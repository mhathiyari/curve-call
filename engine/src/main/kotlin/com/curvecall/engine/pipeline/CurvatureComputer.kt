package com.curvecall.engine.pipeline

import com.curvecall.engine.geo.GeoMath
import com.curvecall.engine.geo.MengerCurvature
import com.curvecall.engine.types.Direction
import com.curvecall.engine.types.LatLon

/**
 * Computes curvature (as radius and direction) at each point along a resampled route,
 * with rolling average smoothing to reduce noise.
 */
object CurvatureComputer {

    /**
     * Per-point curvature result.
     *
     * @property radius Smoothed circumscribed circle radius in meters. [Double.MAX_VALUE] for straight.
     * @property rawRadius Raw (unsmoothed) radius.
     * @property direction Curve direction at this point (LEFT, RIGHT, or null if straight).
     * @property point The geographic coordinate.
     */
    data class CurvaturePoint(
        val radius: Double,
        val rawRadius: Double,
        val direction: Direction?,
        val point: LatLon
    )

    /**
     * Computes curvature at each point in the uniformly-spaced [points] list.
     *
     * For each triplet (i-1, i, i+1), the Menger curvature radius and direction are computed.
     * The first and last points are assigned the same values as their adjacent interior points.
     *
     * A rolling average is applied to smooth the radius values over the given [smoothingWindow].
     *
     * @param points Uniformly spaced route points (output of [Interpolator.resample]).
     * @param smoothingWindow Number of points for the rolling average. Must be odd and >= 1.
     * @return List of [CurvaturePoint] with the same length as [points].
     */
    fun compute(points: List<LatLon>, smoothingWindow: Int = 7): List<CurvaturePoint> {
        require(points.size >= 3) { "Need at least 3 points for curvature, got ${points.size}" }
        require(smoothingWindow >= 1) { "Smoothing window must be >= 1, got $smoothingWindow" }

        // Compute raw radius and direction for each point
        val rawRadii = DoubleArray(points.size)
        val directions = arrayOfNulls<Direction>(points.size)

        for (i in 1 until points.size - 1) {
            rawRadii[i] = MengerCurvature.radius(points[i - 1], points[i], points[i + 1])
            directions[i] = MengerCurvature.direction(points[i - 1], points[i], points[i + 1])
        }

        // Endpoints inherit from their neighbors
        rawRadii[0] = rawRadii[1]
        rawRadii[points.size - 1] = rawRadii[points.size - 2]
        directions[0] = directions[1]
        directions[points.size - 1] = directions[points.size - 2]

        // Reject GPS outliers before smoothing: a single noisy point creates
        // two artificially tight radii that can cause false curve detections.
        rejectOutliers(rawRadii, points)

        // Apply rolling average smoothing to radii
        // For very large radii (straight sections), cap to avoid distortion
        val CAP = 10_000.0
        val cappedRadii = DoubleArray(rawRadii.size) { minOf(rawRadii[it], CAP) }
        val smoothedRadii = rollingAverage(cappedRadii, smoothingWindow)

        return points.mapIndexed { i, point ->
            CurvaturePoint(
                radius = smoothedRadii[i],
                rawRadius = rawRadii[i],
                direction = directions[i],
                point = point
            )
        }
    }

    /** Radius spike threshold: a point's radius must be at least this fraction of
     *  the neighbor median to be considered valid. Below this → likely GPS spike. */
    private const val SPIKE_RATIO = 0.2

    /** Minimum neighbor median radius to apply spike detection. Below this, all
     *  neighbors are already tight curves and a "spike" is likely genuine. */
    private const val SPIKE_NEIGHBOR_MIN_RADIUS = 100.0

    /** Maximum lateral deviation (meters) from the line between neighbors before
     *  a point is flagged as a GPS outlier regardless of curvature. */
    private const val MAX_LATERAL_DEVIATION = 15.0

    /**
     * Detects and repairs single-point GPS spikes in the raw radius array.
     *
     * A spike is identified when either:
     * 1. The point's radius is < [SPIKE_RATIO] of the median of its 4 nearest neighbors,
     *    AND the neighbor median is above [SPIKE_NEIGHBOR_MIN_RADIUS] (so we don't
     *    accidentally smooth genuine hairpins).
     * 2. The point deviates > [MAX_LATERAL_DEVIATION] from the line between its neighbors,
     *    indicating a GPS position jump.
     *
     * Repaired points get the neighbor median radius. Direction is preserved since
     * the direction at a spike point is unreliable anyway and will be overridden
     * by the dominant direction during classification.
     *
     * Modifies [rawRadii] in place.
     */
    private fun rejectOutliers(rawRadii: DoubleArray, points: List<LatLon>) {
        // Need at least 2 neighbors on each side
        if (rawRadii.size < 5) return

        for (i in 2 until rawRadii.size - 2) {
            val neighbors = doubleArrayOf(
                rawRadii[i - 2], rawRadii[i - 1], rawRadii[i + 1], rawRadii[i + 2]
            )
            neighbors.sort()
            val neighborMedian = (neighbors[1] + neighbors[2]) / 2.0

            // Check 1: radius spike — unusually tight compared to neighbors AND
            // both immediate neighbors are normal. This distinguishes a genuine
            // curve entry (where at least one adjacent point is also tight) from
            // an isolated GPS spike (single point dip surrounded by straights).
            val isIsolated = rawRadii[i - 1] > neighborMedian * 0.5 &&
                rawRadii[i + 1] > neighborMedian * 0.5
            val isRadiusSpike = rawRadii[i] < neighborMedian * SPIKE_RATIO &&
                neighborMedian > SPIKE_NEIGHBOR_MIN_RADIUS &&
                isIsolated

            // Check 2: lateral deviation — point far off the line between neighbors
            val (_, lateralDist) = GeoMath.projectOntoSegment(
                points[i], points[i - 1], points[i + 1]
            )
            val isPositionSpike = lateralDist > MAX_LATERAL_DEVIATION

            if (isRadiusSpike || isPositionSpike) {
                rawRadii[i] = neighborMedian
            }
        }
    }

    /**
     * Applies a rolling average (centered window) to the given array.
     * Edges use a smaller window automatically.
     */
    private fun rollingAverage(values: DoubleArray, windowSize: Int): DoubleArray {
        if (windowSize <= 1) return values.copyOf()

        val halfWindow = windowSize / 2
        val result = DoubleArray(values.size)

        for (i in values.indices) {
            val from = maxOf(0, i - halfWindow)
            val to = minOf(values.size - 1, i + halfWindow)
            var sum = 0.0
            for (j in from..to) {
                sum += values[j]
            }
            result[i] = sum / (to - from + 1)
        }

        return result
    }
}

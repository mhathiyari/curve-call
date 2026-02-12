package com.curvecall.engine.pipeline

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

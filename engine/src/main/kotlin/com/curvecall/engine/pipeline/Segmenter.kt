package com.curvecall.engine.pipeline

import com.curvecall.engine.geo.GeoMath
import com.curvecall.engine.types.AnalysisConfig
import com.curvecall.engine.types.LatLon

/**
 * Splits a route into curve and straight segments based on curvature thresholds.
 *
 * A point is "in a curve" if its smoothed radius is below the curvature threshold (default 500m).
 * Consecutive curve points form a curve segment. Short straight gaps between curves
 * (below the merge threshold, default 50m) are merged into a single compound curve.
 */
object Segmenter {

    /**
     * A raw segment before classification — just index ranges and whether it's a curve.
     */
    data class RawSegment(
        val startIndex: Int,
        val endIndex: Int,
        val isCurve: Boolean
    )

    /**
     * Segments the route into alternating curve and straight sections.
     *
     * @param curvaturePoints Output from [CurvatureComputer.compute].
     * @param points The interpolated route points (same indices as curvaturePoints).
     * @param config Analysis configuration with threshold parameters.
     * @return Ordered list of [RawSegment]s covering the entire route.
     */
    fun segment(
        curvaturePoints: List<CurvatureComputer.CurvaturePoint>,
        points: List<LatLon>,
        config: AnalysisConfig
    ): List<RawSegment> {
        require(curvaturePoints.size == points.size) {
            "Curvature points count (${curvaturePoints.size}) must match points count (${points.size})"
        }

        if (curvaturePoints.isEmpty()) return emptyList()

        // Step 1: Mark each point as curve or straight
        val isCurvePoint = BooleanArray(curvaturePoints.size) { i ->
            curvaturePoints[i].radius < config.curvatureThresholdRadius
        }

        // Step 2: Build initial segments from consecutive same-type points
        val initialSegments = buildInitialSegments(isCurvePoint)

        if (initialSegments.isEmpty()) return emptyList()

        // Step 3: Merge short straight gaps between curves
        val merged = mergeShortGaps(initialSegments, points, config.straightGapMerge)

        return merged
    }

    /**
     * Groups consecutive points of the same type (curve/straight) into segments.
     */
    private fun buildInitialSegments(isCurvePoint: BooleanArray): List<RawSegment> {
        if (isCurvePoint.isEmpty()) return emptyList()

        val segments = mutableListOf<RawSegment>()
        var currentStart = 0
        var currentIsCurve = isCurvePoint[0]

        for (i in 1 until isCurvePoint.size) {
            if (isCurvePoint[i] != currentIsCurve) {
                segments.add(RawSegment(currentStart, i - 1, currentIsCurve))
                currentStart = i
                currentIsCurve = isCurvePoint[i]
            }
        }
        // Add the last segment
        segments.add(RawSegment(currentStart, isCurvePoint.size - 1, currentIsCurve))

        return segments
    }

    /**
     * Merges short straight gaps between curve segments.
     * If a straight segment between two curves is shorter than [mergeGap] meters,
     * the straight and both curves are merged into a single curve segment.
     */
    private fun mergeShortGaps(
        segments: List<RawSegment>,
        points: List<LatLon>,
        mergeGap: Double
    ): List<RawSegment> {
        if (segments.size <= 1) return segments

        val result = mutableListOf<RawSegment>()
        var i = 0

        while (i < segments.size) {
            val current = segments[i]

            if (!current.isCurve) {
                // Check if this straight gap should be merged into adjacent curves
                val prev = if (result.isNotEmpty()) result.last() else null
                val next = if (i + 1 < segments.size) segments[i + 1] else null

                if (prev != null && prev.isCurve && next != null && next.isCurve) {
                    val gapLength = segmentLength(current, points)
                    if (gapLength < mergeGap) {
                        // Merge: remove the previous curve from result, skip this straight,
                        // and merge with the next curve
                        result.removeAt(result.size - 1)
                        result.add(RawSegment(prev.startIndex, next.endIndex, true))
                        i += 2 // skip both the straight and the next curve
                        continue
                    }
                }
            }

            result.add(current)
            i++
        }

        return result
    }

    /**
     * Computes the length of a segment in meters.
     */
    private fun segmentLength(segment: RawSegment, points: List<LatLon>): Double {
        var length = 0.0
        for (j in segment.startIndex until segment.endIndex) {
            length += GeoMath.haversineDistance(points[j], points[j + 1])
        }
        return length
    }
}

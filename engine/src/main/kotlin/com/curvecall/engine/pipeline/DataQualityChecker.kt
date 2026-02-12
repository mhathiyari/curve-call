package com.curvecall.engine.pipeline

import com.curvecall.engine.geo.GeoMath
import com.curvecall.engine.types.AnalysisConfig
import com.curvecall.engine.types.CurveSegment
import com.curvecall.engine.types.LatLon
import kotlin.math.abs

/**
 * Checks data quality by detecting segments with low node density in the original
 * (pre-interpolation) data. Sparse data with bearing changes may indicate curves
 * that were poorly resolved.
 *
 * When original node spacing exceeds the threshold (default 100m) in a section
 * with significant bearing change, curves in that area are flagged as low-confidence.
 */
object DataQualityChecker {

    /**
     * Represents a region of sparse data in the original points.
     *
     * @property startDistance Distance from route start (meters).
     * @property endDistance Distance from route start (meters).
     * @property maxSpacing Maximum node spacing found in this region.
     * @property bearingChange Total bearing change across the sparse region.
     */
    data class SparseRegion(
        val startDistance: Double,
        val endDistance: Double,
        val maxSpacing: Double,
        val bearingChange: Double
    )

    /**
     * Detects sparse regions in the original (pre-interpolation) point data.
     *
     * A sparse region is identified where:
     * 1. The spacing between consecutive original points exceeds [config.sparseNodeThreshold]
     * 2. There is a significant bearing change (> 10 degrees) across the gap
     *
     * @param originalPoints The raw input points (before interpolation).
     * @param config Analysis configuration.
     * @return List of sparse regions found.
     */
    fun detectSparseRegions(
        originalPoints: List<LatLon>,
        config: AnalysisConfig
    ): List<SparseRegion> {
        if (originalPoints.size < 3) return emptyList()

        val sparseRegions = mutableListOf<SparseRegion>()
        var cumulativeDistance = 0.0

        for (i in 0 until originalPoints.size - 1) {
            val spacing = GeoMath.haversineDistance(originalPoints[i], originalPoints[i + 1])
            val prevCumulativeDistance = cumulativeDistance

            if (spacing > config.sparseNodeThreshold) {
                // Check bearing change across this gap
                val bearingChange = if (i > 0 && i + 2 < originalPoints.size) {
                    val bearingBefore = GeoMath.bearing(originalPoints[i - 1], originalPoints[i])
                    val bearingAfter = GeoMath.bearing(originalPoints[i + 1], originalPoints[i + 2])
                    abs(GeoMath.bearingDifference(bearingBefore, bearingAfter))
                } else if (i + 1 < originalPoints.size - 1) {
                    val bearingAcross = GeoMath.bearing(originalPoints[i], originalPoints[i + 1])
                    val bearingAfter = GeoMath.bearing(originalPoints[i + 1], originalPoints[i + 2])
                    abs(GeoMath.bearingDifference(bearingAcross, bearingAfter))
                } else {
                    0.0
                }

                // Only flag as sparse if there's a meaningful bearing change
                // (straight roads with sparse data are not a quality concern)
                if (bearingChange > 10.0) {
                    sparseRegions.add(
                        SparseRegion(
                            startDistance = prevCumulativeDistance,
                            endDistance = prevCumulativeDistance + spacing,
                            maxSpacing = spacing,
                            bearingChange = bearingChange
                        )
                    )
                }
            }

            cumulativeDistance += spacing
        }

        return sparseRegions
    }

    /**
     * Applies confidence scores to curve segments based on detected sparse regions.
     *
     * Curves that overlap with sparse regions get reduced confidence:
     * - Fully within a sparse region: 0.3
     * - Partially overlapping: 0.6
     * - No overlap: 1.0 (unchanged)
     *
     * @param curves List of classified curve segments.
     * @param sparseRegions Detected sparse data regions.
     * @return Updated curves with adjusted confidence scores.
     */
    fun applyConfidence(
        curves: List<CurveSegment>,
        sparseRegions: List<SparseRegion>
    ): List<CurveSegment> {
        if (sparseRegions.isEmpty()) return curves

        return curves.map { curve ->
            val curveStart = curve.distanceFromStart
            val curveEnd = curve.distanceFromStart + curve.arcLength

            var minConfidence = 1.0f

            for (sparse in sparseRegions) {
                // Check overlap between curve and sparse region
                val overlapStart = maxOf(curveStart, sparse.startDistance)
                val overlapEnd = minOf(curveEnd, sparse.endDistance)

                if (overlapStart < overlapEnd) {
                    val overlapLength = overlapEnd - overlapStart
                    val curveLength = curveEnd - curveStart

                    val overlapFraction = if (curveLength > 0) {
                        (overlapLength / curveLength).toFloat()
                    } else {
                        1.0f
                    }

                    // Higher overlap = lower confidence
                    val confidence = if (overlapFraction > 0.8f) 0.3f else 0.6f
                    minConfidence = minOf(minConfidence, confidence)
                }
            }

            if (minConfidence < 1.0f) {
                curve.copy(confidence = minConfidence)
            } else {
                curve
            }
        }
    }
}

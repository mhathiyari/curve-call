package com.curvecall.engine

import com.curvecall.engine.geo.GeoMath
import com.curvecall.engine.pipeline.*
import com.curvecall.engine.types.*

/**
 * Pipeline orchestrator that transforms raw GPS coordinates into a fully classified
 * list of route segments (curves and straights).
 *
 * This is the main entry point for the engine module:
 *   analyzeRoute(points, config) -> List<RouteSegment>
 *
 * Pipeline stages:
 * 1. Interpolation - resample to uniform spacing
 * 2. Curvature computation - Menger curvature + smoothing
 * 3. Segmentation - split into curve vs straight segments
 * 4. Classification - severity, direction, modifiers, angle change
 * 5. Speed advisory - physics-based recommended speeds
 * 6. Lean angle - motorcycle cornering angle
 * 7. Compound detection - S-bends, chicanes, series
 * 8. Data quality - confidence scoring from original data density
 */
class RouteAnalyzer {

    /**
     * Result of route analysis, containing the classified segments plus metadata.
     */
    data class AnalysisResult(
        /** Ordered list of route segments (curves and straights). */
        val segments: List<RouteSegment>,

        /** The interpolated (uniformly spaced) points used for analysis. */
        val interpolatedPoints: List<LatLon>,

        /** Detected sparse data regions in the original input. */
        val sparseRegions: List<DataQualityChecker.SparseRegion>,

        /** Total route distance in meters. */
        val totalDistance: Double,

        /** Number of curves detected. */
        val curveCount: Int
    )

    /**
     * Analyzes a route and returns classified segments.
     *
     * @param points Ordered list of route coordinates (from routing engine).
     *               Must contain at least 3 points.
     * @param config Analysis configuration with all thresholds and parameters.
     * @param metadata Optional road metadata from the routing engine (e.g., GraphHopper).
     * @return Ordered list of [RouteSegment] covering the entire route.
     * @throws IllegalArgumentException if fewer than 3 points are provided.
     */
    fun analyzeRoute(
        points: List<LatLon>,
        config: AnalysisConfig = AnalysisConfig(),
        metadata: RouteMetadata? = null
    ): List<RouteSegment> {
        return analyzeRouteDetailed(points, config, metadata).segments
    }

    /**
     * Analyzes a route and returns full analysis result with metadata.
     *
     * @param points Ordered list of route coordinates.
     * @param config Analysis configuration.
     * @param metadata Optional road metadata from the routing engine. When present,
     *                 data quality checks are skipped (OSM-sourced data is high quality)
     *                 and curve segments are enriched with road class, surface, speed limit,
     *                 and intersection data.
     * @return Full [AnalysisResult] including segments, interpolated points, and quality info.
     */
    fun analyzeRouteDetailed(
        points: List<LatLon>,
        config: AnalysisConfig = AnalysisConfig(),
        metadata: RouteMetadata? = null
    ): AnalysisResult {
        require(points.size >= 3) {
            "Route must contain at least 3 points, but had ${points.size}"
        }

        // Stage 1: Interpolation - resample to uniform spacing
        val interpolated = Interpolator.resample(points, config.interpolationSpacing)

        // Early exit if interpolation produced too few points
        if (interpolated.size < 3) {
            return AnalysisResult(
                segments = emptyList(),
                interpolatedPoints = interpolated,
                sparseRegions = emptyList(),
                totalDistance = computeTotalDistance(interpolated),
                curveCount = 0
            )
        }

        // Stage 2: Curvature computation with smoothing
        val curvaturePoints = CurvatureComputer.compute(interpolated, config.smoothingWindow)

        // Stage 3: Segmentation into curve and straight regions
        val rawSegments = Segmenter.segment(curvaturePoints, interpolated, config)

        // Stage 4 & 5 & 6: Classification + Speed Advisory + Lean Angle
        val cumulativeDistances = computeCumulativeDistances(interpolated)
        val classifiedSegments = rawSegments.map { raw ->
            val distFromStart = cumulativeDistances[raw.startIndex]

            if (raw.isCurve) {
                // Classify (with optional road metadata enrichment)
                var curve = Classifier.classify(raw, curvaturePoints, interpolated, config, distFromStart, metadata)

                // Apply speed advisory
                curve = SpeedAdvisor.applyAdvisory(curve, config)

                // Apply lean angle (motorcycle mode)
                curve = LeanAngleCalculator.applyLeanAngle(curve, config.isMotorcycleMode)

                RouteSegment.Curve(curve)
            } else {
                // Straight segment
                val length = computeSegmentLength(interpolated, raw.startIndex, raw.endIndex)
                RouteSegment.Straight(
                    StraightSegment(
                        length = length,
                        startIndex = raw.startIndex,
                        endIndex = raw.endIndex,
                        distanceFromStart = distFromStart
                    )
                )
            }
        }

        // Stage 7: Compound detection
        val withCompounds = CompoundDetector.detect(classifiedSegments, interpolated, config)

        // Stage 8: Data quality check (skipped for routing-engine data — OSM is high quality)
        val sparseRegions = if (metadata != null) {
            emptyList()
        } else {
            DataQualityChecker.detectSparseRegions(points, config)
        }
        val finalSegments = if (sparseRegions.isNotEmpty()) {
            applyDataQuality(withCompounds, sparseRegions)
        } else {
            withCompounds
        }

        val totalDistance = if (cumulativeDistances.isNotEmpty()) {
            cumulativeDistances.last()
        } else {
            0.0
        }

        val curveCount = finalSegments.count { it is RouteSegment.Curve }

        return AnalysisResult(
            segments = finalSegments,
            interpolatedPoints = interpolated,
            sparseRegions = sparseRegions,
            totalDistance = totalDistance,
            curveCount = curveCount
        )
    }

    /**
     * Computes cumulative distance from the start for each point.
     */
    private fun computeCumulativeDistances(points: List<LatLon>): DoubleArray {
        val distances = DoubleArray(points.size)
        for (i in 1 until points.size) {
            distances[i] = distances[i - 1] + GeoMath.haversineDistance(points[i - 1], points[i])
        }
        return distances
    }

    /**
     * Computes the distance along a range of points.
     */
    private fun computeSegmentLength(points: List<LatLon>, startIdx: Int, endIdx: Int): Double {
        var length = 0.0
        for (i in startIdx until endIdx) {
            length += GeoMath.haversineDistance(points[i], points[i + 1])
        }
        return length
    }

    /**
     * Computes total distance of a polyline.
     */
    private fun computeTotalDistance(points: List<LatLon>): Double {
        var total = 0.0
        for (i in 0 until points.size - 1) {
            total += GeoMath.haversineDistance(points[i], points[i + 1])
        }
        return total
    }

    /**
     * Applies data quality confidence scores to curve segments.
     */
    private fun applyDataQuality(
        segments: List<RouteSegment>,
        sparseRegions: List<DataQualityChecker.SparseRegion>
    ): List<RouteSegment> {
        val curves = segments.filterIsInstance<RouteSegment.Curve>().map { it.data }
        val updatedCurves = DataQualityChecker.applyConfidence(curves, sparseRegions)

        // Build a map from startIndex to updated curve
        val curveMap = updatedCurves.associateBy { it.startIndex }

        return segments.map { segment ->
            when (segment) {
                is RouteSegment.Curve -> {
                    val updated = curveMap[segment.data.startIndex]
                    if (updated != null) RouteSegment.Curve(updated) else segment
                }
                is RouteSegment.Straight -> segment
            }
        }
    }
}

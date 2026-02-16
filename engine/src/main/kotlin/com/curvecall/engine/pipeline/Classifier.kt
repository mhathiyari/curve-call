package com.curvecall.engine.pipeline

import com.curvecall.engine.geo.GeoMath
import com.curvecall.engine.types.*
import kotlin.math.abs

/**
 * Classifies raw curve segments into fully described [CurveSegment] objects,
 * computing direction, severity, modifiers, angle change, and 90-degree detection.
 */
object Classifier {

    /**
     * Classifies a raw curve segment, computing all properties needed for narration.
     *
     * @param rawSegment The index range of the curve (must have isCurve = true).
     * @param curvaturePoints Per-point curvature data from [CurvatureComputer].
     * @param points The interpolated route points.
     * @param config Analysis configuration.
     * @param distanceFromStart Distance from route start to this segment's start point.
     * @return A fully classified [CurveSegment].
     */
    fun classify(
        rawSegment: Segmenter.RawSegment,
        curvaturePoints: List<CurvatureComputer.CurvaturePoint>,
        points: List<LatLon>,
        config: AnalysisConfig,
        distanceFromStart: Double
    ): CurveSegment {
        require(rawSegment.isCurve) { "Can only classify curve segments" }

        val startIdx = rawSegment.startIndex
        val endIdx = rawSegment.endIndex

        // Direction: dominant sign of cross products across the segment
        val direction = computeDirection(curvaturePoints, startIdx, endIdx)

        // Minimum radius in the segment
        val minRadius = computeMinRadius(curvaturePoints, startIdx, endIdx)

        // Severity from radius thresholds
        val severity = classifySeverity(minRadius, config.severityThresholds)

        // Arc length
        val arcLength = computeArcLength(points, startIdx, endIdx)

        // Modifiers
        val modifiers = computeModifiers(curvaturePoints, startIdx, endIdx, arcLength)

        // Total angle change
        val totalAngleChange = computeTotalAngleChange(points, startIdx, endIdx)

        // 90-degree detection
        val is90Degree = abs(totalAngleChange) in 85.0..95.0 && arcLength < 50.0

        return CurveSegment(
            direction = direction,
            severity = severity,
            minRadius = minRadius,
            arcLength = arcLength,
            modifiers = modifiers,
            totalAngleChange = totalAngleChange,
            is90Degree = is90Degree,
            advisorySpeedMs = null,  // Computed later by SpeedAdvisor
            leanAngleDeg = null,     // Computed later by LeanAngleCalculator
            compoundType = null,     // Computed later by CompoundDetector
            compoundSize = null,     // Computed later by CompoundDetector
            confidence = 1.0f,       // Adjusted later by DataQualityChecker
            startIndex = startIdx,
            endIndex = endIdx,
            startPoint = points[startIdx],
            endPoint = points[endIdx],
            distanceFromStart = distanceFromStart
        )
    }

    /**
     * Determines the dominant curve direction from cross product signs.
     */
    private fun computeDirection(
        curvaturePoints: List<CurvatureComputer.CurvaturePoint>,
        startIdx: Int,
        endIdx: Int
    ): Direction {
        var leftCount = 0
        var rightCount = 0

        for (i in startIdx..endIdx) {
            when (curvaturePoints[i].direction) {
                Direction.LEFT -> leftCount++
                Direction.RIGHT -> rightCount++
                null -> { /* collinear, skip */ }
            }
        }

        return if (leftCount >= rightCount) Direction.LEFT else Direction.RIGHT
    }

    /**
     * Finds the minimum smoothed radius within the segment.
     *
     * Uses the smoothed radius to avoid noise spikes in raw data that would
     * falsely classify gentle curves as hairpins. The smoothing window (5 pts
     * at 5m spacing = 25m) is tight enough to preserve genuine hairpin signals
     * while filtering single-point noise.
     */
    private fun computeMinRadius(
        curvaturePoints: List<CurvatureComputer.CurvaturePoint>,
        startIdx: Int,
        endIdx: Int
    ): Double {
        var minRadius = Double.MAX_VALUE
        for (i in startIdx..endIdx) {
            if (curvaturePoints[i].radius < minRadius) {
                minRadius = curvaturePoints[i].radius
            }
        }
        return minRadius
    }

    /**
     * Maps a minimum radius to a severity level using the configured thresholds.
     */
    fun classifySeverity(minRadius: Double, thresholds: SeverityThresholds): Severity {
        return when {
            minRadius > thresholds.gentle -> Severity.GENTLE
            minRadius > thresholds.moderate -> Severity.MODERATE
            minRadius > thresholds.firm -> Severity.FIRM
            minRadius > thresholds.sharp -> Severity.SHARP
            else -> Severity.HAIRPIN
        }
    }

    /**
     * Computes the total arc length of a segment by summing point-to-point distances.
     */
    private fun computeArcLength(points: List<LatLon>, startIdx: Int, endIdx: Int): Double {
        var length = 0.0
        for (i in startIdx until endIdx) {
            length += GeoMath.haversineDistance(points[i], points[i + 1])
        }
        return length
    }

    /**
     * Determines modifiers: TIGHTENING, OPENING, HOLDS, LONG.
     *
     * - TIGHTENING: average radius of last third < average radius of first third (getting tighter)
     * - OPENING: average radius of last third > average radius of first third (opening up)
     * - LONG: arc length > 200m
     * - HOLDS: constant radius (no tightening/opening) over a long arc (>200m)
     */
    private fun computeModifiers(
        curvaturePoints: List<CurvatureComputer.CurvaturePoint>,
        startIdx: Int,
        endIdx: Int,
        arcLength: Double
    ): Set<CurveModifier> {
        val modifiers = mutableSetOf<CurveModifier>()

        val count = endIdx - startIdx + 1
        if (count < 3) return modifiers

        // Divide into thirds
        val thirdSize = count / 3
        if (thirdSize < 1) return modifiers

        val firstThirdAvg = averageRadius(curvaturePoints, startIdx, startIdx + thirdSize - 1)
        val lastThirdAvg = averageRadius(curvaturePoints, endIdx - thirdSize + 1, endIdx)

        // Tightening: last third has significantly smaller radius (tighter) than first third
        // Use a 20% threshold to avoid false positives from noise
        val tighteningRatio = 0.8
        val openingRatio = 1.2

        if (lastThirdAvg < firstThirdAvg * tighteningRatio) {
            modifiers.add(CurveModifier.TIGHTENING)
        } else if (lastThirdAvg > firstThirdAvg * openingRatio) {
            modifiers.add(CurveModifier.OPENING)
        }

        if (arcLength > 200.0) {
            modifiers.add(CurveModifier.LONG)

            // HOLDS: long arc with no significant tightening or opening
            if (CurveModifier.TIGHTENING !in modifiers && CurveModifier.OPENING !in modifiers) {
                modifiers.add(CurveModifier.HOLDS)
            }
        }

        return modifiers
    }

    /**
     * Computes the average smoothed radius over a range of curvature points.
     */
    private fun averageRadius(
        curvaturePoints: List<CurvatureComputer.CurvaturePoint>,
        from: Int,
        to: Int
    ): Double {
        var sum = 0.0
        var count = 0
        for (i in from..to) {
            // Cap very large radii to avoid skewing the average
            sum += minOf(curvaturePoints[i].radius, 10_000.0)
            count++
        }
        return if (count > 0) sum / count else Double.MAX_VALUE
    }

    /**
     * Computes the total angle change through the curve.
     * This is the difference between the entry bearing and the exit bearing.
     *
     * @return Absolute angle change in degrees (always positive).
     */
    private fun computeTotalAngleChange(points: List<LatLon>, startIdx: Int, endIdx: Int): Double {
        if (endIdx - startIdx < 1) return 0.0

        val entryBearing = GeoMath.bearing(points[startIdx], points[minOf(startIdx + 1, endIdx)])
        val exitBearing = GeoMath.bearing(points[maxOf(endIdx - 1, startIdx)], points[endIdx])

        return abs(GeoMath.bearingDifference(entryBearing, exitBearing))
    }
}

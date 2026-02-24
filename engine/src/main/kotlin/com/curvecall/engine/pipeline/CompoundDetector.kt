package com.curvecall.engine.pipeline

import com.curvecall.engine.geo.GeoMath
import com.curvecall.engine.types.*

/**
 * Detects compound curve patterns (S-bends, chicanes, series, tightening sequences)
 * from a sequence of classified curve segments.
 *
 * Detection rules from PRD Section 7.8:
 * - S-bend: Two curves, opposite direction, < 50m gap
 * - Chicane: S-bend where both curves are SHARP or tighter
 * - Series: 3+ curves linked with < 50m gaps
 * - Tightening sequence: Same-direction curves, each tighter than the last
 */
object CompoundDetector {

    private const val SWITCHBACK_MAX_GAP = 200.0

    /**
     * Detects compound patterns and returns updated curve segments with compound annotations.
     *
     * @param segments The ordered list of route segments (curves and straights).
     * @param points The interpolated route points.
     * @param config Analysis configuration.
     * @return Updated list of segments with compound type/size annotations on relevant curves.
     */
    fun detect(
        segments: List<RouteSegment>,
        points: List<LatLon>,
        config: AnalysisConfig
    ): List<RouteSegment> {
        val curves = segments.filterIsInstance<RouteSegment.Curve>()
        if (curves.size < 2) return segments

        // Build adjacency info: for each consecutive pair of curves, compute the gap
        data class CurvePair(
            val curveA: CurveSegment,
            val curveB: CurveSegment,
            val gap: Double
        )

        val curvePairs = mutableListOf<CurvePair>()
        for (i in 0 until curves.size - 1) {
            val a = curves[i].data
            val b = curves[i + 1].data
            val gap = computeGapBetweenCurves(a, b, points)
            curvePairs.add(CurvePair(a, b, gap))
        }

        // Track which curves get compound annotations
        // Map from curve startIndex to updated CurveSegment
        val compoundUpdates = mutableMapOf<Int, Pair<CompoundType, Int>>()
        val positionUpdates = mutableMapOf<Int, Int>()  // startIndex → positionInCompound

        // Detect S-bends/chicanes first — they're the most safety-critical compound
        // pattern (require counter-steer). Then switchbacks, series, tightening from remaining curves.
        detectSBendsAndChicanes(curves, curvePairs, config, compoundUpdates)
        detectSwitchbacks(curves, config, compoundUpdates, positionUpdates)
        detectSeries(curves, curvePairs, config, compoundUpdates)
        detectTighteningSequences(curves, curvePairs, config, compoundUpdates)

        // Apply compound annotations to the segments
        return segments.map { segment ->
            when (segment) {
                is RouteSegment.Curve -> {
                    val update = compoundUpdates[segment.data.startIndex]
                    val position = positionUpdates[segment.data.startIndex]
                    if (update != null || position != null) {
                        RouteSegment.Curve(
                            segment.data.copy(
                                compoundType = update?.first ?: segment.data.compoundType,
                                compoundSize = update?.second ?: segment.data.compoundSize,
                                positionInCompound = position
                            )
                        )
                    } else {
                        segment
                    }
                }
                is RouteSegment.Straight -> segment
            }
        }
    }

    /**
     * Detects switchback sequences: 3+ consecutive SHARP or HAIRPIN curves with
     * alternating directions and gaps < 200m. Sets positionInCompound (1-indexed).
     */
    private fun detectSwitchbacks(
        curves: List<RouteSegment.Curve>,
        config: AnalysisConfig,
        compoundUpdates: MutableMap<Int, Pair<CompoundType, Int>>,
        positionUpdates: MutableMap<Int, Int>
    ) {
        if (curves.size < 3) return

        var seqStart = 0
        while (seqStart < curves.size) {
            val startCurve = curves[seqStart].data

            // Skip if already compound or not sharp+
            if (startCurve.startIndex in compoundUpdates || !isSeveritySharpOrTighter(startCurve.severity)) {
                seqStart++
                continue
            }

            var seqEnd = seqStart
            while (seqEnd < curves.size - 1) {
                val current = curves[seqEnd].data
                val next = curves[seqEnd + 1].data

                // Next must not already be compound
                if (next.startIndex in compoundUpdates) break
                // Next must be sharp or tighter
                if (!isSeveritySharpOrTighter(next.severity)) break
                // Must alternate direction
                if (current.direction == next.direction) break
                // Gap must be < 200m
                val gap = next.distanceFromStart - (current.distanceFromStart + current.arcLength)
                if (gap >= SWITCHBACK_MAX_GAP) break

                seqEnd++
            }

            val seqLength = seqEnd - seqStart + 1
            if (seqLength >= 3) {
                for (k in seqStart..seqEnd) {
                    val curve = curves[k].data
                    compoundUpdates[curve.startIndex] = Pair(CompoundType.SWITCHBACKS, seqLength)
                    positionUpdates[curve.startIndex] = k - seqStart + 1 // 1-indexed
                }
                seqStart = seqEnd + 1
            } else {
                seqStart++
            }
        }
    }

    /**
     * Detects series: 3+ curves linked with < mergeGap gaps.
     */
    private fun detectSeries(
        curves: List<RouteSegment.Curve>,
        pairs: List<Any>, // Use the pairs computed above
        config: AnalysisConfig,
        updates: MutableMap<Int, Pair<CompoundType, Int>>
    ) {
        // Re-compute gaps directly for clarity
        if (curves.size < 3) return

        // Find runs of curves where consecutive gaps are all < straightGapMerge
        var runStart = 0
        while (runStart < curves.size) {
            var runEnd = runStart

            while (runEnd < curves.size - 1) {
                val gapStartIdx = curves[runEnd].data.endIndex
                val gapEndIdx = curves[runEnd + 1].data.startIndex
                val gap = if (gapEndIdx > gapStartIdx) {
                    // Compute gap distance
                    var gapDist = 0.0
                    // Approximate: use distanceFromStart difference
                    gapDist = curves[runEnd + 1].data.distanceFromStart -
                            (curves[runEnd].data.distanceFromStart + curves[runEnd].data.arcLength)
                    if (gapDist < 0) gapDist = 0.0
                    gapDist
                } else {
                    0.0
                }

                if (gap < config.straightGapMerge) {
                    runEnd++
                } else {
                    break
                }
            }

            val runLength = runEnd - runStart + 1
            if (runLength >= 3) {
                // Mark all curves in this run as SERIES
                for (i in runStart..runEnd) {
                    val curve = curves[i].data
                    // Don't overwrite existing compound annotations
                    if (curve.startIndex !in updates) {
                        updates[curve.startIndex] = Pair(CompoundType.SERIES, runLength)
                    }
                }
            }

            runStart = runEnd + 1
        }
    }

    /**
     * Detects S-bends and chicanes from consecutive curve pairs.
     */
    private fun detectSBendsAndChicanes(
        curves: List<RouteSegment.Curve>,
        pairs: List<Any>,
        config: AnalysisConfig,
        updates: MutableMap<Int, Pair<CompoundType, Int>>
    ) {
        for (i in 0 until curves.size - 1) {
            val a = curves[i].data
            val b = curves[i + 1].data

            // Skip if already part of a compound
            if (a.startIndex in updates || b.startIndex in updates) continue

            // Check gap
            val gap = b.distanceFromStart - (a.distanceFromStart + a.arcLength)
            if (gap >= config.straightGapMerge) continue

            // Must be opposite direction for S-bend
            if (a.direction == b.direction) continue

            // Determine if chicane (both SHARP or tighter)
            val isChicane = isSeveritySharpOrTighter(a.severity) &&
                    isSeveritySharpOrTighter(b.severity)

            val compoundType = if (isChicane) CompoundType.CHICANE else CompoundType.S_BEND

            updates[a.startIndex] = Pair(compoundType, 2)
            updates[b.startIndex] = Pair(compoundType, 2)
        }
    }

    /**
     * Detects tightening sequences: same-direction curves where each is tighter than the last.
     */
    private fun detectTighteningSequences(
        curves: List<RouteSegment.Curve>,
        pairs: List<Any>,
        config: AnalysisConfig,
        updates: MutableMap<Int, Pair<CompoundType, Int>>
    ) {
        if (curves.size < 2) return

        var seqStart = 0
        while (seqStart < curves.size - 1) {
            val startCurve = curves[seqStart].data

            // Skip if already compound
            if (startCurve.startIndex in updates) {
                seqStart++
                continue
            }

            var seqEnd = seqStart
            while (seqEnd < curves.size - 1) {
                val current = curves[seqEnd].data
                val next = curves[seqEnd + 1].data

                // Must be same direction
                if (current.direction != next.direction) break

                // Must be linked (< mergeGap)
                val gap = next.distanceFromStart - (current.distanceFromStart + current.arcLength)
                if (gap >= config.straightGapMerge) break

                // Next must be tighter (smaller radius)
                if (next.minRadius >= current.minRadius) break

                // Skip if next already has a compound annotation
                if (next.startIndex in updates) break

                seqEnd++
            }

            val seqLength = seqEnd - seqStart + 1
            if (seqLength >= 2) {
                for (j in seqStart..seqEnd) {
                    val curve = curves[j].data
                    updates[curve.startIndex] = Pair(CompoundType.TIGHTENING_SEQUENCE, seqLength)
                }
            }

            seqStart = seqEnd + 1
        }
    }

    /**
     * Checks if a severity is SHARP or tighter (HAIRPIN).
     */
    private fun isSeveritySharpOrTighter(severity: Severity): Boolean {
        return severity == Severity.SHARP || severity == Severity.HAIRPIN
    }

    /**
     * Computes the gap distance between two curve segments using the interpolated points.
     */
    private fun computeGapBetweenCurves(
        a: CurveSegment,
        b: CurveSegment,
        points: List<LatLon>
    ): Double {
        if (b.startIndex <= a.endIndex) return 0.0

        var gap = 0.0
        for (i in a.endIndex until b.startIndex) {
            if (i + 1 < points.size) {
                gap += GeoMath.haversineDistance(points[i], points[i + 1])
            }
        }
        return gap
    }
}

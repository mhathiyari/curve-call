package com.curvecall.narration

import com.curvecall.engine.types.CurveModifier
import com.curvecall.engine.types.CurveSegment
import com.curvecall.engine.types.Severity

/**
 * Detects winding road sections — clusters of 6+ curves within a time-distance
 * window — and determines which individual curves "break through" the winding
 * suppression for individual narration.
 */
class WindingDetector {

    /**
     * A detected winding road section.
     * @property startDistance Distance from route start to first curve in section (m).
     * @property endDistance Distance from route start to end of last curve in section (m).
     * @property curveCount Number of curves in the section.
     * @property maxSeverity The highest severity among all curves in the section.
     * @property medianSeverity The median severity (ordinal-based) of curves in the section.
     * @property advisorySpeedMs Most conservative advisory speed across all curves, or null.
     * @property curves The curves that belong to this winding section.
     */
    data class WindingSection(
        val startDistance: Double,
        val endDistance: Double,
        val curveCount: Int,
        val maxSeverity: Severity,
        val medianSeverity: Severity,
        val advisorySpeedMs: Double?,
        val curves: List<CurveSegment>
    )

    /**
     * Detect winding sections from a list of curves.
     *
     * A winding section is 6+ curves where each consecutive pair is reachable within
     * [windowSeconds] at the given [estimatedSpeedMs].
     *
     * Window distance = estimatedSpeedMs * windowSeconds (default: 60s).
     * A new curve extends the section if its distanceFromStart minus the previous
     * curve's (distanceFromStart + arcLength) is <= windowDistance.
     *
     * @param curves All curves on the route, ordered by distanceFromStart.
     * @param estimatedSpeedMs Estimated travel speed in m/s (used for time-window conversion).
     * @param windowSeconds Time window in seconds (default 60).
     * @return List of detected winding sections.
     */
    fun detectWindingSections(
        curves: List<CurveSegment>,
        estimatedSpeedMs: Double,
        windowSeconds: Double = WINDOW_SECONDS
    ): List<WindingSection> {
        if (curves.size < MIN_CURVES_FOR_WINDING) return emptyList()

        val windowDistance = estimatedSpeedMs * windowSeconds
        val sections = mutableListOf<WindingSection>()
        var i = 0

        while (i < curves.size) {
            // Try to build a winding section starting at curve i
            var j = i
            while (j < curves.size - 1) {
                val gap = curves[j + 1].distanceFromStart -
                    (curves[j].distanceFromStart + curves[j].arcLength)
                if (gap <= windowDistance) {
                    j++
                } else {
                    break
                }
            }

            val sectionSize = j - i + 1
            if (sectionSize >= MIN_CURVES_FOR_WINDING) {
                val sectionCurves = curves.subList(i, j + 1)
                val severities = sectionCurves.map { it.severity }.sorted()
                val medianSev = severities[severities.size / 2]

                sections.add(
                    WindingSection(
                        startDistance = sectionCurves.first().distanceFromStart,
                        endDistance = sectionCurves.last().distanceFromStart + sectionCurves.last().arcLength,
                        curveCount = sectionSize,
                        maxSeverity = sectionCurves.maxOf { it.severity },
                        medianSeverity = medianSev,
                        advisorySpeedMs = sectionCurves.mapNotNull { it.advisorySpeedMs }.minOrNull(),
                        curves = sectionCurves.toList()
                    )
                )
                i = j + 1 // Skip past this section
            } else {
                i++
            }
        }

        return sections
    }

    /**
     * Determine if an individual curve within a winding section should still get
     * its own narration ("breakthrough" rules).
     *
     * A curve breaks through winding suppression if:
     * - It is HAIRPIN severity
     * - It has TIGHTENING modifier
     * - Its severity ordinal >= section's medianSeverity ordinal + 2 (significantly worse)
     *
     * @param curve The individual curve to evaluate.
     * @param section The winding section it belongs to.
     * @return true if the curve should be individually narrated despite winding suppression.
     */
    fun shouldNarrateInWindingSection(curve: CurveSegment, section: WindingSection): Boolean {
        // Always narrate hairpins
        if (curve.severity == Severity.HAIRPIN) return true
        // Always narrate sharp and above (safety-critical)
        if (curve.severity >= Severity.SHARP) return true
        // Always narrate tightening curves
        if (CurveModifier.TIGHTENING in curve.modifiers) return true
        // Narrate if severity is median + 2 or more
        if (curve.severity.ordinal >= section.medianSeverity.ordinal + 2) return true
        return false
    }

    companion object {
        const val MIN_CURVES_FOR_WINDING = 6
        const val WINDOW_SECONDS = 60.0
    }
}

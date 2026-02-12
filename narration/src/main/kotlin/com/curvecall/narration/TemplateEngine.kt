package com.curvecall.narration

import com.curvecall.engine.types.CompoundType
import com.curvecall.engine.types.CurveModifier
import com.curvecall.engine.types.CurveSegment
import com.curvecall.engine.types.Direction
import com.curvecall.engine.types.Severity
import com.curvecall.engine.types.StraightSegment
import com.curvecall.narration.types.DrivingMode
import com.curvecall.narration.types.NarrationConfig
import com.curvecall.narration.types.NarrationEvent
import com.curvecall.narration.types.SpeedUnit
import kotlin.math.roundToInt

/**
 * Generates spoken narration text from analyzed curve segments.
 *
 * The TemplateEngine is the core text generation component of the narration system.
 * Given a [CurveSegment] and a [NarrationConfig], it produces a natural-language string
 * suitable for TTS delivery.
 *
 * Narration rules (from PRD Section 6):
 * - Standard (gentle/moderate/firm): "[Direction] curve ahead, [severity]"
 * - Sharp: "Sharp [direction] ahead"
 * - Tightening: append ", tightening" (ALWAYS narrate)
 * - Opening: append ", opening" (Standard+ verbosity)
 * - Holds/Long: "holds for X meters" / "long" (Standard+)
 * - Speed advisory: ", slow to [speed]"
 * - 90-degree: "90 degree [direction] ahead, slow to [speed]"
 * - Hairpin: "Hairpin [direction] ahead, slow to [speed]"
 * - Motorcycle lean: ", lean [X] degrees"
 * - Motorcycle tightening: prepend "Caution, "
 * - S-bend: "[Dir1] into [dir2], S-bend, [max severity]"
 * - Chicane: "Chicane, [dir1]-[dir2], slow to [speed]"
 * - Series: "Series of [N] curves, [max severity]"
 * - Straight: "Straight, [distance] meters" (Detailed verbosity only)
 * - Sparse data: "Low data quality ahead -- curve information may be incomplete"
 *
 * This class is pure Kotlin with no Android dependencies.
 */
class TemplateEngine {

    /**
     * Generate narration text for a curve segment.
     *
     * @param curve The analyzed curve segment.
     * @param config Narration configuration (mode, verbosity, units).
     * @return The narration text string, or null if this curve should not be narrated
     *   at the current verbosity level.
     */
    fun generateNarration(curve: CurveSegment, config: NarrationConfig): String? {
        // Verbosity filtering: check if this curve should be narrated
        if (!shouldNarrate(curve, config)) {
            return null
        }

        // Low confidence warning
        if (curve.confidence < LOW_CONFIDENCE_THRESHOLD) {
            return SPARSE_DATA_WARNING
        }

        // Dispatch to compound or single-curve template
        return when (curve.compoundType) {
            CompoundType.S_BEND -> generateSBendNarration(curve, config)
            CompoundType.CHICANE -> generateChicaneNarration(curve, config)
            CompoundType.SERIES -> generateSeriesNarration(curve, config)
            CompoundType.TIGHTENING_SEQUENCE -> generateTighteningSequenceNarration(curve, config)
            null -> generateSingleCurveNarration(curve, config)
        }
    }

    /**
     * Generate narration text for a straight segment.
     *
     * @param straight The straight segment.
     * @param config Narration configuration.
     * @return The narration text, or null if straights should not be narrated.
     */
    fun generateStraightNarration(straight: StraightSegment, config: NarrationConfig): String? {
        // Straights are only narrated at Detailed verbosity and when enabled
        if (config.verbosity < VERBOSITY_DETAILED || !config.narrateStraights) {
            return null
        }
        val distance = roundToNearest(straight.length.roundToInt(), 10)
        return "Straight, $distance meters"
    }

    /**
     * Generate the sparse data warning text.
     */
    fun generateSparseDataWarning(): String = SPARSE_DATA_WARNING

    /**
     * Generate the off-route warning text.
     */
    fun generateOffRouteWarning(): String = OFF_ROUTE_WARNING

    /**
     * Determine the narration priority for a curve segment.
     * Higher priority = more important = should interrupt lower priority.
     */
    fun priorityForCurve(curve: CurveSegment): Int {
        return when (curve.severity) {
            Severity.HAIRPIN -> NarrationEvent.PRIORITY_HAIRPIN
            Severity.SHARP -> NarrationEvent.PRIORITY_SHARP
            Severity.FIRM -> NarrationEvent.PRIORITY_FIRM
            Severity.MODERATE -> NarrationEvent.PRIORITY_MODERATE
            Severity.GENTLE -> NarrationEvent.PRIORITY_GENTLE
        }
    }

    // ========================================================================
    // Private: Verbosity Filtering
    // ========================================================================

    /**
     * Determine whether a curve should be narrated given the current verbosity.
     *
     * Verbosity levels:
     *   1 (Minimal)  = only SHARP and HAIRPIN
     *   2 (Standard)  = MODERATE and above
     *   3 (Detailed) = all curves including GENTLE
     */
    private fun shouldNarrate(curve: CurveSegment, config: NarrationConfig): Boolean {
        // Tightening curves are always narrated regardless of verbosity
        if (CurveModifier.TIGHTENING in curve.modifiers) {
            return true
        }

        return when (config.verbosity) {
            VERBOSITY_MINIMAL -> curve.severity == Severity.SHARP || curve.severity == Severity.HAIRPIN
            VERBOSITY_STANDARD -> curve.severity >= Severity.MODERATE
            VERBOSITY_DETAILED -> true
            else -> true
        }
    }

    // ========================================================================
    // Private: Single Curve Templates
    // ========================================================================

    private fun generateSingleCurveNarration(curve: CurveSegment, config: NarrationConfig): String {
        val parts = mutableListOf<String>()

        // Check for motorcycle tightening caution prefix
        val needsCaution = config.mode == DrivingMode.MOTORCYCLE &&
            CurveModifier.TIGHTENING in curve.modifiers

        // Special templates: hairpin, 90-degree
        val basePart = when {
            curve.severity == Severity.HAIRPIN -> buildHairpinNarration(curve, config)
            curve.is90Degree -> build90DegreeNarration(curve, config)
            else -> buildStandardCurveNarration(curve, config)
        }

        if (needsCaution) {
            parts.add("Caution")
        }
        parts.add(basePart)

        return parts.joinToString(", ")
    }

    /**
     * Build narration for a hairpin curve.
     * Format: "Hairpin [direction] ahead, slow to [speed]"
     */
    private fun buildHairpinNarration(curve: CurveSegment, config: NarrationConfig): String {
        val parts = mutableListOf<String>()
        val dir = directionText(curve.direction)
        parts.add("Hairpin $dir ahead")

        // Tightening modifier (always narrate)
        if (CurveModifier.TIGHTENING in curve.modifiers) {
            parts.add("tightening")
        }

        // Speed advisory (hairpins always have one)
        val speedText = formatSpeedAdvisory(curve.advisorySpeedMs, config)
        if (speedText != null) {
            parts.add(speedText)
        }

        // Motorcycle lean angle
        val leanText = formatLeanAngle(curve.leanAngleDeg, config)
        if (leanText != null) {
            parts.add(leanText)
        }

        return parts.joinToString(", ")
    }

    /**
     * Build narration for a 90-degree turn.
     * Format: "90 degree [direction] ahead, slow to [speed]"
     */
    private fun build90DegreeNarration(curve: CurveSegment, config: NarrationConfig): String {
        val parts = mutableListOf<String>()
        val dir = directionText(curve.direction)
        parts.add("90 degree $dir ahead")

        // Speed advisory
        val speedText = formatSpeedAdvisory(curve.advisorySpeedMs, config)
        if (speedText != null) {
            parts.add(speedText)
        }

        // Motorcycle lean angle
        val leanText = formatLeanAngle(curve.leanAngleDeg, config)
        if (leanText != null) {
            parts.add(leanText)
        }

        return parts.joinToString(", ")
    }

    /**
     * Build narration for a standard curve (not hairpin, not 90-degree).
     *
     * PRD Section 6.1 format:
     * - For SHARP curves: "Sharp [direction] ahead[, modifiers][, slow to X]"
     * - For GENTLE/MODERATE/FIRM: "[Direction] curve ahead, [severity][, modifiers]"
     * - With LONG modifier: "Long [severity] [direction][, modifiers]"
     *
     * Only the first word of the sentence is capitalized.
     */
    private fun buildStandardCurveNarration(curve: CurveSegment, config: NarrationConfig): String {
        val parts = mutableListOf<String>()

        val severityStr = severityText(curve.severity)
        val dir = directionText(curve.direction)

        // Build the base phrase based on severity and modifiers
        if (CurveModifier.LONG in curve.modifiers && config.verbosity >= VERBOSITY_STANDARD) {
            // "Long gentle left, holds for 400 meters" (PRD example)
            parts.add("Long $severityStr $dir")
        } else if (curve.severity == Severity.SHARP) {
            // "Sharp right ahead, tightening, slow to 40" (PRD example)
            parts.add("Sharp $dir ahead")
        } else {
            // "Left curve ahead, moderate" (PRD example)
            val dirCapitalized = dir.replaceFirstChar { it.uppercase() }
            parts.add("$dirCapitalized curve ahead")
            parts.add(severityStr)
        }

        // Tightening — ALWAYS narrate
        if (CurveModifier.TIGHTENING in curve.modifiers) {
            parts.add("tightening")
        }

        // Opening — Standard+ verbosity only
        if (CurveModifier.OPENING in curve.modifiers && config.verbosity >= VERBOSITY_STANDARD) {
            parts.add("opening")
        }

        // Holds — Standard+ verbosity: "holds for X meters"
        if (CurveModifier.HOLDS in curve.modifiers && config.verbosity >= VERBOSITY_STANDARD) {
            val holdDistance = roundToNearest(curve.arcLength.roundToInt(), 10)
            parts.add("holds for $holdDistance meters")
        }

        // Speed advisory
        val speedText = formatSpeedAdvisory(curve.advisorySpeedMs, config)
        if (speedText != null) {
            parts.add(speedText)
        }

        // Motorcycle lean angle
        val leanText = formatLeanAngle(curve.leanAngleDeg, config)
        if (leanText != null) {
            parts.add(leanText)
        }

        return parts.joinToString(", ")
    }

    // ========================================================================
    // Private: Compound Curve Templates
    // ========================================================================

    /**
     * S-bend: "[Dir1] into [dir2], S-bend, [max severity]"
     * PRD example: "Left into right, S-bend, moderate"
     *
     * The CurveSegment for an S-bend uses `direction` for the first curve's direction.
     * The opposite direction is inferred for the second curve since S-bends are
     * opposite-direction pairs.
     * First word is capitalized.
     */
    private fun generateSBendNarration(curve: CurveSegment, config: NarrationConfig): String {
        val parts = mutableListOf<String>()

        val needsCaution = config.mode == DrivingMode.MOTORCYCLE &&
            CurveModifier.TIGHTENING in curve.modifiers

        if (needsCaution) {
            parts.add("Caution")
        }

        val dir1 = directionText(curve.direction)
        val dir2 = directionText(oppositeDirection(curve.direction))
        // Capitalize first word only if no "Caution" prefix
        val dirPhrase = if (parts.isEmpty()) {
            "${dir1.replaceFirstChar { it.uppercase() }} into $dir2"
        } else {
            "$dir1 into $dir2"
        }
        parts.add(dirPhrase)
        parts.add("S-bend")
        parts.add(severityText(curve.severity))

        // Speed advisory
        val speedText = formatSpeedAdvisory(curve.advisorySpeedMs, config)
        if (speedText != null) {
            parts.add(speedText)
        }

        // Motorcycle lean angle
        val leanText = formatLeanAngle(curve.leanAngleDeg, config)
        if (leanText != null) {
            parts.add(leanText)
        }

        return parts.joinToString(", ")
    }

    /**
     * Chicane: "Chicane, [dir1]-[dir2], slow to [speed]"
     */
    private fun generateChicaneNarration(curve: CurveSegment, config: NarrationConfig): String {
        val parts = mutableListOf<String>()

        val needsCaution = config.mode == DrivingMode.MOTORCYCLE &&
            CurveModifier.TIGHTENING in curve.modifiers

        if (needsCaution) {
            parts.add("Caution")
        }

        val dir1 = directionText(curve.direction)
        val dir2 = directionText(oppositeDirection(curve.direction))
        parts.add("Chicane")
        parts.add("$dir1-$dir2")

        // Speed advisory (chicanes always have one since they are sharp+)
        val speedText = formatSpeedAdvisory(curve.advisorySpeedMs, config)
        if (speedText != null) {
            parts.add(speedText)
        }

        // Motorcycle lean angle
        val leanText = formatLeanAngle(curve.leanAngleDeg, config)
        if (leanText != null) {
            parts.add(leanText)
        }

        return parts.joinToString(", ")
    }

    /**
     * Series: "Series of [N] curves, [max severity]"
     * Severity is lowercase as a descriptor.
     */
    private fun generateSeriesNarration(curve: CurveSegment, config: NarrationConfig): String {
        val parts = mutableListOf<String>()

        val needsCaution = config.mode == DrivingMode.MOTORCYCLE &&
            CurveModifier.TIGHTENING in curve.modifiers

        if (needsCaution) {
            parts.add("Caution")
        }

        val count = curve.compoundSize ?: 3
        parts.add("Series of $count curves")
        parts.add(severityText(curve.severity))

        // Speed advisory
        val speedText = formatSpeedAdvisory(curve.advisorySpeedMs, config)
        if (speedText != null) {
            parts.add(speedText)
        }

        return parts.joinToString(", ")
    }

    /**
     * Tightening sequence: "[direction], tightening through [N] curves"
     */
    private fun generateTighteningSequenceNarration(
        curve: CurveSegment,
        config: NarrationConfig
    ): String {
        val parts = mutableListOf<String>()

        if (config.mode == DrivingMode.MOTORCYCLE) {
            parts.add("Caution")
        }

        val dir = directionText(curve.direction)
        val count = curve.compoundSize ?: 2
        parts.add("$dir, tightening through $count curves")

        // Speed advisory
        val speedText = formatSpeedAdvisory(curve.advisorySpeedMs, config)
        if (speedText != null) {
            parts.add(speedText)
        }

        // Motorcycle lean angle
        val leanText = formatLeanAngle(curve.leanAngleDeg, config)
        if (leanText != null) {
            parts.add(leanText)
        }

        return parts.joinToString(", ")
    }

    // ========================================================================
    // Private: Formatting Helpers
    // ========================================================================

    /**
     * Format speed advisory text: "slow to [speed]" in user's preferred units,
     * rounded to nearest 5.
     *
     * @return Formatted text or null if no advisory speed is set.
     */
    private fun formatSpeedAdvisory(advisorySpeedMs: Double?, config: NarrationConfig): String? {
        if (advisorySpeedMs == null) return null

        val speedInUnit = when (config.units) {
            SpeedUnit.KMH -> advisorySpeedMs * MS_TO_KMH
            SpeedUnit.MPH -> advisorySpeedMs * MS_TO_MPH
        }

        val rounded = roundToNearest5(speedInUnit)
        return "slow to $rounded"
    }

    /**
     * Format lean angle text: "lean [X] degrees" for motorcycle mode.
     *
     * @return Formatted text or null if not applicable.
     */
    private fun formatLeanAngle(leanAngleDeg: Double?, config: NarrationConfig): String? {
        if (config.mode != DrivingMode.MOTORCYCLE) return null
        if (!config.narrateLeanAngle) return null
        if (leanAngleDeg == null) return null

        return if (leanAngleDeg > EXTREME_LEAN_THRESHOLD) {
            "extreme lean"
        } else {
            val rounded = roundToNearest5(leanAngleDeg)
            "lean $rounded degrees"
        }
    }

    /**
     * Convert a [Direction] to its lowercase narration text.
     */
    private fun directionText(direction: Direction): String {
        return when (direction) {
            Direction.LEFT -> "left"
            Direction.RIGHT -> "right"
        }
    }

    /**
     * Convert a [Severity] to its lowercase narration text.
     * All severities are lowercase for use as descriptors in mid-sentence.
     * Capitalization of the first word in the sentence is handled by the template builders.
     */
    private fun severityText(severity: Severity): String {
        return when (severity) {
            Severity.GENTLE -> "gentle"
            Severity.MODERATE -> "moderate"
            Severity.FIRM -> "firm"
            Severity.SHARP -> "sharp"
            Severity.HAIRPIN -> "hairpin"
        }
    }

    /**
     * Get the opposite direction.
     */
    private fun oppositeDirection(direction: Direction): Direction {
        return when (direction) {
            Direction.LEFT -> Direction.RIGHT
            Direction.RIGHT -> Direction.LEFT
        }
    }

    /**
     * Round a double value DOWN to the nearest 5.
     * Uses floor (not round) per PRD Section 7.6: "Round down to nearest 5."
     * This is a safety measure — speed advisories must be conservative.
     */
    private fun roundToNearest5(value: Double): Int {
        return (Math.floor(value / 5.0) * 5).toInt()
    }

    /**
     * Round an integer to the nearest [step].
     */
    private fun roundToNearest(value: Int, step: Int): Int {
        return ((value + step / 2) / step) * step
    }

    companion object {
        /** Verbosity level constants. */
        const val VERBOSITY_MINIMAL = 1
        const val VERBOSITY_STANDARD = 2
        const val VERBOSITY_DETAILED = 3

        /** Conversion factors. */
        const val MS_TO_KMH = 3.6
        const val MS_TO_MPH = 2.23694

        /** Lean angle cap: above this, narrate "extreme lean" instead of a number. */
        const val EXTREME_LEAN_THRESHOLD = 45.0

        /** Confidence below this triggers sparse data warning instead of curve narration. */
        const val LOW_CONFIDENCE_THRESHOLD = 0.3f

        /** Standard sparse data warning text. Uses comma for TTS clarity. */
        const val SPARSE_DATA_WARNING =
            "Low data quality ahead, curve information may be incomplete"

        /** Off-route warning text. */
        const val OFF_ROUTE_WARNING = "Off route \u2014 curve narration paused"
    }
}

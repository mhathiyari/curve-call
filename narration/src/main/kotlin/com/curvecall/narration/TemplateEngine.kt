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
import com.curvecall.narration.types.VerbosityTier
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
 * - Tightening: prepend "Caution, " (universal for all modes)
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
     * Resolve the effective VerbosityTier given config and optional current speed.
     * Maps verbosity 1->TERSE, 2->STANDARD, 3->DESCRIPTIVE.
     * Speed-adaptive auto-downgrade (non-configurable safety rule):
     *   >80 km/h (22.22 m/s) -> TERSE
     *   >50 km/h (13.89 m/s) -> STANDARD
     *   <=50 km/h -> DESCRIPTIVE
     * Effective tier = min(userTier, speedTier)
     */
    fun resolveTier(config: NarrationConfig, currentSpeedMs: Double? = null): VerbosityTier {
        val userTier = when (config.verbosity) {
            VERBOSITY_MINIMAL -> VerbosityTier.TERSE
            VERBOSITY_STANDARD -> VerbosityTier.STANDARD
            VERBOSITY_DETAILED -> VerbosityTier.DESCRIPTIVE
            else -> VerbosityTier.STANDARD
        }
        if (currentSpeedMs == null) return userTier

        val speedTier = when {
            currentSpeedMs > 22.22 -> VerbosityTier.TERSE      // >80 km/h
            currentSpeedMs > 13.89 -> VerbosityTier.STANDARD    // >50 km/h
            else -> VerbosityTier.DESCRIPTIVE                    // <=50 km/h
        }
        // min = the more terse of the two
        return if (speedTier.ordinal < userTier.ordinal) speedTier else userTier
    }

    /**
     * Generate narration text for a curve segment.
     *
     * @param curve The analyzed curve segment.
     * @param config Narration configuration (mode, verbosity, units).
     * @param currentSpeedMs Optional current speed in m/s for speed-adaptive tier downgrade.
     * @return The narration text string, or null if this curve should not be narrated
     *   at the current verbosity level.
     */
    fun generateNarration(curve: CurveSegment, config: NarrationConfig, currentSpeedMs: Double? = null): String? {
        // Verbosity filtering: check if this curve should be narrated
        if (!shouldNarrate(curve, config)) {
            return null
        }

        // Low confidence warning
        if (curve.confidence < LOW_CONFIDENCE_THRESHOLD) {
            return SPARSE_DATA_WARNING
        }

        val tier = resolveTier(config, currentSpeedMs)

        // Dispatch to compound or single-curve template
        return when (curve.compoundType) {
            CompoundType.S_BEND -> generateSBendNarration(curve, config, tier)
            CompoundType.CHICANE -> generateChicaneNarration(curve, config, tier)
            CompoundType.SERIES -> generateSeriesNarration(curve, config, tier)
            CompoundType.TIGHTENING_SEQUENCE -> generateTighteningSequenceNarration(curve, config, tier)
            CompoundType.SWITCHBACKS -> generateSwitchbackNarration(curve, config, tier)
            null -> generateSingleCurveNarration(curve, config, tier)
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

    private fun generateSingleCurveNarration(
        curve: CurveSegment,
        config: NarrationConfig,
        tier: VerbosityTier
    ): String {
        val parts = mutableListOf<String>()

        // Universal tightening caution prefix
        val needsCaution = CurveModifier.TIGHTENING in curve.modifiers

        // Special templates: hairpin, 90-degree
        val basePart = when {
            curve.severity == Severity.HAIRPIN -> buildHairpinNarration(curve, config, tier)
            curve.is90Degree -> build90DegreeNarration(curve, config, tier)
            else -> buildStandardCurveNarration(curve, config, tier)
        }

        if (needsCaution) {
            parts.add("Caution")
        }
        parts.add(basePart)

        return parts.joinToString(", ")
    }

    /**
     * Build narration for a hairpin curve.
     * STANDARD: "Hairpin [direction] ahead, slow to [speed]"
     * TERSE: "Hairpin [dir], [speed]"
     * DESCRIPTIVE: "Hairpin [dir] ahead, very tight, slow to [speed]"
     */
    private fun buildHairpinNarration(
        curve: CurveSegment,
        config: NarrationConfig,
        tier: VerbosityTier
    ): String {
        return when (tier) {
            VerbosityTier.TERSE -> {
                val dir = directionText(curve.direction)
                val speed = extractSpeedNumber(curve.advisorySpeedMs, config)
                if (speed != null) "Hairpin $dir, $speed" else "Hairpin $dir"
            }
            VerbosityTier.STANDARD -> {
                // Existing output — unchanged
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

                parts.joinToString(", ")
            }
            VerbosityTier.DESCRIPTIVE -> {
                val parts = mutableListOf<String>()
                val dir = directionText(curve.direction)
                parts.add("Hairpin $dir ahead")
                parts.add("very tight")

                // Tightening modifier (always narrate)
                if (CurveModifier.TIGHTENING in curve.modifiers) {
                    parts.add("tightening")
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

                parts.joinToString(", ")
            }
        }
    }

    /**
     * Build narration for a 90-degree turn.
     * STANDARD: "90 degree [direction] ahead, slow to [speed]"
     * TERSE: "90 [dir], [speed]"
     * DESCRIPTIVE: "90 degree [dir] ahead, slow to [speed]" (same as STANDARD)
     */
    private fun build90DegreeNarration(
        curve: CurveSegment,
        config: NarrationConfig,
        tier: VerbosityTier
    ): String {
        return when (tier) {
            VerbosityTier.TERSE -> {
                val dir = directionText(curve.direction)
                val speed = extractSpeedNumber(curve.advisorySpeedMs, config)
                if (speed != null) "90 $dir, $speed" else "90 $dir"
            }
            VerbosityTier.STANDARD, VerbosityTier.DESCRIPTIVE -> {
                // Same output for both STANDARD and DESCRIPTIVE
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

                parts.joinToString(", ")
            }
        }
    }

    /**
     * Build narration for a standard curve (not hairpin, not 90-degree).
     *
     * STANDARD (unchanged):
     * - For SHARP curves: "Sharp [direction] ahead[, modifiers][, slow to X]"
     * - For GENTLE/MODERATE/FIRM: "[Direction] curve ahead, [severity][, modifiers]"
     * - With LONG modifier: "Long [severity] [direction][, modifiers]"
     *
     * TERSE:
     * - SHARP: "Sharp [dir]" (omit "ahead"), append ", [speed]" if advisory
     * - Others: "[Severity] [dir]", append ", [speed]" if advisory
     * - Skip modifiers (tightening/opening/holds) and lean angle
     * - Drop LONG modifier
     *
     * DESCRIPTIVE:
     * - SHARP: same as STANDARD
     * - Others: "[Dir] curve ahead, [severity], slow to [speed]" or "steady speed"
     * - Include all modifiers and lean angle
     */
    private fun buildStandardCurveNarration(
        curve: CurveSegment,
        config: NarrationConfig,
        tier: VerbosityTier
    ): String {
        return when (tier) {
            VerbosityTier.TERSE -> {
                val dir = directionText(curve.direction)
                val speed = extractSpeedNumber(curve.advisorySpeedMs, config)

                val base = if (curve.severity == Severity.SHARP) {
                    "Sharp $dir"
                } else {
                    val sevCapitalized = severityText(curve.severity)
                        .replaceFirstChar { it.uppercase() }
                    "$sevCapitalized $dir"
                }

                if (speed != null) "$base, $speed" else base
            }
            VerbosityTier.STANDARD -> {
                // Existing output — unchanged
                val parts = mutableListOf<String>()

                val severityStr = severityText(curve.severity)
                val dir = directionText(curve.direction)

                // Build the base phrase based on severity and modifiers
                if (CurveModifier.LONG in curve.modifiers && config.verbosity >= VERBOSITY_STANDARD) {
                    parts.add("Long $severityStr $dir")
                } else if (curve.severity == Severity.SHARP) {
                    parts.add("Sharp $dir ahead")
                } else {
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

                parts.joinToString(", ")
            }
            VerbosityTier.DESCRIPTIVE -> {
                val parts = mutableListOf<String>()

                val severityStr = severityText(curve.severity)
                val dir = directionText(curve.direction)

                if (curve.severity == Severity.SHARP) {
                    // SHARP at DESCRIPTIVE: same as STANDARD
                    if (CurveModifier.LONG in curve.modifiers) {
                        parts.add("Long $severityStr $dir")
                    } else {
                        parts.add("Sharp $dir ahead")
                    }
                } else {
                    // "[Dir] curve ahead, [severity]" with extended info
                    if (CurveModifier.LONG in curve.modifiers) {
                        parts.add("Long $severityStr $dir")
                    } else {
                        val dirCapitalized = dir.replaceFirstChar { it.uppercase() }
                        parts.add("$dirCapitalized curve ahead")
                        parts.add(severityStr)
                    }
                }

                // Tightening — ALWAYS narrate
                if (CurveModifier.TIGHTENING in curve.modifiers) {
                    parts.add("tightening")
                }

                // Opening
                if (CurveModifier.OPENING in curve.modifiers) {
                    parts.add("opening")
                }

                // Holds: "holds for X meters"
                if (CurveModifier.HOLDS in curve.modifiers) {
                    val holdDistance = roundToNearest(curve.arcLength.roundToInt(), 10)
                    parts.add("holds for $holdDistance meters")
                }

                // Speed advisory or "steady speed" for non-sharp
                val speedText = formatSpeedAdvisory(curve.advisorySpeedMs, config)
                if (speedText != null) {
                    parts.add(speedText)
                } else if (curve.severity != Severity.SHARP) {
                    parts.add("steady speed")
                }

                // Motorcycle lean angle
                val leanText = formatLeanAngle(curve.leanAngleDeg, config)
                if (leanText != null) {
                    parts.add(leanText)
                }

                parts.joinToString(", ")
            }
        }
    }

    // ========================================================================
    // Private: Compound Curve Templates
    // ========================================================================

    /**
     * S-bend narration.
     * STANDARD: "[Dir1] into [dir2], S-bend, [max severity]"
     * TERSE: "S-bend, [dir1]-[dir2]"
     * DESCRIPTIVE: "S-bend ahead, [dir1] into [dir2], [severity][, slow to [speed]]"
     */
    private fun generateSBendNarration(
        curve: CurveSegment,
        config: NarrationConfig,
        tier: VerbosityTier
    ): String {
        val parts = mutableListOf<String>()

        val needsCaution = CurveModifier.TIGHTENING in curve.modifiers

        if (needsCaution) {
            parts.add("Caution")
        }

        val dir1 = directionText(curve.direction)
        val dir2 = directionText(oppositeDirection(curve.direction))

        when (tier) {
            VerbosityTier.TERSE -> {
                parts.add("S-bend")
                parts.add("$dir1-$dir2")
            }
            VerbosityTier.STANDARD -> {
                // Existing output — unchanged
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
            }
            VerbosityTier.DESCRIPTIVE -> {
                parts.add("S-bend ahead")
                parts.add("$dir1 into $dir2")
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
            }
        }

        return parts.joinToString(", ")
    }

    /**
     * Chicane narration.
     * STANDARD: "Chicane, [dir1]-[dir2], slow to [speed]"
     * TERSE: "Chicane, [dir1]-[dir2]"
     * DESCRIPTIVE: "Chicane ahead, [dir1]-[dir2], slow to [speed]"
     */
    private fun generateChicaneNarration(
        curve: CurveSegment,
        config: NarrationConfig,
        tier: VerbosityTier
    ): String {
        val parts = mutableListOf<String>()

        val needsCaution = CurveModifier.TIGHTENING in curve.modifiers

        if (needsCaution) {
            parts.add("Caution")
        }

        val dir1 = directionText(curve.direction)
        val dir2 = directionText(oppositeDirection(curve.direction))

        when (tier) {
            VerbosityTier.TERSE -> {
                parts.add("Chicane")
                parts.add("$dir1-$dir2")
            }
            VerbosityTier.STANDARD -> {
                // Existing output — unchanged
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
            }
            VerbosityTier.DESCRIPTIVE -> {
                parts.add("Chicane ahead")
                parts.add("$dir1-$dir2")

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
            }
        }

        return parts.joinToString(", ")
    }

    /**
     * Series narration.
     * STANDARD: "Series of [N] curves, [max severity]"
     * TERSE: "[N] curves, [severity]"
     * DESCRIPTIVE: "Series of [N] [severity] curves ahead[, slow to [speed]]"
     */
    private fun generateSeriesNarration(
        curve: CurveSegment,
        config: NarrationConfig,
        tier: VerbosityTier
    ): String {
        val parts = mutableListOf<String>()

        val needsCaution = CurveModifier.TIGHTENING in curve.modifiers

        if (needsCaution) {
            parts.add("Caution")
        }

        val count = curve.compoundSize ?: 3

        when (tier) {
            VerbosityTier.TERSE -> {
                parts.add("$count curves")
                parts.add(severityText(curve.severity))
            }
            VerbosityTier.STANDARD -> {
                // Existing output — unchanged
                parts.add("Series of $count curves")
                parts.add(severityText(curve.severity))

                // Speed advisory
                val speedText = formatSpeedAdvisory(curve.advisorySpeedMs, config)
                if (speedText != null) {
                    parts.add(speedText)
                }
            }
            VerbosityTier.DESCRIPTIVE -> {
                parts.add("Series of $count ${severityText(curve.severity)} curves ahead")

                // Speed advisory
                val speedText = formatSpeedAdvisory(curve.advisorySpeedMs, config)
                if (speedText != null) {
                    parts.add(speedText)
                }
            }
        }

        return parts.joinToString(", ")
    }

    /**
     * Tightening sequence narration.
     * STANDARD: "[direction], tightening through [N] curves[, slow to [speed]]"
     * TERSE: "Tightening, [N] curves"
     * DESCRIPTIVE: "[Dir], tightening through [N] curves, slow to [speed]" (same as STANDARD)
     */
    private fun generateTighteningSequenceNarration(
        curve: CurveSegment,
        config: NarrationConfig,
        tier: VerbosityTier
    ): String {
        val parts = mutableListOf<String>()

        // Tightening sequences always get Caution
        parts.add("Caution")

        val dir = directionText(curve.direction)
        val count = curve.compoundSize ?: 2

        when (tier) {
            VerbosityTier.TERSE -> {
                parts.add("Tightening")
                parts.add("$count curves")
            }
            VerbosityTier.STANDARD, VerbosityTier.DESCRIPTIVE -> {
                // Same output for both STANDARD and DESCRIPTIVE
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
            }
        }

        return parts.joinToString(", ")
    }

    /**
     * Switchback narration.
     * First curve (overview): "Series of [N] switchbacks, slow to [speed]"
     * Subsequent (countdown): "Hairpin left, 2 of 4, slow to [speed]"
     */
    private fun generateSwitchbackNarration(
        curve: CurveSegment,
        config: NarrationConfig,
        tier: VerbosityTier
    ): String {
        val parts = mutableListOf<String>()

        // Caution for all switchbacks (they are sharp/hairpin by definition)
        parts.add("Caution")

        val dir = directionText(curve.direction)
        val count = curve.compoundSize ?: 3
        val position = curve.positionInCompound

        when {
            // First curve in switchback → overview
            position == null || position == 1 -> {
                when (tier) {
                    VerbosityTier.TERSE -> {
                        parts.add("$count switchbacks")
                        val speed = extractSpeedNumber(curve.advisorySpeedMs, config)
                        if (speed != null) parts.add(speed)
                    }
                    VerbosityTier.STANDARD -> {
                        parts.add("Series of $count switchbacks")
                        val speedText = formatSpeedAdvisory(curve.advisorySpeedMs, config)
                        if (speedText != null) parts.add(speedText)
                    }
                    VerbosityTier.DESCRIPTIVE -> {
                        parts.add("Series of $count switchbacks ahead")
                        val speedText = formatSpeedAdvisory(curve.advisorySpeedMs, config)
                        if (speedText != null) parts.add(speedText)
                    }
                }
            }
            // Subsequent curves → countdown
            else -> {
                val sevText = if (curve.severity == Severity.HAIRPIN) "Hairpin" else "Sharp"
                when (tier) {
                    VerbosityTier.TERSE -> {
                        parts.add("$sevText $dir, $position of $count")
                    }
                    VerbosityTier.STANDARD, VerbosityTier.DESCRIPTIVE -> {
                        parts.add("$sevText $dir, $position of $count")
                        val speedText = formatSpeedAdvisory(curve.advisorySpeedMs, config)
                        if (speedText != null) parts.add(speedText)
                    }
                }
            }
        }

        return parts.joinToString(", ")
    }

    // ========================================================================
    // Public: Winding and Transition Templates
    // ========================================================================

    /**
     * Generate winding road overview narration.
     * TERSE: "Winding, [N] curves"
     * STANDARD: "Winding, [N] [severity] curves, slow to [speed]"
     * DESCRIPTIVE: "Winding road ahead, [N] [severity] curves, slow to [speed]"
     */
    fun generateWindingOverview(
        section: WindingDetector.WindingSection,
        config: NarrationConfig,
        tier: VerbosityTier
    ): String {
        val sevText = severityText(section.maxSeverity)
        return when (tier) {
            VerbosityTier.TERSE -> {
                val speed = if (section.advisorySpeedMs != null) {
                    extractSpeedNumber(section.advisorySpeedMs, config)
                } else null
                if (speed != null) "Winding, ${section.curveCount} curves, $speed"
                else "Winding, ${section.curveCount} curves"
            }
            VerbosityTier.STANDARD -> {
                val parts = mutableListOf("Winding", "${section.curveCount} $sevText curves")
                val speedText = formatSpeedAdvisory(section.advisorySpeedMs, config)
                if (speedText != null) parts.add(speedText)
                parts.joinToString(", ")
            }
            VerbosityTier.DESCRIPTIVE -> {
                val parts = mutableListOf("Winding road ahead", "${section.curveCount} $sevText curves")
                val speedText = formatSpeedAdvisory(section.advisorySpeedMs, config)
                if (speedText != null) parts.add(speedText)
                parts.joinToString(", ")
            }
        }
    }

    /**
     * Generate transition narration.
     * SEVERITY_INCREASE: "Curves tighten ahead"
     * SEVERITY_DECREASE: "Curves ease ahead"
     * STRAIGHT_TO_WINDING: "Winding road ahead"
     * WINDING_TO_STRAIGHT: "Clear, straight ahead"
     */
    fun generateTransitionNarration(
        transition: TransitionDetector.Transition,
        tier: VerbosityTier
    ): String {
        return when (transition.type) {
            TransitionDetector.TransitionType.SEVERITY_INCREASE -> when (tier) {
                VerbosityTier.TERSE -> "Curves tighten"
                VerbosityTier.STANDARD -> "Curves tighten ahead"
                VerbosityTier.DESCRIPTIVE -> "Curves tighten ahead"
            }
            TransitionDetector.TransitionType.SEVERITY_DECREASE -> when (tier) {
                VerbosityTier.TERSE -> "Curves ease"
                VerbosityTier.STANDARD -> "Curves ease ahead"
                VerbosityTier.DESCRIPTIVE -> "Curves ease ahead"
            }
            TransitionDetector.TransitionType.STRAIGHT_TO_WINDING -> when (tier) {
                VerbosityTier.TERSE -> "Winding ahead"
                VerbosityTier.STANDARD -> "Winding road ahead"
                VerbosityTier.DESCRIPTIVE -> "Winding road ahead"
            }
            TransitionDetector.TransitionType.WINDING_TO_STRAIGHT -> when (tier) {
                VerbosityTier.TERSE -> "Straight ahead"
                VerbosityTier.STANDARD -> "Clear, straight ahead"
                VerbosityTier.DESCRIPTIVE -> "Clear, straight road ahead"
            }
        }
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
     * Extract just the speed number (as a string) for TERSE mode.
     *
     * @return Speed number string or null if no advisory speed is set.
     */
    private fun extractSpeedNumber(advisorySpeedMs: Double?, config: NarrationConfig): String? {
        if (advisorySpeedMs == null) return null
        val speedInUnit = when (config.units) {
            SpeedUnit.KMH -> advisorySpeedMs * MS_TO_KMH
            SpeedUnit.MPH -> advisorySpeedMs * MS_TO_MPH
        }
        return roundToNearest5(speedInUnit).toString()
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

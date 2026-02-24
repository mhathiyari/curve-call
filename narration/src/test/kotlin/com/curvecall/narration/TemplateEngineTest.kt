package com.curvecall.narration

import com.curvecall.engine.types.CompoundType
import com.curvecall.engine.types.CurveModifier
import com.curvecall.engine.types.CurveSegment
import com.curvecall.engine.types.Direction
import com.curvecall.engine.types.LatLon
import com.curvecall.engine.types.Severity
import com.curvecall.engine.types.StraightSegment
import com.curvecall.narration.types.DrivingMode
import com.curvecall.narration.types.NarrationConfig
import com.curvecall.narration.types.NarrationEvent
import com.curvecall.narration.types.SpeedUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Exhaustive tests for the TemplateEngine narration text generator.
 *
 * Tests cover every combination of severity, direction, modifier, compound type,
 * driving mode, verbosity level, and speed unit.
 */
class TemplateEngineTest {

    private lateinit var engine: TemplateEngine

    @BeforeEach
    fun setUp() {
        engine = TemplateEngine()
    }

    // ========================================================================
    // Helper functions to create test CurveSegments
    // ========================================================================

    private fun curve(
        direction: Direction = Direction.RIGHT,
        severity: Severity = Severity.MODERATE,
        minRadius: Double = 150.0,
        arcLength: Double = 100.0,
        modifiers: Set<CurveModifier> = emptySet(),
        totalAngleChange: Double = 45.0,
        is90Degree: Boolean = false,
        advisorySpeedMs: Double? = null,
        leanAngleDeg: Double? = null,
        compoundType: CompoundType? = null,
        compoundSize: Int? = null,
        confidence: Float = 1.0f,
        distanceFromStart: Double = 500.0
    ) = CurveSegment(
        direction = direction,
        severity = severity,
        minRadius = minRadius,
        arcLength = arcLength,
        modifiers = modifiers,
        totalAngleChange = totalAngleChange,
        is90Degree = is90Degree,
        advisorySpeedMs = advisorySpeedMs,
        leanAngleDeg = leanAngleDeg,
        compoundType = compoundType,
        compoundSize = compoundSize,
        confidence = confidence,
        startIndex = 0,
        endIndex = 10,
        startPoint = LatLon(46.0, 10.0),
        endPoint = LatLon(46.001, 10.001),
        distanceFromStart = distanceFromStart
    )

    private val carStandard = NarrationConfig(
        mode = DrivingMode.CAR,
        verbosity = 2,
        units = SpeedUnit.KMH
    )

    private val carMinimal = NarrationConfig(
        mode = DrivingMode.CAR,
        verbosity = 1,
        units = SpeedUnit.KMH
    )

    private val carDetailed = NarrationConfig(
        mode = DrivingMode.CAR,
        verbosity = 3,
        units = SpeedUnit.KMH
    )

    private val motoStandard = NarrationConfig(
        mode = DrivingMode.MOTORCYCLE,
        verbosity = 2,
        units = SpeedUnit.KMH,
        narrateLeanAngle = true
    )

    private val motoMinimal = NarrationConfig(
        mode = DrivingMode.MOTORCYCLE,
        verbosity = 1,
        units = SpeedUnit.KMH,
        narrateLeanAngle = true
    )

    private val carMph = NarrationConfig(
        mode = DrivingMode.CAR,
        verbosity = 2,
        units = SpeedUnit.MPH
    )

    // ========================================================================
    // Basic single-curve narration
    // ========================================================================

    @Nested
    @DisplayName("Basic Severity + Direction")
    inner class BasicNarration {

        @Test
        fun `gentle right curve ahead`() {
            val text = engine.generateNarration(
                curve(severity = Severity.GENTLE, direction = Direction.RIGHT),
                carDetailed // Gentle only narrated at Detailed -> DESCRIPTIVE tier
            )
            assertThat(text).isEqualTo("Right curve ahead, gentle, steady speed")
        }

        @Test
        fun `moderate left curve ahead`() {
            val text = engine.generateNarration(
                curve(severity = Severity.MODERATE, direction = Direction.LEFT),
                carStandard
            )
            assertThat(text).isEqualTo("Left curve ahead, moderate")
        }

        @Test
        fun `firm right curve ahead`() {
            val text = engine.generateNarration(
                curve(severity = Severity.FIRM, direction = Direction.RIGHT),
                carStandard
            )
            assertThat(text).isEqualTo("Right curve ahead, firm")
        }

        @Test
        fun `sharp left ahead`() {
            val text = engine.generateNarration(
                curve(severity = Severity.SHARP, direction = Direction.LEFT),
                carStandard
            )
            assertThat(text).isEqualTo("Sharp left ahead")
        }

        @ParameterizedTest
        @EnumSource(Direction::class)
        fun `all directions produce valid narration`(direction: Direction) {
            val text = engine.generateNarration(
                curve(severity = Severity.MODERATE, direction = direction),
                carStandard
            )
            assertThat(text).containsIgnoringCase(direction.name.lowercase())
        }

        @ParameterizedTest
        @EnumSource(Severity::class)
        fun `all severities produce valid narration at detailed verbosity`(severity: Severity) {
            val text = engine.generateNarration(
                curve(severity = severity, direction = Direction.RIGHT),
                carDetailed
            )
            assertThat(text).isNotNull()
        }
    }

    // ========================================================================
    // Hairpin narration
    // ========================================================================

    @Nested
    @DisplayName("Hairpin Curves")
    inner class HairpinNarration {

        @Test
        fun `hairpin left ahead with speed advisory`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.HAIRPIN,
                    direction = Direction.LEFT,
                    advisorySpeedMs = 5.56 // ~20 km/h
                ),
                carStandard
            )
            assertThat(text).isEqualTo("Hairpin left ahead, slow to 20")
        }

        @Test
        fun `hairpin right ahead with speed advisory`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.HAIRPIN,
                    direction = Direction.RIGHT,
                    advisorySpeedMs = 7.0 // 25.2 km/h -> floor to 25
                ),
                carStandard
            )
            assertThat(text).isEqualTo("Hairpin right ahead, slow to 25")
        }

        @Test
        fun `hairpin with tightening modifier`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.HAIRPIN,
                    direction = Direction.LEFT,
                    modifiers = setOf(CurveModifier.TIGHTENING),
                    advisorySpeedMs = 5.56
                ),
                carStandard
            )
            assertThat(text).isEqualTo("Caution, Hairpin left ahead, tightening, slow to 20")
        }

        @Test
        fun `hairpin narrated even at minimal verbosity`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.HAIRPIN,
                    direction = Direction.RIGHT,
                    advisorySpeedMs = 5.56
                ),
                carMinimal
            )
            assertThat(text).isNotNull()
            assertThat(text).contains("Hairpin")
        }
    }

    // ========================================================================
    // 90-degree turns
    // ========================================================================

    @Nested
    @DisplayName("90-degree Turns")
    inner class NinetyDegreeTurns {

        @Test
        fun `90 degree right ahead with speed advisory`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.SHARP,
                    direction = Direction.RIGHT,
                    is90Degree = true,
                    advisorySpeedMs = 7.0 // 25.2 km/h -> floor to 25
                ),
                carStandard
            )
            assertThat(text).isEqualTo("90 degree right ahead, slow to 25")
        }

        @Test
        fun `90 degree left ahead`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.SHARP,
                    direction = Direction.LEFT,
                    is90Degree = true,
                    advisorySpeedMs = 7.0 // 25.2 km/h -> floor to 25
                ),
                carStandard
            )
            assertThat(text).isEqualTo("90 degree left ahead, slow to 25")
        }
    }

    // ========================================================================
    // Modifiers: tightening, opening, holds, long
    // ========================================================================

    @Nested
    @DisplayName("Curve Modifiers")
    inner class Modifiers {

        @Test
        fun `tightening modifier always narrated`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.MODERATE,
                    direction = Direction.RIGHT,
                    modifiers = setOf(CurveModifier.TIGHTENING)
                ),
                carStandard
            )
            assertThat(text).isEqualTo("Caution, Right curve ahead, moderate, tightening")
        }

        @Test
        fun `tightening narrated at minimal verbosity for moderate curve`() {
            // Tightening curves are always narrated regardless of verbosity
            // At TERSE tier, modifiers text is skipped but Caution prefix conveys danger
            val text = engine.generateNarration(
                curve(
                    severity = Severity.MODERATE,
                    direction = Direction.LEFT,
                    modifiers = setOf(CurveModifier.TIGHTENING)
                ),
                carMinimal
            )
            assertThat(text).isNotNull()
            assertThat(text).startsWith("Caution")
        }

        @Test
        fun `opening modifier at standard verbosity`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.MODERATE,
                    direction = Direction.LEFT,
                    modifiers = setOf(CurveModifier.OPENING)
                ),
                carStandard
            )
            assertThat(text).isEqualTo("Left curve ahead, moderate, opening")
        }

        @Test
        fun `opening modifier hidden at minimal verbosity`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.SHARP,
                    direction = Direction.LEFT,
                    modifiers = setOf(CurveModifier.OPENING)
                ),
                carMinimal
            )
            // Sharp is narrated at minimal, but opening modifier is not
            assertThat(text).isNotNull()
            assertThat(text).doesNotContain("opening")
        }

        @Test
        fun `holds modifier with arc length`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.MODERATE,
                    direction = Direction.LEFT,
                    arcLength = 350.0,
                    modifiers = setOf(CurveModifier.HOLDS)
                ),
                carStandard
            )
            assertThat(text).isEqualTo("Left curve ahead, moderate, holds for 350 meters")
        }

        @Test
        fun `long modifier changes phrasing`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.GENTLE,
                    direction = Direction.LEFT,
                    arcLength = 400.0,
                    modifiers = setOf(CurveModifier.LONG)
                ),
                carDetailed // DESCRIPTIVE tier adds "steady speed"
            )
            assertThat(text).isEqualTo("Long gentle left, steady speed")
        }

        @Test
        fun `multiple modifiers combined`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.FIRM,
                    direction = Direction.RIGHT,
                    arcLength = 250.0,
                    modifiers = setOf(CurveModifier.TIGHTENING, CurveModifier.LONG),
                    advisorySpeedMs = 12.5 // ~45 km/h
                ),
                carStandard
            )
            assertThat(text).startsWith("Caution")
            assertThat(text).contains("Long firm right")
            assertThat(text).contains("tightening")
            assertThat(text).contains("slow to 45")
        }
    }

    // ========================================================================
    // Speed advisory formatting
    // ========================================================================

    @Nested
    @DisplayName("Speed Advisory")
    inner class SpeedAdvisory {

        @Test
        fun `speed advisory in KMH rounded to nearest 5`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.SHARP,
                    direction = Direction.RIGHT,
                    advisorySpeedMs = 12.5 // 45 km/h
                ),
                carStandard
            )
            assertThat(text).contains("slow to 45")
        }

        @Test
        fun `speed advisory in MPH`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.SHARP,
                    direction = Direction.RIGHT,
                    advisorySpeedMs = 12.5 // ~27.96 mph -> floor to 25
                ),
                carMph
            )
            assertThat(text).contains("slow to 25")
        }

        @Test
        fun `no speed advisory when null`() {
            val text = engine.generateNarration(
                curve(severity = Severity.MODERATE, direction = Direction.LEFT),
                carStandard
            )
            assertThat(text).doesNotContain("slow to")
        }

        @Test
        fun `speed advisory floors to nearest 5 KMH`() {
            // 13.89 m/s = 50.004 km/h -> floor to 50
            val text = engine.generateNarration(
                curve(
                    severity = Severity.FIRM,
                    direction = Direction.RIGHT,
                    advisorySpeedMs = 13.89
                ),
                carStandard
            )
            assertThat(text).contains("slow to 50")
        }

        @Test
        fun `low speed advisory rounds correctly`() {
            // 5.56 m/s = 20.016 km/h -> rounds to 20
            val text = engine.generateNarration(
                curve(
                    severity = Severity.HAIRPIN,
                    direction = Direction.LEFT,
                    advisorySpeedMs = 5.56
                ),
                carStandard
            )
            assertThat(text).contains("slow to 20")
        }
    }

    // ========================================================================
    // Motorcycle mode
    // ========================================================================

    @Nested
    @DisplayName("Motorcycle Mode")
    inner class MotorcycleMode {

        @Test
        fun `lean angle appended in motorcycle mode`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.MODERATE,
                    direction = Direction.LEFT,
                    advisorySpeedMs = 15.0,
                    leanAngleDeg = 20.0
                ),
                motoStandard
            )
            assertThat(text).contains("lean 20 degrees")
        }

        @Test
        fun `lean angle rounded to nearest 5`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.FIRM,
                    direction = Direction.RIGHT,
                    advisorySpeedMs = 11.0,
                    leanAngleDeg = 27.0
                ),
                motoStandard
            )
            assertThat(text).contains("lean 25 degrees")
        }

        @Test
        fun `extreme lean above 45 degrees`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.SHARP,
                    direction = Direction.LEFT,
                    advisorySpeedMs = 10.0,
                    leanAngleDeg = 48.0
                ),
                motoStandard
            )
            assertThat(text).contains("extreme lean")
            assertThat(text).doesNotContain("48")
        }

        @Test
        fun `lean angle not included when config disables it`() {
            val config = NarrationConfig(
                mode = DrivingMode.MOTORCYCLE,
                verbosity = 2,
                units = SpeedUnit.KMH,
                narrateLeanAngle = false
            )
            val text = engine.generateNarration(
                curve(
                    severity = Severity.MODERATE,
                    direction = Direction.LEFT,
                    leanAngleDeg = 20.0
                ),
                config
            )
            assertThat(text).doesNotContain("lean")
        }

        @Test
        fun `lean angle not shown in car mode`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.MODERATE,
                    direction = Direction.LEFT,
                    leanAngleDeg = 20.0
                ),
                carStandard
            )
            assertThat(text).doesNotContain("lean")
        }

        @Test
        fun `motorcycle tightening prepends Caution`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.SHARP,
                    direction = Direction.RIGHT,
                    modifiers = setOf(CurveModifier.TIGHTENING),
                    advisorySpeedMs = 10.0,
                    leanAngleDeg = 30.0
                ),
                motoStandard
            )
            assertThat(text).startsWith("Caution")
            assertThat(text).contains("tightening")
        }

        @Test
        fun `car mode tightening now prepends Caution`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.SHARP,
                    direction = Direction.RIGHT,
                    modifiers = setOf(CurveModifier.TIGHTENING),
                    advisorySpeedMs = 10.0
                ),
                carStandard
            )
            assertThat(text).startsWith("Caution")
        }

        @Test
        fun `motorcycle hairpin with lean angle`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.HAIRPIN,
                    direction = Direction.LEFT,
                    advisorySpeedMs = 5.56,
                    leanAngleDeg = 15.0
                ),
                motoStandard
            )
            assertThat(text).contains("Hairpin left ahead")
            assertThat(text).contains("slow to 20")
            assertThat(text).contains("lean 15 degrees")
        }
    }

    // ========================================================================
    // Compound curves
    // ========================================================================

    @Nested
    @DisplayName("Compound Curves")
    inner class CompoundCurves {

        @Test
        fun `S-bend left into right`() {
            val text = engine.generateNarration(
                curve(
                    direction = Direction.LEFT,
                    severity = Severity.MODERATE,
                    compoundType = CompoundType.S_BEND,
                    compoundSize = 2
                ),
                carStandard
            )
            assertThat(text).isEqualTo("Left into right, S-bend, moderate")
        }

        @Test
        fun `S-bend right into left`() {
            val text = engine.generateNarration(
                curve(
                    direction = Direction.RIGHT,
                    severity = Severity.FIRM,
                    compoundType = CompoundType.S_BEND,
                    compoundSize = 2
                ),
                carStandard
            )
            assertThat(text).isEqualTo("Right into left, S-bend, firm")
        }

        @Test
        fun `S-bend with speed advisory`() {
            val text = engine.generateNarration(
                curve(
                    direction = Direction.LEFT,
                    severity = Severity.SHARP,
                    compoundType = CompoundType.S_BEND,
                    compoundSize = 2,
                    advisorySpeedMs = 12.5
                ),
                carStandard
            )
            assertThat(text).contains("S-bend")
            assertThat(text).contains("slow to 45")
        }

        @Test
        fun `chicane left-right`() {
            val text = engine.generateNarration(
                curve(
                    direction = Direction.LEFT,
                    severity = Severity.SHARP,
                    compoundType = CompoundType.CHICANE,
                    compoundSize = 2,
                    advisorySpeedMs = 8.34 // 30.024 km/h -> floor to 30
                ),
                carStandard
            )
            assertThat(text).isEqualTo("Chicane, left-right, slow to 30")
        }

        @Test
        fun `chicane right-left`() {
            val text = engine.generateNarration(
                curve(
                    direction = Direction.RIGHT,
                    severity = Severity.SHARP,
                    compoundType = CompoundType.CHICANE,
                    compoundSize = 2,
                    advisorySpeedMs = 8.34 // 30.024 km/h -> floor to 30
                ),
                carStandard
            )
            assertThat(text).isEqualTo("Chicane, right-left, slow to 30")
        }

        @Test
        fun `series of curves`() {
            val text = engine.generateNarration(
                curve(
                    direction = Direction.RIGHT,
                    severity = Severity.SHARP,
                    compoundType = CompoundType.SERIES,
                    compoundSize = 5
                ),
                carStandard
            )
            assertThat(text).isEqualTo("Series of 5 curves, sharp")
        }

        @Test
        fun `series with speed advisory`() {
            val text = engine.generateNarration(
                curve(
                    direction = Direction.LEFT,
                    severity = Severity.FIRM,
                    compoundType = CompoundType.SERIES,
                    compoundSize = 4,
                    advisorySpeedMs = 12.5
                ),
                carStandard
            )
            assertThat(text).contains("Series of 4 curves")
            assertThat(text).contains("firm")
            assertThat(text).contains("slow to 45")
        }

        @Test
        fun `tightening sequence`() {
            val text = engine.generateNarration(
                curve(
                    direction = Direction.RIGHT,
                    severity = Severity.SHARP,
                    compoundType = CompoundType.TIGHTENING_SEQUENCE,
                    compoundSize = 3,
                    modifiers = setOf(CurveModifier.TIGHTENING),
                    advisorySpeedMs = 10.0
                ),
                carStandard
            )
            assertThat(text).startsWith("Caution")
            assertThat(text).contains("right, tightening through 3 curves")
            assertThat(text).contains("slow to")
        }

        @Test
        fun `motorcycle S-bend with tightening gets Caution`() {
            val text = engine.generateNarration(
                curve(
                    direction = Direction.LEFT,
                    severity = Severity.FIRM,
                    compoundType = CompoundType.S_BEND,
                    compoundSize = 2,
                    modifiers = setOf(CurveModifier.TIGHTENING)
                ),
                motoStandard
            )
            assertThat(text).startsWith("Caution")
            assertThat(text).contains("S-bend")
        }

        @Test
        fun `motorcycle tightening sequence always gets Caution`() {
            val text = engine.generateNarration(
                curve(
                    direction = Direction.LEFT,
                    severity = Severity.MODERATE,
                    compoundType = CompoundType.TIGHTENING_SEQUENCE,
                    compoundSize = 2,
                    advisorySpeedMs = 15.0,
                    leanAngleDeg = 18.0
                ),
                motoStandard
            )
            assertThat(text).startsWith("Caution")
        }
    }

    // ========================================================================
    // Verbosity filtering
    // ========================================================================

    @Nested
    @DisplayName("Verbosity Filtering")
    inner class VerbosityFiltering {

        @Test
        fun `minimal - gentle filtered out`() {
            val text = engine.generateNarration(
                curve(severity = Severity.GENTLE),
                carMinimal
            )
            assertThat(text).isNull()
        }

        @Test
        fun `minimal - moderate filtered out`() {
            val text = engine.generateNarration(
                curve(severity = Severity.MODERATE),
                carMinimal
            )
            assertThat(text).isNull()
        }

        @Test
        fun `minimal - firm filtered out`() {
            val text = engine.generateNarration(
                curve(severity = Severity.FIRM),
                carMinimal
            )
            assertThat(text).isNull()
        }

        @Test
        fun `minimal - sharp narrated`() {
            val text = engine.generateNarration(
                curve(severity = Severity.SHARP, direction = Direction.RIGHT),
                carMinimal
            )
            assertThat(text).isNotNull()
        }

        @Test
        fun `minimal - hairpin narrated`() {
            val text = engine.generateNarration(
                curve(severity = Severity.HAIRPIN, direction = Direction.LEFT, advisorySpeedMs = 5.56),
                carMinimal
            )
            assertThat(text).isNotNull()
        }

        @Test
        fun `standard - gentle filtered out`() {
            val text = engine.generateNarration(
                curve(severity = Severity.GENTLE),
                carStandard
            )
            assertThat(text).isNull()
        }

        @Test
        fun `standard - moderate narrated`() {
            val text = engine.generateNarration(
                curve(severity = Severity.MODERATE, direction = Direction.LEFT),
                carStandard
            )
            assertThat(text).isNotNull()
        }

        @Test
        fun `standard - firm narrated`() {
            val text = engine.generateNarration(
                curve(severity = Severity.FIRM, direction = Direction.RIGHT),
                carStandard
            )
            assertThat(text).isNotNull()
        }

        @Test
        fun `detailed - gentle narrated`() {
            val text = engine.generateNarration(
                curve(severity = Severity.GENTLE, direction = Direction.LEFT),
                carDetailed
            )
            assertThat(text).isNotNull()
        }

        @Test
        fun `tightening overrides verbosity filter`() {
            // Gentle + tightening should be narrated even at minimal
            // At TERSE tier, modifiers text is skipped but Caution prefix conveys danger
            val text = engine.generateNarration(
                curve(
                    severity = Severity.GENTLE,
                    direction = Direction.RIGHT,
                    modifiers = setOf(CurveModifier.TIGHTENING)
                ),
                carMinimal
            )
            assertThat(text).isNotNull()
            assertThat(text).startsWith("Caution")
        }
    }

    // ========================================================================
    // Straight segments
    // ========================================================================

    @Nested
    @DisplayName("Straight Segments")
    inner class StraightSegments {

        private val straight = StraightSegment(
            length = 300.0,
            startIndex = 0,
            endIndex = 30,
            distanceFromStart = 1000.0
        )

        @Test
        fun `straight narrated at detailed verbosity when enabled`() {
            val config = NarrationConfig(
                mode = DrivingMode.CAR,
                verbosity = 3,
                narrateStraights = true
            )
            val text = engine.generateStraightNarration(straight, config)
            assertThat(text).isEqualTo("Straight, 300 meters")
        }

        @Test
        fun `straight not narrated at standard verbosity`() {
            val config = NarrationConfig(
                mode = DrivingMode.CAR,
                verbosity = 2,
                narrateStraights = true
            )
            val text = engine.generateStraightNarration(straight, config)
            assertThat(text).isNull()
        }

        @Test
        fun `straight not narrated when disabled in config`() {
            val config = NarrationConfig(
                mode = DrivingMode.CAR,
                verbosity = 3,
                narrateStraights = false
            )
            val text = engine.generateStraightNarration(straight, config)
            assertThat(text).isNull()
        }

        @Test
        fun `straight distance rounded to nearest 10`() {
            val shortStraight = StraightSegment(
                length = 147.0,
                startIndex = 0,
                endIndex = 15,
                distanceFromStart = 500.0
            )
            val config = NarrationConfig(
                mode = DrivingMode.CAR,
                verbosity = 3,
                narrateStraights = true
            )
            val text = engine.generateStraightNarration(shortStraight, config)
            assertThat(text).isEqualTo("Straight, 150 meters")
        }
    }

    // ========================================================================
    // Sparse data / low confidence
    // ========================================================================

    @Nested
    @DisplayName("Sparse Data and Low Confidence")
    inner class SparseData {

        @Test
        fun `low confidence curve produces sparse data warning`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.SHARP,
                    direction = Direction.RIGHT,
                    confidence = 0.2f
                ),
                carStandard
            )
            assertThat(text).isEqualTo(
                "Low data quality ahead, curve information may be incomplete"
            )
        }

        @Test
        fun `confidence at threshold produces normal narration`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.SHARP,
                    direction = Direction.RIGHT,
                    confidence = 0.3f
                ),
                carStandard
            )
            // At exactly the threshold, should produce normal narration
            assertThat(text).contains("Sharp right ahead")
        }

        @Test
        fun `high confidence produces normal narration`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.MODERATE,
                    direction = Direction.LEFT,
                    confidence = 0.95f
                ),
                carStandard
            )
            assertThat(text).isEqualTo("Left curve ahead, moderate")
        }

        @Test
        fun `sparse data warning is constant`() {
            val warning = engine.generateSparseDataWarning()
            assertThat(warning).isEqualTo(
                "Low data quality ahead, curve information may be incomplete"
            )
        }
    }

    // ========================================================================
    // Off-route warning
    // ========================================================================

    @Nested
    @DisplayName("Off-Route Warning")
    inner class OffRouteWarning {

        @Test
        fun `off route warning text`() {
            val warning = engine.generateOffRouteWarning()
            assertThat(warning).isEqualTo("Off route \u2014 curve narration paused")
        }
    }

    // ========================================================================
    // Priority mapping
    // ========================================================================

    @Nested
    @DisplayName("Priority Mapping")
    inner class PriorityMapping {

        @Test
        fun `hairpin has highest curve priority`() {
            val priority = engine.priorityForCurve(curve(severity = Severity.HAIRPIN))
            assertThat(priority).isEqualTo(NarrationEvent.PRIORITY_HAIRPIN)
        }

        @Test
        fun `sharp is lower than hairpin`() {
            val hairpinPriority = engine.priorityForCurve(curve(severity = Severity.HAIRPIN))
            val sharpPriority = engine.priorityForCurve(curve(severity = Severity.SHARP))
            assertThat(sharpPriority).isLessThan(hairpinPriority)
        }

        @Test
        fun `priority order is correct`() {
            val priorities = Severity.entries.map { sev ->
                engine.priorityForCurve(curve(severity = sev))
            }
            // Should be ascending: GENTLE < MODERATE < FIRM < SHARP < HAIRPIN
            for (i in 0 until priorities.size - 1) {
                assertThat(priorities[i]).isLessThan(priorities[i + 1])
            }
        }
    }

    // ========================================================================
    // Full narration examples from PRD Section 6.1
    // ========================================================================

    @Nested
    @DisplayName("PRD Examples")
    inner class PrdExamples {

        @Test
        fun `moderate left curve constant radius`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.MODERATE,
                    direction = Direction.LEFT
                ),
                carStandard
            )
            assertThat(text).isEqualTo("Left curve ahead, moderate")
        }

        @Test
        fun `sharp right tightening with speed advisory`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.SHARP,
                    direction = Direction.RIGHT,
                    modifiers = setOf(CurveModifier.TIGHTENING),
                    advisorySpeedMs = 11.2 // 40.32 km/h -> floor to 40
                ),
                carStandard
            )
            assertThat(text).isEqualTo("Caution, Sharp right ahead, tightening, slow to 40")
        }

        @Test
        fun `S-bend left into right moderate`() {
            val text = engine.generateNarration(
                curve(
                    direction = Direction.LEFT,
                    severity = Severity.MODERATE,
                    compoundType = CompoundType.S_BEND,
                    compoundSize = 2
                ),
                carStandard
            )
            assertThat(text).isEqualTo("Left into right, S-bend, moderate")
        }

        @Test
        fun `hairpin left slow to 20`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.HAIRPIN,
                    direction = Direction.LEFT,
                    advisorySpeedMs = 5.56 // ~20 km/h
                ),
                carStandard
            )
            assertThat(text).isEqualTo("Hairpin left ahead, slow to 20")
        }

        @Test
        fun `90 degree right slow to 25`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.SHARP,
                    direction = Direction.RIGHT,
                    is90Degree = true,
                    advisorySpeedMs = 7.0 // 25.2 km/h -> floor to 25
                ),
                carStandard
            )
            assertThat(text).isEqualTo("90 degree right ahead, slow to 25")
        }

        @Test
        fun `long gentle left holds for 400 meters`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.GENTLE,
                    direction = Direction.LEFT,
                    arcLength = 400.0,
                    modifiers = setOf(CurveModifier.LONG, CurveModifier.HOLDS)
                ),
                carDetailed
            )
            assertThat(text).contains("Long gentle left")
            assertThat(text).contains("holds for 400 meters")
        }

        @Test
        fun `motorcycle sharp right with lean angle`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.SHARP,
                    direction = Direction.RIGHT,
                    advisorySpeedMs = 9.72, // ~34.99 km/h -> floor to 30
                    leanAngleDeg = 35.0
                ),
                motoStandard
            )
            assertThat(text).contains("Sharp right ahead")
            assertThat(text).contains("slow to 30")
            assertThat(text).contains("lean 35 degrees")
        }
    }

    // ========================================================================
    // Edge cases
    // ========================================================================

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCases {

        @Test
        fun `null lean angle in motorcycle mode`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.MODERATE,
                    direction = Direction.LEFT,
                    leanAngleDeg = null
                ),
                motoStandard
            )
            assertThat(text).doesNotContain("lean")
        }

        @Test
        fun `zero speed advisory`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.HAIRPIN,
                    direction = Direction.LEFT,
                    advisorySpeedMs = 0.0
                ),
                carStandard
            )
            assertThat(text).contains("slow to 0")
        }

        @Test
        fun `very high speed advisory rounds to nearest 5`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.FIRM,
                    direction = Direction.RIGHT,
                    advisorySpeedMs = 27.78 // 100 km/h
                ),
                carStandard
            )
            assertThat(text).contains("slow to 100")
        }

        @Test
        fun `chicane with motorcycle lean angle`() {
            val text = engine.generateNarration(
                curve(
                    direction = Direction.LEFT,
                    severity = Severity.SHARP,
                    compoundType = CompoundType.CHICANE,
                    compoundSize = 2,
                    advisorySpeedMs = 8.33,
                    leanAngleDeg = 30.0
                ),
                motoStandard
            )
            assertThat(text).contains("Chicane")
            assertThat(text).contains("lean 30 degrees")
        }
    }

    // ========================================================================
    // Verbosity Tier Dispatch
    // ========================================================================

    @Nested
    @DisplayName("Verbosity Tier Dispatch")
    inner class VerbosityTierDispatch {

        // TERSE tier tests (verbosity=1, no speed override)

        @Test
        fun `TERSE - sharp left is brief`() {
            val text = engine.generateNarration(
                curve(severity = Severity.SHARP, direction = Direction.LEFT),
                carMinimal // verbosity=1 -> TERSE
            )
            assertThat(text).isEqualTo("Sharp left")
        }

        @Test
        fun `TERSE - hairpin right with speed shows just number`() {
            val text = engine.generateNarration(
                curve(severity = Severity.HAIRPIN, direction = Direction.RIGHT, advisorySpeedMs = 5.56),
                carMinimal
            )
            assertThat(text).isEqualTo("Hairpin right, 20")
        }

        @Test
        fun `TERSE - sharp S-bend is brief`() {
            val text = engine.generateNarration(
                curve(
                    direction = Direction.LEFT, severity = Severity.SHARP,
                    compoundType = CompoundType.S_BEND, compoundSize = 2
                ),
                carMinimal
            )
            assertThat(text).isEqualTo("S-bend, left-right")
        }

        @Test
        fun `TERSE - chicane is brief`() {
            val text = engine.generateNarration(
                curve(
                    direction = Direction.LEFT, severity = Severity.SHARP,
                    compoundType = CompoundType.CHICANE, compoundSize = 2,
                    advisorySpeedMs = 8.34
                ),
                carMinimal
            )
            assertThat(text).isEqualTo("Chicane, left-right")
        }

        @Test
        fun `TERSE - series is brief`() {
            val text = engine.generateNarration(
                curve(
                    direction = Direction.RIGHT, severity = Severity.SHARP,
                    compoundType = CompoundType.SERIES, compoundSize = 5
                ),
                carMinimal
            )
            assertThat(text).isEqualTo("5 curves, sharp")
        }

        @Test
        fun `TERSE - 90 degree is brief`() {
            val text = engine.generateNarration(
                curve(
                    severity = Severity.SHARP, direction = Direction.RIGHT,
                    is90Degree = true, advisorySpeedMs = 7.0
                ),
                carMinimal
            )
            assertThat(text).isEqualTo("90 right, 25")
        }

        @Test
        fun `TERSE - tightening sequence is brief`() {
            val text = engine.generateNarration(
                curve(
                    direction = Direction.RIGHT, severity = Severity.SHARP,
                    compoundType = CompoundType.TIGHTENING_SEQUENCE, compoundSize = 3,
                    modifiers = setOf(CurveModifier.TIGHTENING), advisorySpeedMs = 10.0
                ),
                carMinimal
            )
            assertThat(text).isEqualTo("Caution, Tightening, 3 curves")
        }

        // DESCRIPTIVE tier tests (verbosity=3)

        @Test
        fun `DESCRIPTIVE - hairpin includes very tight`() {
            val text = engine.generateNarration(
                curve(severity = Severity.HAIRPIN, direction = Direction.LEFT, advisorySpeedMs = 5.56),
                carDetailed
            )
            assertThat(text).isEqualTo("Hairpin left ahead, very tight, slow to 20")
        }

        @Test
        fun `DESCRIPTIVE - moderate with no advisory gets steady speed`() {
            val text = engine.generateNarration(
                curve(severity = Severity.MODERATE, direction = Direction.LEFT),
                carDetailed
            )
            assertThat(text).isEqualTo("Left curve ahead, moderate, steady speed")
        }

        @Test
        fun `DESCRIPTIVE - S-bend has ahead prefix`() {
            val text = engine.generateNarration(
                curve(
                    direction = Direction.LEFT, severity = Severity.MODERATE,
                    compoundType = CompoundType.S_BEND, compoundSize = 2
                ),
                carDetailed
            )
            assertThat(text).isEqualTo("S-bend ahead, left into right, moderate")
        }

        @Test
        fun `DESCRIPTIVE - chicane has ahead prefix`() {
            val text = engine.generateNarration(
                curve(
                    direction = Direction.LEFT, severity = Severity.SHARP,
                    compoundType = CompoundType.CHICANE, compoundSize = 2,
                    advisorySpeedMs = 8.34
                ),
                carDetailed
            )
            assertThat(text).isEqualTo("Chicane ahead, left-right, slow to 30")
        }

        @Test
        fun `DESCRIPTIVE - series has ahead suffix`() {
            val text = engine.generateNarration(
                curve(
                    direction = Direction.RIGHT, severity = Severity.SHARP,
                    compoundType = CompoundType.SERIES, compoundSize = 5,
                    advisorySpeedMs = 12.5
                ),
                carDetailed
            )
            assertThat(text).isEqualTo("Series of 5 sharp curves ahead, slow to 45")
        }

        // Speed-adaptive test (verbosity=3 but high speed -> TERSE)

        @Test
        fun `speed-adaptive downgrades DESCRIPTIVE to TERSE at high speed`() {
            val text = engine.generateNarration(
                curve(severity = Severity.SHARP, direction = Direction.LEFT, advisorySpeedMs = 12.5),
                carDetailed,
                currentSpeedMs = 30.0 // 108 km/h -> TERSE
            )
            assertThat(text).isEqualTo("Sharp left, 45")
        }

        @Test
        fun `speed-adaptive downgrades DESCRIPTIVE to STANDARD at moderate speed`() {
            val text = engine.generateNarration(
                curve(severity = Severity.MODERATE, direction = Direction.LEFT),
                carDetailed,
                currentSpeedMs = 16.67 // 60 km/h -> STANDARD
            )
            assertThat(text).isEqualTo("Left curve ahead, moderate")
        }
    }
}

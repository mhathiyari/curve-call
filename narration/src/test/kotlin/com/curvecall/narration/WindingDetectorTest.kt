package com.curvecall.narration

import com.curvecall.engine.types.CurveModifier
import com.curvecall.engine.types.CurveSegment
import com.curvecall.engine.types.Direction
import com.curvecall.engine.types.LatLon
import com.curvecall.engine.types.Severity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class WindingDetectorTest {

    private val detector = WindingDetector()

    private fun curve(
        severity: Severity = Severity.MODERATE,
        direction: Direction = Direction.RIGHT,
        distanceFromStart: Double = 0.0,
        arcLength: Double = 50.0,
        modifiers: Set<CurveModifier> = emptySet(),
        advisorySpeedMs: Double? = null
    ) = CurveSegment(
        direction = direction, severity = severity, minRadius = 100.0,
        arcLength = arcLength, modifiers = modifiers, totalAngleChange = 45.0,
        is90Degree = false, advisorySpeedMs = advisorySpeedMs, leanAngleDeg = null,
        compoundType = null, compoundSize = null, confidence = 1.0f,
        startIndex = 0, endIndex = 10,
        startPoint = LatLon(46.0, 10.0), endPoint = LatLon(46.001, 10.001),
        distanceFromStart = distanceFromStart
    )

    @Nested
    @DisplayName("Winding Section Detection")
    inner class Detection {

        @Test
        fun `6 curves within window form a winding section`() {
            // 6 curves, each 50m long, gaps of 100m -> total span ~850m
            // At 13.9 m/s (50 km/h), window = 834m
            val curves = (0..5).map { i ->
                curve(distanceFromStart = i * 150.0, arcLength = 50.0)
            }
            val sections = detector.detectWindingSections(curves, 13.9)
            assertThat(sections).hasSize(1)
            assertThat(sections[0].curveCount).isEqualTo(6)
        }

        @Test
        fun `5 curves do not form winding section`() {
            val curves = (0..4).map { i ->
                curve(distanceFromStart = i * 150.0)
            }
            val sections = detector.detectWindingSections(curves, 13.9)
            assertThat(sections).isEmpty()
        }

        @Test
        fun `large gaps split into separate sections`() {
            // 6 curves close together, then a 2000m gap, then 6 more
            val group1 = (0..5).map { i -> curve(distanceFromStart = i * 100.0) }
            val group2 = (0..5).map { i -> curve(distanceFromStart = 2500.0 + i * 100.0) }
            val sections = detector.detectWindingSections(group1 + group2, 13.9)
            assertThat(sections).hasSize(2)
        }

        @Test
        fun `empty curve list returns no sections`() {
            val sections = detector.detectWindingSections(emptyList(), 13.9)
            assertThat(sections).isEmpty()
        }

        @Test
        fun `section captures max severity`() {
            val curves = listOf(
                curve(severity = Severity.MODERATE, distanceFromStart = 0.0),
                curve(severity = Severity.FIRM, distanceFromStart = 100.0),
                curve(severity = Severity.SHARP, distanceFromStart = 200.0),
                curve(severity = Severity.MODERATE, distanceFromStart = 300.0),
                curve(severity = Severity.FIRM, distanceFromStart = 400.0),
                curve(severity = Severity.MODERATE, distanceFromStart = 500.0)
            )
            val sections = detector.detectWindingSections(curves, 13.9)
            assertThat(sections).hasSize(1)
            assertThat(sections[0].maxSeverity).isEqualTo(Severity.SHARP)
        }

        @Test
        fun `section captures median severity`() {
            // Severities sorted: MODERATE, MODERATE, MODERATE, FIRM, FIRM, SHARP
            // Median (index 3) = FIRM
            val curves = listOf(
                curve(severity = Severity.SHARP, distanceFromStart = 0.0),
                curve(severity = Severity.FIRM, distanceFromStart = 100.0),
                curve(severity = Severity.MODERATE, distanceFromStart = 200.0),
                curve(severity = Severity.MODERATE, distanceFromStart = 300.0),
                curve(severity = Severity.FIRM, distanceFromStart = 400.0),
                curve(severity = Severity.MODERATE, distanceFromStart = 500.0)
            )
            val sections = detector.detectWindingSections(curves, 13.9)
            assertThat(sections).hasSize(1)
            assertThat(sections[0].medianSeverity).isEqualTo(Severity.FIRM)
        }

        @Test
        fun `section captures most conservative advisory speed`() {
            val curves = listOf(
                curve(distanceFromStart = 0.0, advisorySpeedMs = 15.0),
                curve(distanceFromStart = 100.0, advisorySpeedMs = 12.0),
                curve(distanceFromStart = 200.0, advisorySpeedMs = 8.0),
                curve(distanceFromStart = 300.0, advisorySpeedMs = null),
                curve(distanceFromStart = 400.0, advisorySpeedMs = 10.0),
                curve(distanceFromStart = 500.0, advisorySpeedMs = 14.0)
            )
            val sections = detector.detectWindingSections(curves, 13.9)
            assertThat(sections[0].advisorySpeedMs).isEqualTo(8.0)
        }

        @Test
        fun `higher speed expands window distance`() {
            // At 27.8 m/s (100 km/h), window = 1668m
            // Curves 250m apart (gap = 200m) -> should still form section
            val curves = (0..5).map { i ->
                curve(distanceFromStart = i * 250.0, arcLength = 50.0)
            }
            val sections = detector.detectWindingSections(curves, 27.8)
            assertThat(sections).hasSize(1)
        }
    }

    @Nested
    @DisplayName("Breakthrough Rules")
    inner class BreakthroughRules {

        private val section = WindingDetector.WindingSection(
            startDistance = 0.0, endDistance = 600.0, curveCount = 6,
            maxSeverity = Severity.SHARP,
            medianSeverity = Severity.MODERATE,
            advisorySpeedMs = 10.0,
            curves = emptyList()
        )

        @Test
        fun `HAIRPIN always breaks through`() {
            val c = curve(severity = Severity.HAIRPIN)
            assertThat(detector.shouldNarrateInWindingSection(c, section)).isTrue()
        }

        @Test
        fun `TIGHTENING always breaks through`() {
            val c = curve(severity = Severity.MODERATE, modifiers = setOf(CurveModifier.TIGHTENING))
            assertThat(detector.shouldNarrateInWindingSection(c, section)).isTrue()
        }

        @Test
        fun `severity median+2 breaks through`() {
            // median is MODERATE (ordinal 1), so SHARP (ordinal 3) = +2 -> breakthrough
            val c = curve(severity = Severity.SHARP)
            assertThat(detector.shouldNarrateInWindingSection(c, section)).isTrue()
        }

        @Test
        fun `severity median+1 does not break through`() {
            // median is MODERATE (ordinal 1), FIRM (ordinal 2) = +1 -> no breakthrough
            val c = curve(severity = Severity.FIRM)
            assertThat(detector.shouldNarrateInWindingSection(c, section)).isFalse()
        }

        @Test
        fun `same-as-median does not break through`() {
            val c = curve(severity = Severity.MODERATE)
            assertThat(detector.shouldNarrateInWindingSection(c, section)).isFalse()
        }

        @Test
        fun `GENTLE does not break through when median is MODERATE`() {
            val c = curve(severity = Severity.GENTLE)
            assertThat(detector.shouldNarrateInWindingSection(c, section)).isFalse()
        }
    }
}

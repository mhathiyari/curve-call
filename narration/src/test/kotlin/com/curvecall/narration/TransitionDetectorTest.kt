package com.curvecall.narration

import com.curvecall.engine.types.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested

class TransitionDetectorTest {

    private val detector = TransitionDetector()

    private fun curve(
        severity: Severity = Severity.MODERATE,
        direction: Direction = Direction.RIGHT,
        distanceFromStart: Double = 0.0,
        arcLength: Double = 50.0
    ) = CurveSegment(
        direction = direction, severity = severity, minRadius = 100.0,
        arcLength = arcLength, modifiers = emptySet(), totalAngleChange = 45.0,
        is90Degree = false, advisorySpeedMs = null, leanAngleDeg = null,
        compoundType = null, compoundSize = null, confidence = 1.0f,
        startIndex = 0, endIndex = 10,
        startPoint = LatLon(46.0, 10.0), endPoint = LatLon(46.001, 10.001),
        distanceFromStart = distanceFromStart
    )

    @Nested
    @DisplayName("Severity Transitions")
    inner class SeverityTransitions {

        @Test
        fun `detects severity increase of 2+ levels`() {
            // Group 1: two GENTLE curves, Group 2: two SHARP curves (gap > 200m between groups)
            val curves = listOf(
                curve(severity = Severity.GENTLE, distanceFromStart = 0.0),
                curve(severity = Severity.GENTLE, distanceFromStart = 100.0),
                curve(severity = Severity.SHARP, distanceFromStart = 400.0),
                curve(severity = Severity.SHARP, distanceFromStart = 500.0)
            )
            val transitions = detector.detectSeverityTransitions(curves)
            assertThat(transitions).hasSize(1)
            assertThat(transitions[0].type).isEqualTo(TransitionDetector.TransitionType.SEVERITY_INCREASE)
            assertThat(transitions[0].fromSeverity).isEqualTo(Severity.GENTLE)
            assertThat(transitions[0].toSeverity).isEqualTo(Severity.SHARP)
        }

        @Test
        fun `detects severity decrease of 2+ levels`() {
            val curves = listOf(
                curve(severity = Severity.SHARP, distanceFromStart = 0.0),
                curve(severity = Severity.SHARP, distanceFromStart = 100.0),
                curve(severity = Severity.GENTLE, distanceFromStart = 400.0),
                curve(severity = Severity.GENTLE, distanceFromStart = 500.0)
            )
            val transitions = detector.detectSeverityTransitions(curves)
            assertThat(transitions).hasSize(1)
            assertThat(transitions[0].type).isEqualTo(TransitionDetector.TransitionType.SEVERITY_DECREASE)
        }

        @Test
        fun `no transition when severity jump is only 1 level`() {
            val curves = listOf(
                curve(severity = Severity.MODERATE, distanceFromStart = 0.0),
                curve(severity = Severity.MODERATE, distanceFromStart = 100.0),
                curve(severity = Severity.FIRM, distanceFromStart = 400.0),
                curve(severity = Severity.FIRM, distanceFromStart = 500.0)
            )
            val transitions = detector.detectSeverityTransitions(curves)
            assertThat(transitions).isEmpty()
        }

        @Test
        fun `single curve returns no transitions`() {
            val transitions = detector.detectSeverityTransitions(listOf(curve()))
            assertThat(transitions).isEmpty()
        }

        @Test
        fun `transition distance is at start of new group`() {
            val curves = listOf(
                curve(severity = Severity.GENTLE, distanceFromStart = 0.0),
                curve(severity = Severity.GENTLE, distanceFromStart = 100.0),
                curve(severity = Severity.SHARP, distanceFromStart = 500.0),
                curve(severity = Severity.SHARP, distanceFromStart = 600.0)
            )
            val transitions = detector.detectSeverityTransitions(curves)
            assertThat(transitions[0].distanceFromStart).isEqualTo(500.0)
        }
    }

    @Nested
    @DisplayName("Density Transitions")
    inner class DensityTransitions {

        @Test
        fun `detects straight to winding after long gap`() {
            val curves = listOf(
                curve(distanceFromStart = 0.0, arcLength = 50.0),
                curve(distanceFromStart = 100.0, arcLength = 50.0),
                // 450m gap (> 500m threshold? No, gap = 650 - 150 = 500. Let's use 700)
                curve(distanceFromStart = 700.0, arcLength = 50.0),
                curve(distanceFromStart = 800.0, arcLength = 50.0)
            )
            val transitions = detector.detectDensityTransitions(curves)
            // Gap = 700 - (100+50) = 550m > 500m -> winding-to-straight + straight-to-winding
            assertThat(transitions).hasSize(2)
            assertThat(transitions[0].type).isEqualTo(TransitionDetector.TransitionType.WINDING_TO_STRAIGHT)
            assertThat(transitions[1].type).isEqualTo(TransitionDetector.TransitionType.STRAIGHT_TO_WINDING)
        }

        @Test
        fun `no density transition when curves are close`() {
            val curves = listOf(
                curve(distanceFromStart = 0.0),
                curve(distanceFromStart = 100.0),
                curve(distanceFromStart = 200.0)
            )
            val transitions = detector.detectDensityTransitions(curves)
            assertThat(transitions).isEmpty()
        }

        @Test
        fun `detects straight-to-winding at route start`() {
            val curves = listOf(
                curve(distanceFromStart = 600.0), // first curve starts 600m into route
                curve(distanceFromStart = 700.0)
            )
            val transitions = detector.detectDensityTransitions(curves)
            assertThat(transitions).hasSize(1)
            assertThat(transitions[0].type).isEqualTo(TransitionDetector.TransitionType.STRAIGHT_TO_WINDING)
            assertThat(transitions[0].distanceFromStart).isEqualTo(600.0)
        }

        @Test
        fun `detects winding-to-straight at route end`() {
            val curves = listOf(
                curve(distanceFromStart = 0.0, arcLength = 50.0),
                curve(distanceFromStart = 100.0, arcLength = 50.0)
            )
            val transitions = detector.detectDensityTransitions(curves, routeLength = 800.0)
            // Last curve ends at 150m, route is 800m -> 650m gap -> winding-to-straight
            assertThat(transitions).hasSize(1)
            assertThat(transitions[0].type).isEqualTo(TransitionDetector.TransitionType.WINDING_TO_STRAIGHT)
        }

        @Test
        fun `empty curves returns no transitions`() {
            assertThat(detector.detectDensityTransitions(emptyList())).isEmpty()
        }
    }

    @Nested
    @DisplayName("Combined Detection")
    inner class CombinedDetection {

        @Test
        fun `detectAll finds both severity and density transitions sorted`() {
            val curves = listOf(
                curve(severity = Severity.GENTLE, distanceFromStart = 0.0, arcLength = 50.0),
                curve(severity = Severity.GENTLE, distanceFromStart = 100.0, arcLength = 50.0),
                // 550m gap -> density transition at ~150m and ~700m
                curve(severity = Severity.SHARP, distanceFromStart = 700.0, arcLength = 50.0),
                curve(severity = Severity.SHARP, distanceFromStart = 800.0, arcLength = 50.0)
            )
            val transitions = detector.detectAll(curves)
            assertThat(transitions).isNotEmpty()
            // Should be sorted by distance
            for (i in 0 until transitions.size - 1) {
                assertThat(transitions[i].distanceFromStart).isLessThanOrEqualTo(transitions[i + 1].distanceFromStart)
            }
        }
    }
}

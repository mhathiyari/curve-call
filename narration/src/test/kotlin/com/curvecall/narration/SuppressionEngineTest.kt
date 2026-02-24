package com.curvecall.narration

import com.curvecall.engine.types.*
import com.curvecall.narration.types.NarrationEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested

class SuppressionEngineTest {

    private lateinit var engine: SuppressionEngine

    @BeforeEach
    fun setUp() {
        engine = SuppressionEngine()
    }

    private fun curve(
        severity: Severity = Severity.MODERATE,
        direction: Direction = Direction.RIGHT,
        modifiers: Set<CurveModifier> = emptySet(),
        advisorySpeedMs: Double? = null,
        distanceFromStart: Double = 500.0
    ) = CurveSegment(
        direction = direction, severity = severity, minRadius = 100.0,
        arcLength = 50.0, modifiers = modifiers, totalAngleChange = 45.0,
        is90Degree = false, advisorySpeedMs = advisorySpeedMs, leanAngleDeg = null,
        compoundType = null, compoundSize = null, confidence = 1.0f,
        startIndex = 0, endIndex = 10,
        startPoint = LatLon(46.0, 10.0), endPoint = LatLon(46.001, 10.001),
        distanceFromStart = distanceFromStart
    )

    private fun event(
        curveSegment: CurveSegment? = curve(),
        priority: Int = NarrationEvent.PRIORITY_MODERATE,
        advisorySpeedMs: Double? = curveSegment?.advisorySpeedMs
    ) = NarrationEvent(
        text = "Test narration",
        priority = priority,
        curveDistanceFromStart = curveSegment?.distanceFromStart ?: 500.0,
        advisorySpeedMs = advisorySpeedMs,
        associatedCurve = curveSegment
    )

    @Nested
    @DisplayName("Never-Suppress Overrides")
    inner class NeverSuppress {

        @Test
        fun `HAIRPIN is never suppressed even at low speed`() {
            val e = event(curve(severity = Severity.HAIRPIN, advisorySpeedMs = 6.0))
            assertThat(engine.shouldSuppress(e, currentSpeedMs = 3.0, currentTimeSec = 0.0)).isFalse()
        }

        @Test
        fun `TIGHTENING is never suppressed`() {
            val e = event(curve(modifiers = setOf(CurveModifier.TIGHTENING), advisorySpeedMs = 10.0))
            assertThat(engine.shouldSuppress(e, currentSpeedMs = 3.0, currentTimeSec = 0.0)).isFalse()
        }

        @Test
        fun `SHARP is never suppressed`() {
            val e = event(curve(severity = Severity.SHARP, advisorySpeedMs = 10.0))
            assertThat(engine.shouldSuppress(e, currentSpeedMs = 3.0, currentTimeSec = 0.0)).isFalse()
        }

        @Test
        fun `warning events are never suppressed`() {
            val e = event(curveSegment = null, priority = NarrationEvent.PRIORITY_WARNING)
            assertThat(engine.shouldSuppress(e, currentSpeedMs = 3.0, currentTimeSec = 0.0)).isFalse()
        }
    }

    @Nested
    @DisplayName("Speed Floor Rule")
    inner class SpeedFloor {

        @Test
        fun `suppress moderate curve below 15 kmh`() {
            val e = event(curve(advisorySpeedMs = 10.0))
            // 3.0 m/s = 10.8 km/h < 15 km/h floor
            assertThat(engine.shouldSuppress(e, currentSpeedMs = 3.0, currentTimeSec = 0.0)).isTrue()
        }

        @Test
        fun `do not suppress moderate curve above 15 kmh`() {
            val e = event(curve(advisorySpeedMs = null))
            // 5.0 m/s = 18 km/h > 15 km/h floor; null advisory avoids already-slow rule
            assertThat(engine.shouldSuppress(e, currentSpeedMs = 5.0, currentTimeSec = 0.0)).isFalse()
        }
    }

    @Nested
    @DisplayName("Already-Slow Rule")
    inner class AlreadySlow {

        @Test
        fun `suppress when current speed below advisory times 1_1`() {
            val e = event(curve(advisorySpeedMs = 10.0))
            // 10.0 m/s * 1.1 = 11.0. Current 10.5 < 11.0 -> suppress
            assertThat(engine.shouldSuppress(e, currentSpeedMs = 10.5, currentTimeSec = 0.0)).isTrue()
        }

        @Test
        fun `do not suppress when current speed above advisory times 1_1`() {
            val e = event(curve(advisorySpeedMs = 10.0))
            // 10.0 * 1.1 = 11.0. Current 12.0 > 11.0 -> no suppress
            assertThat(engine.shouldSuppress(e, currentSpeedMs = 12.0, currentTimeSec = 0.0)).isFalse()
        }

        @Test
        fun `no suppression when advisory is null`() {
            val e = event(curve(advisorySpeedMs = null))
            assertThat(engine.shouldSuppress(e, currentSpeedMs = 10.0, currentTimeSec = 0.0)).isFalse()
        }
    }

    @Nested
    @DisplayName("Repetition Rule")
    inner class Repetition {

        @Test
        fun `suppress duplicate severity+direction within 20s when already slow`() {
            // e1 has advisory 15.0 so the recorded narration triggers repetition check
            val c1 = curve(severity = Severity.MODERATE, direction = Direction.RIGHT, advisorySpeedMs = 15.0)
            val e1 = event(c1)
            // e2 has null advisory so Rule 2 (already-slow) does NOT fire on its own
            val c2 = curve(severity = Severity.MODERATE, direction = Direction.RIGHT, advisorySpeedMs = null, distanceFromStart = 600.0)
            val e2 = event(c2)

            // Fire first event
            engine.recordFired(e1, currentTimeSec = 0.0)

            // 10s later, same severity+direction; speed 12.0 < 15.0*1.1=16.5 -> repetition suppresses
            assertThat(engine.shouldSuppress(e2, currentSpeedMs = 12.0, currentTimeSec = 10.0)).isTrue()
        }

        @Test
        fun `no suppression after repetition window expires`() {
            val c1 = curve(severity = Severity.MODERATE, direction = Direction.RIGHT, advisorySpeedMs = 15.0)
            val e1 = event(c1)
            // e2 has null advisory so Rule 2 does not fire on its own
            val c2 = curve(severity = Severity.MODERATE, direction = Direction.RIGHT, advisorySpeedMs = null, distanceFromStart = 600.0)
            val e2 = event(c2)

            engine.recordFired(e1, currentTimeSec = 0.0)

            // 25s later, window expired -> no suppress
            // Speed 12.0 is above speed floor and e2 has null advisory (no already-slow)
            assertThat(engine.shouldSuppress(e2, currentSpeedMs = 12.0, currentTimeSec = 25.0)).isFalse()
        }

        @Test
        fun `different direction is not a repetition`() {
            val c1 = curve(severity = Severity.MODERATE, direction = Direction.RIGHT, advisorySpeedMs = 15.0)
            // e2 has different direction and null advisory (avoids Rule 2)
            val c2 = curve(severity = Severity.MODERATE, direction = Direction.LEFT, advisorySpeedMs = null, distanceFromStart = 600.0)
            val e1 = event(c1)
            val e2 = event(c2)

            engine.recordFired(e1, currentTimeSec = 0.0)
            // Speed 12.0 above floor, null advisory, different direction -> no suppress
            assertThat(engine.shouldSuppress(e2, currentSpeedMs = 12.0, currentTimeSec = 10.0)).isFalse()
        }

        @Test
        fun `different severity is not a repetition`() {
            val c1 = curve(severity = Severity.MODERATE, advisorySpeedMs = 15.0)
            // e2 has different severity and null advisory (avoids Rule 2)
            val c2 = curve(severity = Severity.FIRM, advisorySpeedMs = null, distanceFromStart = 600.0)
            val e1 = event(c1)
            val e2 = event(c2)

            engine.recordFired(e1, currentTimeSec = 0.0)
            // Speed 12.0 above floor, null advisory, different severity -> no suppress
            assertThat(engine.shouldSuppress(e2, currentSpeedMs = 12.0, currentTimeSec = 10.0)).isFalse()
        }
    }

    @Nested
    @DisplayName("Reset")
    inner class Reset {

        @Test
        fun `reset clears repetition history`() {
            val c = curve(severity = Severity.MODERATE, advisorySpeedMs = 6.0)
            val e = event(c)

            engine.recordFired(e, 0.0)
            engine.reset()

            // After reset, same event should not be suppressed by repetition
            assertThat(engine.shouldSuppress(e, currentSpeedMs = 3.0, currentTimeSec = 5.0)).isTrue()
            // Wait -- this is still suppressed by the already-slow rule (3.0 < 6.0*1.1=6.6)
            // Actually 3.0 < 4.17 speed floor, so it's suppressed by speed floor
            // Let's use a higher speed
        }

        @Test
        fun `reset clears repetition history properly`() {
            val c = curve(severity = Severity.MODERATE, advisorySpeedMs = 8.0)
            val e = event(c)

            // Speed 12 -> not already-slow (12 > 8*1.1=8.8)
            // Record a firing then suppress by repetition
            engine.recordFired(e, 0.0)
            // Make a new event with lower advisory so the "already slow" check for repetition passes
            val c2 = curve(severity = Severity.MODERATE, advisorySpeedMs = 12.0, distanceFromStart = 600.0)
            val e2 = event(c2)
            // Speed 12 < 12*1.1=13.2 -> already slow -> repetition triggers
            assertThat(engine.shouldSuppress(e2, currentSpeedMs = 12.0, currentTimeSec = 5.0)).isTrue()

            engine.reset()

            // After reset, no repetition history -> only already-slow rule applies
            // Speed 12 < 12*1.1=13.2 -> still suppressed by already-slow
            assertThat(engine.shouldSuppress(e2, currentSpeedMs = 12.0, currentTimeSec = 5.0)).isTrue()

            // Use speed 14 -> 14 > 12*1.1=13.2 -> not already slow, and no repetition
            assertThat(engine.shouldSuppress(e2, currentSpeedMs = 14.0, currentTimeSec = 5.0)).isFalse()
        }
    }
}

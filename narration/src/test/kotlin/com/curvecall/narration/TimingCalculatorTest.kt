package com.curvecall.narration

import com.curvecall.narration.types.DrivingMode
import com.curvecall.narration.types.TimingProfile
import com.curvecall.narration.types.TimingProfileConfig
import com.curvecall.narration.types.TriggerDecision
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for the TimingCalculator's kinematic timing model.
 *
 * Covers:
 * - Braking distance calculations
 * - TTS duration estimation
 * - Dynamic trigger evaluation (FIRE / URGENT / WAIT)
 * - Profile-dependent behavior (Relaxed / Normal / Sporty)
 */
class TimingCalculatorTest {

    private lateinit var calculator: TimingCalculator

    private val normalProfile = TimingProfileConfig.forProfile(TimingProfile.NORMAL)
    private val relaxedProfile = TimingProfileConfig.forProfile(TimingProfile.RELAXED)
    private val sportyProfile = TimingProfileConfig.forProfile(TimingProfile.SPORTY)

    @BeforeEach
    fun setUp() {
        calculator = TimingCalculator()
    }

    // ========================================================================
    // Braking distance
    // ========================================================================

    @Nested
    @DisplayName("Braking Distance")
    inner class BrakingDistance {

        @Test
        fun `no braking needed when advisory equals current speed`() {
            val dist = calculator.brakingDistance(20.0, 20.0, 4.0)
            assertThat(dist).isEqualTo(0.0)
        }

        @Test
        fun `no braking needed when advisory exceeds current speed`() {
            val dist = calculator.brakingDistance(15.0, 20.0, 4.0)
            assertThat(dist).isEqualTo(0.0)
        }

        @Test
        fun `braking from 100 kmh to 50 kmh car mode`() {
            // v1 = 27.78 m/s (100 km/h), v2 = 13.89 m/s (50 km/h)
            // d = (27.78^2 - 13.89^2) / (2 * 4.0) = (771.73 - 192.93) / 8.0 = 72.35m
            val dist = calculator.brakingDistance(27.78, 13.89, 4.0)
            assertThat(dist).isCloseTo(72.35, Offset.offset(0.5))
        }

        @Test
        fun `braking from 100 kmh to 0`() {
            // v1 = 27.78 m/s, v2 = 0
            // d = 27.78^2 / (2 * 4.0) = 771.73 / 8.0 = 96.47m
            val dist = calculator.brakingDistance(27.78, 0.0, 4.0)
            assertThat(dist).isCloseTo(96.47, Offset.offset(0.5))
        }

        @Test
        fun `motorcycle braking is longer than car`() {
            val carDist = calculator.brakingDistance(27.78, 13.89, 4.0)
            val motoDist = calculator.brakingDistance(27.78, 13.89, 3.0)
            assertThat(motoDist).isGreaterThan(carDist)
        }

        @Test
        fun `braking from 80 kmh to 30 kmh motorcycle`() {
            // v1 = 22.22 m/s (80 km/h), v2 = 8.33 m/s (30 km/h)
            // d = (22.22^2 - 8.33^2) / (2 * 3.0) = (493.73 - 69.39) / 6.0 = 70.72m
            val dist = calculator.brakingDistance(22.22, 8.33, 3.0)
            assertThat(dist).isCloseTo(70.72, Offset.offset(0.5))
        }

        @Test
        fun `zero decel rate returns max value`() {
            val dist = calculator.brakingDistance(27.78, 13.89, 0.0)
            assertThat(dist).isEqualTo(Double.MAX_VALUE)
        }
    }

    // ========================================================================
    // TTS duration estimation
    // ========================================================================

    @Nested
    @DisplayName("TTS Duration Estimation")
    inner class TtsDuration {

        @Test
        fun `short prompt duration`() {
            // "Sharp right ahead" = 3 words → 3/2.5 + 0.3 = 1.5s
            val duration = calculator.estimateTtsDuration("Sharp right ahead")
            assertThat(duration).isCloseTo(1.5, Offset.offset(0.05))
        }

        @Test
        fun `long prompt duration`() {
            // "Sharp right ahead, tightening, slow to 35" = 7 words → 7/2.5 + 0.3 = 3.1s
            val duration = calculator.estimateTtsDuration("Sharp right ahead, tightening, slow to 35")
            assertThat(duration).isCloseTo(3.1, Offset.offset(0.05))
        }

        @Test
        fun `medium prompt duration`() {
            // "Series of 3 curves ahead" = 5 words → 5/2.5 + 0.3 = 2.3s
            val duration = calculator.estimateTtsDuration("Series of 3 curves ahead")
            assertThat(duration).isCloseTo(2.3, Offset.offset(0.05))
        }

        @Test
        fun `empty text has only startup delay`() {
            val duration = calculator.estimateTtsDuration("")
            assertThat(duration).isCloseTo(0.3, Offset.offset(0.05))
        }
    }

    // ========================================================================
    // Deceleration rates
    // ========================================================================

    @Nested
    @DisplayName("Deceleration Rates")
    inner class DecelRates {

        @Test
        fun `car decel rate is 4 m per s squared`() {
            assertThat(calculator.decelRateForMode(DrivingMode.CAR)).isEqualTo(4.0)
        }

        @Test
        fun `motorcycle decel rate is 3 m per s squared`() {
            assertThat(calculator.decelRateForMode(DrivingMode.MOTORCYCLE)).isEqualTo(3.0)
        }

        @Test
        fun `motorcycle decel is lower than car`() {
            val carDecel = calculator.decelRateForMode(DrivingMode.CAR)
            val motoDecel = calculator.decelRateForMode(DrivingMode.MOTORCYCLE)
            assertThat(motoDecel).isLessThan(carDecel)
        }
    }

    // ========================================================================
    // Dynamic trigger evaluation
    // ========================================================================

    @Nested
    @DisplayName("Evaluate - Basic Triggering")
    inner class EvaluateBasic {

        @Test
        fun `WAIT when far from curve`() {
            val decision = calculator.evaluate(
                distanceToCurveEntry = 500.0,
                currentSpeedMs = 27.78, // 100 km/h
                advisorySpeedMs = 13.89, // 50 km/h
                ttsDurationSec = 2.0,
                profile = normalProfile,
                mode = DrivingMode.CAR
            )
            assertThat(decision).isEqualTo(TriggerDecision.WAIT)
        }

        @Test
        fun `FIRE when within lead distance`() {
            // 100 km/h = 27.78 m/s, advisory 50 km/h = 13.89 m/s, Normal profile
            // brakeDist = (27.78^2 - 13.89^2) / 8 ≈ 72.35m
            // reactionDist = 27.78 * 1.5 = 41.67m
            // ttsDist = 27.78 * 2.0 = 55.56m
            // total = 72.35 + 41.67 + 55.56 = 169.58m
            val decision = calculator.evaluate(
                distanceToCurveEntry = 160.0, // within lead distance
                currentSpeedMs = 27.78,
                advisorySpeedMs = 13.89,
                ttsDurationSec = 2.0,
                profile = normalProfile,
                mode = DrivingMode.CAR
            )
            assertThat(decision).isEqualTo(TriggerDecision.FIRE)
        }

        @Test
        fun `FIRE at minimum distance when slow`() {
            // 10 km/h = 2.78 m/s, no braking
            // reactionDist = 2.78 * 1.5 = 4.17m
            // ttsDist = 2.78 * 2.0 = 5.56m
            // total = 9.73m → clamped to 100m minimum
            val decision = calculator.evaluate(
                distanceToCurveEntry = 95.0,
                currentSpeedMs = 2.78,
                advisorySpeedMs = null,
                ttsDurationSec = 2.0,
                profile = normalProfile,
                mode = DrivingMode.CAR
            )
            assertThat(decision).isEqualTo(TriggerDecision.FIRE)
        }

        @Test
        fun `WAIT when past curve`() {
            val decision = calculator.evaluate(
                distanceToCurveEntry = -10.0, // already past
                currentSpeedMs = 27.78,
                advisorySpeedMs = 13.89,
                ttsDurationSec = 2.0,
                profile = normalProfile,
                mode = DrivingMode.CAR
            )
            assertThat(decision).isEqualTo(TriggerDecision.WAIT)
        }

        @Test
        fun `FIRE for awareness prompt no braking needed`() {
            // 40 km/h = 11.11 m/s, advisory 50 km/h (no braking)
            // brakeDist = 0
            // reactionDist = 11.11 * 1.5 = 16.67m
            // ttsDist = 11.11 * 2.0 = 22.22m
            // total = max(38.89, 100) = 100m
            val decision = calculator.evaluate(
                distanceToCurveEntry = 90.0,
                currentSpeedMs = 11.11,
                advisorySpeedMs = 13.89, // faster than current
                ttsDurationSec = 2.0,
                profile = normalProfile,
                mode = DrivingMode.CAR
            )
            assertThat(decision).isEqualTo(TriggerDecision.FIRE)
        }
    }

    // ========================================================================
    // Urgent alert triggering
    // ========================================================================

    @Nested
    @DisplayName("Evaluate - Urgent Alerts")
    inner class EvaluateUrgent {

        @Test
        fun `URGENT when dangerously close to braking point`() {
            // 100 km/h, braking to 50 km/h
            // brakeDist ≈ 72.35m, urgency threshold (Normal) = 0.6
            // Urgent fires when distanceToCurve < 72.35 * 0.6 = 43.41m
            val decision = calculator.evaluate(
                distanceToCurveEntry = 40.0, // below threshold
                currentSpeedMs = 27.78,
                advisorySpeedMs = 13.89,
                ttsDurationSec = 2.0,
                profile = normalProfile,
                mode = DrivingMode.CAR
            )
            assertThat(decision).isEqualTo(TriggerDecision.URGENT)
        }

        @Test
        fun `not URGENT when braking distance is comfortable`() {
            // Same speed but farther away
            val decision = calculator.evaluate(
                distanceToCurveEntry = 50.0, // above threshold (72.35 * 0.6 = 43.41)
                currentSpeedMs = 27.78,
                advisorySpeedMs = 13.89,
                ttsDurationSec = 2.0,
                profile = normalProfile,
                mode = DrivingMode.CAR
            )
            // Should be FIRE (within lead distance) but not URGENT
            assertThat(decision).isNotEqualTo(TriggerDecision.URGENT)
        }

        @Test
        fun `no URGENT when no braking needed`() {
            val decision = calculator.evaluate(
                distanceToCurveEntry = 10.0,
                currentSpeedMs = 11.11,
                advisorySpeedMs = null, // no braking
                ttsDurationSec = 2.0,
                profile = normalProfile,
                mode = DrivingMode.CAR
            )
            // Should not be urgent even though very close — no braking needed
            assertThat(decision).isNotEqualTo(TriggerDecision.URGENT)
        }

        @Test
        fun `no URGENT when already below advisory speed`() {
            val decision = calculator.evaluate(
                distanceToCurveEntry = 10.0,
                currentSpeedMs = 10.0,
                advisorySpeedMs = 13.89, // faster than current
                ttsDurationSec = 2.0,
                profile = normalProfile,
                mode = DrivingMode.CAR
            )
            assertThat(decision).isNotEqualTo(TriggerDecision.URGENT)
        }
    }

    // ========================================================================
    // Profile-dependent behavior
    // ========================================================================

    @Nested
    @DisplayName("Evaluate - Profile Behavior")
    inner class EvaluateProfiles {

        @Test
        fun `relaxed profile triggers earlier than normal`() {
            // At a distance where Normal says WAIT but Relaxed says FIRE
            // 100 km/h, advisory 50 km/h, 2s TTS
            // Normal lead: 72.35 + 41.67 + 55.56 = 169.58m
            // Relaxed lead: 72.35 + 69.45 + 55.56 = 197.36m (reactionTime = 2.5s)
            val normalDecision = calculator.evaluate(
                distanceToCurveEntry = 185.0,
                currentSpeedMs = 27.78,
                advisorySpeedMs = 13.89,
                ttsDurationSec = 2.0,
                profile = normalProfile,
                mode = DrivingMode.CAR
            )
            val relaxedDecision = calculator.evaluate(
                distanceToCurveEntry = 185.0,
                currentSpeedMs = 27.78,
                advisorySpeedMs = 13.89,
                ttsDurationSec = 2.0,
                profile = relaxedProfile,
                mode = DrivingMode.CAR
            )
            assertThat(normalDecision).isEqualTo(TriggerDecision.WAIT)
            assertThat(relaxedDecision).isEqualTo(TriggerDecision.FIRE)
        }

        @Test
        fun `sporty profile triggers later than normal`() {
            // At a distance where Sporty says WAIT but Normal says FIRE
            // Sporty lead: 72.35 + 27.78 + 55.56 = 155.69m (reactionTime = 1.0s)
            val sportyDecision = calculator.evaluate(
                distanceToCurveEntry = 160.0,
                currentSpeedMs = 27.78,
                advisorySpeedMs = 13.89,
                ttsDurationSec = 2.0,
                profile = sportyProfile,
                mode = DrivingMode.CAR
            )
            val normalDecision = calculator.evaluate(
                distanceToCurveEntry = 160.0,
                currentSpeedMs = 27.78,
                advisorySpeedMs = 13.89,
                ttsDurationSec = 2.0,
                profile = normalProfile,
                mode = DrivingMode.CAR
            )
            assertThat(sportyDecision).isEqualTo(TriggerDecision.WAIT)
            assertThat(normalDecision).isEqualTo(TriggerDecision.FIRE)
        }

        @Test
        fun `relaxed urgency threshold triggers urgent earlier`() {
            // brakeDist ≈ 72.35m
            // Relaxed urgency = 0.8 → urgent at 72.35 * 0.8 = 57.88m
            // Sporty urgency = 0.4 → urgent at 72.35 * 0.4 = 28.94m
            val relaxedDecision = calculator.evaluate(
                distanceToCurveEntry = 50.0,
                currentSpeedMs = 27.78,
                advisorySpeedMs = 13.89,
                ttsDurationSec = 2.0,
                profile = relaxedProfile,
                mode = DrivingMode.CAR
            )
            val sportyDecision = calculator.evaluate(
                distanceToCurveEntry = 50.0,
                currentSpeedMs = 27.78,
                advisorySpeedMs = 13.89,
                ttsDurationSec = 2.0,
                profile = sportyProfile,
                mode = DrivingMode.CAR
            )
            assertThat(relaxedDecision).isEqualTo(TriggerDecision.URGENT)
            assertThat(sportyDecision).isNotEqualTo(TriggerDecision.URGENT)
        }
    }

    // ========================================================================
    // Worked examples from spec
    // ========================================================================

    @Nested
    @DisplayName("Spec Worked Examples")
    inner class SpecExamples {

        @Test
        fun `100 kmh sharp curve with advisory 50 kmh`() {
            // v = 27.78 m/s, advisory = 13.89 m/s, Normal profile
            // brakeDist = 72.35m, reactionDist = 41.67m
            // TTS "Sharp right ahead, slow to 50" = 6 words → 2.7s
            // ttsDist = 27.78 * 2.7 = 75.01m
            // totalLead = 72.35 + 41.67 + 75.01 = 189.03m
            val ttsDuration = calculator.estimateTtsDuration("Sharp right ahead, slow to 50")

            // Just outside lead distance → WAIT
            val waitDecision = calculator.evaluate(
                distanceToCurveEntry = 195.0,
                currentSpeedMs = 27.78,
                advisorySpeedMs = 13.89,
                ttsDurationSec = ttsDuration,
                profile = normalProfile,
                mode = DrivingMode.CAR
            )
            assertThat(waitDecision).isEqualTo(TriggerDecision.WAIT)

            // Inside lead distance → FIRE
            val fireDecision = calculator.evaluate(
                distanceToCurveEntry = 185.0,
                currentSpeedMs = 27.78,
                advisorySpeedMs = 13.89,
                ttsDurationSec = ttsDuration,
                profile = normalProfile,
                mode = DrivingMode.CAR
            )
            assertThat(fireDecision).isEqualTo(TriggerDecision.FIRE)
        }

        @Test
        fun `60 kmh same curve much shorter lead distance`() {
            // v = 16.67 m/s, advisory = 13.89 m/s
            // brakeDist = (16.67^2 - 13.89^2) / 8 = (277.89 - 192.93) / 8 = 10.62m
            // reactionDist = 16.67 * 1.5 = 25.0m
            // TTS 2.7s → ttsDist = 16.67 * 2.7 = 45.01m
            // totalLead = 10.62 + 25.0 + 45.01 = 80.63m
            val ttsDuration = calculator.estimateTtsDuration("Sharp right ahead, slow to 50")

            val decision = calculator.evaluate(
                distanceToCurveEntry = 75.0,
                currentSpeedMs = 16.67,
                advisorySpeedMs = 13.89,
                ttsDurationSec = ttsDuration,
                profile = normalProfile,
                mode = DrivingMode.CAR
            )
            assertThat(decision).isEqualTo(TriggerDecision.FIRE)
        }

        @Test
        fun `45 kmh no braking needed uses minimum distance`() {
            // v = 12.5 m/s, advisory = 13.89 m/s (already below)
            // brakeDist = 0
            // reactionDist = 12.5 * 1.5 = 18.75m
            // ttsDist = 12.5 * 2.7 = 33.75m
            // total = max(52.5, 100) = 100m
            val ttsDuration = calculator.estimateTtsDuration("Sharp right ahead, slow to 50")

            // At 95m → FIRE (within 100m minimum)
            val decision = calculator.evaluate(
                distanceToCurveEntry = 95.0,
                currentSpeedMs = 12.5,
                advisorySpeedMs = 13.89,
                ttsDurationSec = ttsDuration,
                profile = normalProfile,
                mode = DrivingMode.CAR
            )
            assertThat(decision).isEqualTo(TriggerDecision.FIRE)

            // At 105m → WAIT (outside minimum)
            val waitDecision = calculator.evaluate(
                distanceToCurveEntry = 105.0,
                currentSpeedMs = 12.5,
                advisorySpeedMs = 13.89,
                ttsDurationSec = ttsDuration,
                profile = normalProfile,
                mode = DrivingMode.CAR
            )
            assertThat(waitDecision).isEqualTo(TriggerDecision.WAIT)
        }
    }
}

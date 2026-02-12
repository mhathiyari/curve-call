package com.curvecall.narration

import com.curvecall.narration.types.DrivingMode
import com.curvecall.narration.types.NarrationConfig
import com.curvecall.narration.types.SpeedUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for the TimingCalculator announcement distance computation.
 *
 * Verifies the formulas from PRD Section 6.3:
 * - announcement_distance = max(speed * lookAheadSeconds, MIN_ANNOUNCEMENT_DISTANCE)
 * - With braking: max(above, braking_distance * 1.5)
 * - braking_distance = (v^2 - v_advisory^2) / (2 * decelRate)
 */
class TimingCalculatorTest {

    private lateinit var calculator: TimingCalculator

    private val carConfig = NarrationConfig(
        mode = DrivingMode.CAR,
        verbosity = 2,
        units = SpeedUnit.KMH,
        lookAheadSeconds = 8.0
    )

    private val motoConfig = NarrationConfig(
        mode = DrivingMode.MOTORCYCLE,
        verbosity = 2,
        units = SpeedUnit.KMH,
        lookAheadSeconds = 10.0
    )

    @BeforeEach
    fun setUp() {
        calculator = TimingCalculator()
    }

    // ========================================================================
    // Basic announcement distance (no braking)
    // ========================================================================

    @Nested
    @DisplayName("Basic Announcement Distance")
    inner class BasicDistance {

        @Test
        fun `stationary returns minimum distance`() {
            val dist = calculator.announcementDistance(0.0, carConfig)
            assertThat(dist).isEqualTo(TimingCalculator.MIN_ANNOUNCEMENT_DISTANCE)
        }

        @Test
        fun `very slow speed returns minimum distance`() {
            // 5 km/h = ~1.39 m/s * 8s = 11.1m < 100m
            val dist = calculator.announcementDistance(1.39, carConfig)
            assertThat(dist).isEqualTo(TimingCalculator.MIN_ANNOUNCEMENT_DISTANCE)
        }

        @Test
        fun `100 kmh car mode`() {
            // 100 km/h = 27.78 m/s * 8s = 222.2m
            val dist = calculator.announcementDistance(27.78, carConfig)
            assertThat(dist).isCloseTo(222.2, Offset.offset(0.5))
        }

        @Test
        fun `60 kmh car mode`() {
            // 60 km/h = 16.67 m/s * 8s = 133.3m
            val dist = calculator.announcementDistance(16.67, carConfig)
            assertThat(dist).isCloseTo(133.3, Offset.offset(0.5))
        }

        @Test
        fun `100 kmh motorcycle mode`() {
            // 100 km/h = 27.78 m/s * 10s = 277.8m (motorcycle has longer look-ahead)
            val dist = calculator.announcementDistance(27.78, motoConfig)
            assertThat(dist).isCloseTo(277.8, Offset.offset(0.5))
        }

        @Test
        fun `80 kmh motorcycle mode`() {
            // 80 km/h = 22.22 m/s * 10s = 222.2m
            val dist = calculator.announcementDistance(22.22, motoConfig)
            assertThat(dist).isCloseTo(222.2, Offset.offset(0.5))
        }

        @Test
        fun `custom look-ahead seconds`() {
            val config = NarrationConfig(
                mode = DrivingMode.CAR,
                lookAheadSeconds = 12.0
            )
            // 100 km/h = 27.78 m/s * 12s = 333.3m
            val dist = calculator.announcementDistance(27.78, config)
            assertThat(dist).isCloseTo(333.3, Offset.offset(0.5))
        }

        @Test
        fun `look-ahead distance scales linearly with speed`() {
            val dist30 = calculator.announcementDistance(30.0, carConfig)
            val dist60 = calculator.announcementDistance(60.0, carConfig)
            // At these speeds, both are above minimum, so should be exactly 2x
            assertThat(dist60).isCloseTo(dist30 * 2.0, Offset.offset(0.1))
        }
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
    // Announcement distance with braking
    // ========================================================================

    @Nested
    @DisplayName("Announcement Distance With Braking")
    inner class WithBraking {

        @Test
        fun `braking extends announcement distance`() {
            // 100 km/h, need to slow to 30 km/h
            // Look-ahead: 27.78 * 8 = 222.2m
            // Braking: (27.78^2 - 8.33^2) / (2 * 4.0) = (771.73 - 69.39) / 8.0 = 87.79m
            // Braking * 1.5 = 131.69m
            // max(222.2, 131.69) = 222.2m (look-ahead dominates)
            val dist = calculator.announcementDistance(27.78, carConfig, 8.33)
            assertThat(dist).isCloseTo(222.2, Offset.offset(0.5))
        }

        @Test
        fun `heavy braking extends beyond look-ahead`() {
            // 150 km/h = 41.67 m/s, need to slow to 20 km/h = 5.56 m/s
            // Look-ahead: 41.67 * 8 = 333.3m
            // Braking: (41.67^2 - 5.56^2) / (2 * 4.0) = (1736.39 - 30.91) / 8.0 = 213.19m
            // Braking * 1.5 = 319.78m
            // max(333.3, 319.78) = 333.3m
            val dist = calculator.announcementDistance(41.67, carConfig, 5.56)
            assertThat(dist).isCloseTo(333.3, Offset.offset(0.5))
        }

        @Test
        fun `extreme braking at low speed extends beyond look-ahead`() {
            // 50 km/h = 13.89 m/s, need to slow to 0 (complete stop)
            // Look-ahead: 13.89 * 8 = 111.1m
            // Braking: 13.89^2 / (2 * 4.0) = 192.93 / 8.0 = 24.12m
            // Braking * 1.5 = 36.17m
            // max(111.1, 36.17) = 111.1m
            val dist = calculator.announcementDistance(13.89, carConfig, 0.0)
            assertThat(dist).isCloseTo(111.1, Offset.offset(0.5))
        }

        @Test
        fun `motorcycle braking extends further than car`() {
            // Same speed and advisory, motorcycle should need more distance
            val carDist = calculator.announcementDistance(27.78, carConfig, 8.33)
            val motoDist = calculator.announcementDistance(27.78, motoConfig, 8.33)
            // Motorcycle has both longer look-ahead AND lower decel rate
            assertThat(motoDist).isGreaterThan(carDist)
        }

        @Test
        fun `no advisory speed returns basic distance`() {
            val withAdvisory = calculator.announcementDistance(27.78, carConfig, null)
            val basic = calculator.announcementDistance(27.78, carConfig)
            assertThat(withAdvisory).isEqualTo(basic)
        }

        @Test
        fun `advisory at current speed returns basic distance`() {
            val dist = calculator.announcementDistance(27.78, carConfig, 27.78)
            val basic = calculator.announcementDistance(27.78, carConfig)
            assertThat(dist).isEqualTo(basic)
        }

        @Test
        fun `advisory above current speed returns basic distance`() {
            val dist = calculator.announcementDistance(20.0, carConfig, 30.0)
            val basic = calculator.announcementDistance(20.0, carConfig)
            assertThat(dist).isEqualTo(basic)
        }
    }

    // ========================================================================
    // Trigger distance from start
    // ========================================================================

    @Nested
    @DisplayName("Trigger Distance From Start")
    inner class TriggerDistance {

        @Test
        fun `trigger is curve distance minus announcement distance`() {
            // Curve at 1000m, driving at 100 km/h
            // Announcement dist = 27.78 * 8 = 222.2m
            // Trigger = 1000 - 222.2 = 777.8m
            val trigger = calculator.triggerDistanceFromStart(
                curveDistanceFromStart = 1000.0,
                currentSpeedMs = 27.78,
                config = carConfig
            )
            assertThat(trigger).isCloseTo(777.8, Offset.offset(0.5))
        }

        @Test
        fun `trigger clamped to zero when curve is too close`() {
            // Curve at 50m, but announcement distance is 100m+
            val trigger = calculator.triggerDistanceFromStart(
                curveDistanceFromStart = 50.0,
                currentSpeedMs = 27.78,
                config = carConfig
            )
            assertThat(trigger).isEqualTo(0.0)
        }

        @Test
        fun `trigger with braking advisory`() {
            val trigger = calculator.triggerDistanceFromStart(
                curveDistanceFromStart = 500.0,
                currentSpeedMs = 27.78,
                config = carConfig,
                advisorySpeedMs = 8.33
            )
            val announceDist = calculator.announcementDistance(27.78, carConfig, 8.33)
            assertThat(trigger).isCloseTo(500.0 - announceDist, Offset.offset(0.1))
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
    // Specific speed scenarios
    // ========================================================================

    @Nested
    @DisplayName("Speed Scenarios")
    inner class SpeedScenarios {

        @Test
        fun `walking speed - 5 kmh`() {
            // 5 km/h = 1.39 m/s * 8 = 11.1m -> clamped to 100m
            val dist = calculator.announcementDistance(1.39, carConfig)
            assertThat(dist).isEqualTo(100.0)
        }

        @Test
        fun `city speed - 50 kmh`() {
            // 50 km/h = 13.89 m/s * 8 = 111.1m
            val dist = calculator.announcementDistance(13.89, carConfig)
            assertThat(dist).isCloseTo(111.1, Offset.offset(0.5))
        }

        @Test
        fun `highway speed - 120 kmh`() {
            // 120 km/h = 33.33 m/s * 8 = 266.7m
            val dist = calculator.announcementDistance(33.33, carConfig)
            assertThat(dist).isCloseTo(266.7, Offset.offset(0.5))
        }

        @Test
        fun `autobahn speed - 200 kmh`() {
            // 200 km/h = 55.56 m/s * 8 = 444.4m
            val dist = calculator.announcementDistance(55.56, carConfig)
            assertThat(dist).isCloseTo(444.4, Offset.offset(0.5))
        }

        @Test
        fun `motorcycle on mountain pass - 60 kmh with braking to 20 kmh`() {
            // 60 km/h = 16.67 m/s, advisory 20 km/h = 5.56 m/s
            // Look-ahead: 16.67 * 10 = 166.7m
            // Braking: (16.67^2 - 5.56^2) / (2 * 3.0) = (277.89 - 30.91) / 6.0 = 41.16m
            // Braking * 1.5 = 61.75m
            // max(166.7, 61.75) = 166.7m
            val dist = calculator.announcementDistance(16.67, motoConfig, 5.56)
            assertThat(dist).isCloseTo(166.7, Offset.offset(0.5))
        }
    }
}

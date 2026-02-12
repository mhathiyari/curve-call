package com.curvecall.engine.pipeline

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LeanAngleCalculatorTest {

    @Nested
    inner class BasicCalculation {

        @Test
        fun `lean angle at advisory speed with 0_25g is approximately 14 degrees`() {
            // At 0.25g lateral, tan(theta) = v^2/(r*g) = 0.25g*g*r/(r*g) = 0.25
            // theta = atan(0.25) = 14.04 degrees
            val radiusM = 100.0
            val lateralG = 0.25
            val speedMs = SpeedAdvisor.calculateSpeedMs(radiusM, lateralG)

            val leanAngle = LeanAngleCalculator.calculateExact(speedMs, radiusM)
            assertThat(leanAngle).isCloseTo(14.04, Offset.offset(1.0))
        }

        @Test
        fun `lean angle at 0_35g is approximately 19 degrees`() {
            val radiusM = 100.0
            val lateralG = 0.35
            val speedMs = SpeedAdvisor.calculateSpeedMs(radiusM, lateralG)

            val leanAngle = LeanAngleCalculator.calculateExact(speedMs, radiusM)
            assertThat(leanAngle).isCloseTo(19.3, Offset.offset(1.0))
        }

        @Test
        fun `zero speed returns null`() {
            assertThat(LeanAngleCalculator.calculate(0.0, 100.0)).isNull()
        }
    }

    @Nested
    inner class RoundingAndCapping {

        @Test
        fun `lean angle rounds to nearest 5 degrees`() {
            val result = LeanAngleCalculator.calculate(10.0, 100.0)
            assertThat(result).isNotNull()
            assertThat(result!! % 5.0).isCloseTo(0.0, Offset.offset(0.001))
        }

        @Test
        fun `lean angle capped at 45 degrees`() {
            // Very high speed on tight curve would give > 45 degrees
            val result = LeanAngleCalculator.calculate(30.0, 20.0)
            assertThat(result).isNotNull()
            assertThat(result!!).isLessThanOrEqualTo(45.0)
        }

        @Test
        fun `extreme lean detected above 45 degrees`() {
            // v^2/(r*g) > tan(45) = 1 when v^2 > r*g
            // r=20, g=9.81 -> v > sqrt(196.2) = 14.0 m/s
            assertThat(LeanAngleCalculator.isExtremeLean(20.0, 20.0)).isTrue()
        }

        @Test
        fun `normal lean not extreme`() {
            assertThat(LeanAngleCalculator.isExtremeLean(10.0, 100.0)).isFalse()
        }

        @Test
        fun `zero speed is not extreme lean`() {
            assertThat(LeanAngleCalculator.isExtremeLean(0.0, 100.0)).isFalse()
        }
    }

    @Nested
    inner class MotorcycleModeIntegration {

        @Test
        fun `motorcycle mode adds lean angle to curve with advisory`() {
            val curve = TestHelpers.createTestCurve(
                minRadius = 50.0,
                advisorySpeedMs = SpeedAdvisor.calculateSpeedMs(50.0, 0.25)
            )

            val result = LeanAngleCalculator.applyLeanAngle(curve, isMotorcycleMode = true)

            assertThat(result.leanAngleDeg).isNotNull()
            assertThat(result.leanAngleDeg!!).isBetween(10.0, 45.0)
        }

        @Test
        fun `non-motorcycle mode does not add lean angle`() {
            val curve = TestHelpers.createTestCurve(
                minRadius = 50.0,
                advisorySpeedMs = SpeedAdvisor.calculateSpeedMs(50.0, 0.35)
            )

            val result = LeanAngleCalculator.applyLeanAngle(curve, isMotorcycleMode = false)

            assertThat(result.leanAngleDeg).isNull()
        }

        @Test
        fun `curve without advisory speed gets no lean angle`() {
            val curve = TestHelpers.createTestCurve(
                minRadius = 300.0,
                advisorySpeedMs = null
            )

            val result = LeanAngleCalculator.applyLeanAngle(curve, isMotorcycleMode = true)

            assertThat(result.leanAngleDeg).isNull()
        }
    }

    @Nested
    inner class Validation {

        @Test
        fun `negative speed throws`() {
            assertThrows<IllegalArgumentException> {
                LeanAngleCalculator.calculateExact(-10.0, 100.0)
            }
        }

        @Test
        fun `zero radius throws`() {
            assertThrows<IllegalArgumentException> {
                LeanAngleCalculator.calculateExact(10.0, 0.0)
            }
        }

        @Test
        fun `negative radius throws`() {
            assertThrows<IllegalArgumentException> {
                LeanAngleCalculator.calculateExact(10.0, -50.0)
            }
        }
    }
}

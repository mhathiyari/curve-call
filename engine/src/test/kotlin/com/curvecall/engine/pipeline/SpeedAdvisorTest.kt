package com.curvecall.engine.pipeline

import com.curvecall.engine.types.AnalysisConfig
import com.curvecall.engine.types.Severity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SpeedAdvisorTest {

    @Nested
    inner class SpeedCalculation {

        @Test
        fun `car mode 200m radius gives approximately 94 kmh`() {
            val speedMs = SpeedAdvisor.calculateSpeedMs(200.0, 0.35)
            val kmh = speedMs * SpeedAdvisor.MS_TO_KMH
            assertThat(kmh).isCloseTo(94.0, Offset.offset(5.0))
        }

        @Test
        fun `car mode 100m radius gives approximately 66 kmh`() {
            val speedMs = SpeedAdvisor.calculateSpeedMs(100.0, 0.35)
            val kmh = speedMs * SpeedAdvisor.MS_TO_KMH
            assertThat(kmh).isCloseTo(66.0, Offset.offset(5.0))
        }

        @Test
        fun `car mode 50m radius gives approximately 47 kmh`() {
            val speedMs = SpeedAdvisor.calculateSpeedMs(50.0, 0.35)
            val kmh = speedMs * SpeedAdvisor.MS_TO_KMH
            assertThat(kmh).isCloseTo(47.0, Offset.offset(5.0))
        }

        @Test
        fun `car mode 25m radius gives approximately 33 kmh`() {
            val speedMs = SpeedAdvisor.calculateSpeedMs(25.0, 0.35)
            val kmh = speedMs * SpeedAdvisor.MS_TO_KMH
            assertThat(kmh).isCloseTo(33.0, Offset.offset(5.0))
        }

        @Test
        fun `car mode 15m radius gives approximately 25 kmh`() {
            val speedMs = SpeedAdvisor.calculateSpeedMs(15.0, 0.35)
            val kmh = speedMs * SpeedAdvisor.MS_TO_KMH
            assertThat(kmh).isCloseTo(25.0, Offset.offset(5.0))
        }
    }

    @Nested
    inner class MotorcycleMode {

        @Test
        fun `motorcycle mode 200m radius gives approximately 79 kmh`() {
            val speedMs = SpeedAdvisor.calculateSpeedMs(200.0, 0.25)
            val kmh = speedMs * SpeedAdvisor.MS_TO_KMH
            assertThat(kmh).isCloseTo(79.0, Offset.offset(5.0))
        }

        @Test
        fun `motorcycle mode 100m radius gives approximately 56 kmh`() {
            val speedMs = SpeedAdvisor.calculateSpeedMs(100.0, 0.25)
            val kmh = speedMs * SpeedAdvisor.MS_TO_KMH
            assertThat(kmh).isCloseTo(56.0, Offset.offset(5.0))
        }

        @Test
        fun `motorcycle mode 50m radius gives approximately 39 kmh`() {
            val speedMs = SpeedAdvisor.calculateSpeedMs(50.0, 0.25)
            val kmh = speedMs * SpeedAdvisor.MS_TO_KMH
            assertThat(kmh).isCloseTo(39.0, Offset.offset(5.0))
        }

        @Test
        fun `motorcycle mode 25m radius gives approximately 28 kmh`() {
            val speedMs = SpeedAdvisor.calculateSpeedMs(25.0, 0.25)
            val kmh = speedMs * SpeedAdvisor.MS_TO_KMH
            assertThat(kmh).isCloseTo(28.0, Offset.offset(5.0))
        }

        @Test
        fun `motorcycle mode 15m radius gives approximately 21 kmh`() {
            val speedMs = SpeedAdvisor.calculateSpeedMs(15.0, 0.25)
            val kmh = speedMs * SpeedAdvisor.MS_TO_KMH
            assertThat(kmh).isCloseTo(21.0, Offset.offset(5.0))
        }
    }

    @Nested
    inner class Rounding {

        @Test
        fun `toKmhRounded rounds down to nearest 5`() {
            // 94 km/h -> 90 km/h (rounded down to nearest 5)
            val speedMs = SpeedAdvisor.calculateSpeedMs(200.0, 0.35)
            val rounded = SpeedAdvisor.toKmhRounded(speedMs)
            assertThat(rounded % 5.0).isCloseTo(0.0, Offset.offset(0.001))
            assertThat(rounded).isLessThanOrEqualTo(speedMs * SpeedAdvisor.MS_TO_KMH)
        }

        @Test
        fun `toMphRounded rounds down to nearest 5`() {
            val speedMs = SpeedAdvisor.calculateSpeedMs(100.0, 0.35)
            val rounded = SpeedAdvisor.toMphRounded(speedMs)
            assertThat(rounded % 5.0).isCloseTo(0.0, Offset.offset(0.001))
            assertThat(rounded).isLessThanOrEqualTo(speedMs * SpeedAdvisor.MS_TO_MPH)
        }

        @Test
        fun `rounding examples`() {
            // 47 km/h -> should round to 45
            assertThat(SpeedAdvisor.toKmhRounded(47.0 / SpeedAdvisor.MS_TO_KMH))
                .isCloseTo(45.0, Offset.offset(0.001))

            // 33 km/h -> should round to 30
            assertThat(SpeedAdvisor.toKmhRounded(33.0 / SpeedAdvisor.MS_TO_KMH))
                .isCloseTo(30.0, Offset.offset(0.001))
        }
    }

    @Nested
    inner class AdvisoryNeed {

        @Test
        fun `GENTLE does not need advisory`() {
            assertThat(SpeedAdvisor.needsAdvisory(Severity.GENTLE)).isFalse()
        }

        @Test
        fun `MODERATE does not need advisory by default (severity-only overload)`() {
            assertThat(SpeedAdvisor.needsAdvisory(Severity.MODERATE)).isFalse()
        }

        @Test
        fun `MODERATE needs advisory when speed below 70 kmh threshold`() {
            // 100m radius at 0.35g -> ~18.5 m/s -> ~66.7 km/h (below 70)
            val speedMs = SpeedAdvisor.calculateSpeedMs(100.0, 0.35)
            assertThat(SpeedAdvisor.needsAdvisory(Severity.MODERATE, speedMs)).isTrue()
        }

        @Test
        fun `MODERATE does not need advisory when speed above 70 kmh threshold`() {
            // 200m radius at 0.35g -> ~26.2 m/s -> ~94.3 km/h (above 70)
            val speedMs = SpeedAdvisor.calculateSpeedMs(200.0, 0.35)
            assertThat(SpeedAdvisor.needsAdvisory(Severity.MODERATE, speedMs)).isFalse()
        }

        @Test
        fun `MODERATE at boundary radius needs advisory`() {
            // 150m radius at 0.35g -> ~22.7 m/s -> ~81.8 km/h (above 70, no advisory)
            val speedMs = SpeedAdvisor.calculateSpeedMs(150.0, 0.35)
            assertThat(SpeedAdvisor.needsAdvisory(Severity.MODERATE, speedMs)).isFalse()
        }

        @Test
        fun `FIRM needs advisory`() {
            assertThat(SpeedAdvisor.needsAdvisory(Severity.FIRM)).isTrue()
        }

        @Test
        fun `SHARP needs advisory`() {
            assertThat(SpeedAdvisor.needsAdvisory(Severity.SHARP)).isTrue()
        }

        @Test
        fun `HAIRPIN needs advisory`() {
            assertThat(SpeedAdvisor.needsAdvisory(Severity.HAIRPIN)).isTrue()
        }

        @Test
        fun `context-dependent overload delegates to severity-only for non-MODERATE`() {
            // GENTLE: never advisory regardless of speed
            assertThat(SpeedAdvisor.needsAdvisory(Severity.GENTLE, 5.0)).isFalse()
            // FIRM: always advisory regardless of speed
            assertThat(SpeedAdvisor.needsAdvisory(Severity.FIRM, 100.0)).isTrue()
            // SHARP: always advisory
            assertThat(SpeedAdvisor.needsAdvisory(Severity.SHARP, 100.0)).isTrue()
            // HAIRPIN: always advisory
            assertThat(SpeedAdvisor.needsAdvisory(Severity.HAIRPIN, 100.0)).isTrue()
        }
    }

    @Nested
    inner class EdgeCases {

        @Test
        fun `zero radius throws`() {
            assertThrows<IllegalArgumentException> {
                SpeedAdvisor.calculateSpeedMs(0.0, 0.35)
            }
        }

        @Test
        fun `negative radius throws`() {
            assertThrows<IllegalArgumentException> {
                SpeedAdvisor.calculateSpeedMs(-10.0, 0.35)
            }
        }

        @Test
        fun `zero lateral G throws`() {
            assertThrows<IllegalArgumentException> {
                SpeedAdvisor.calculateSpeedMs(100.0, 0.0)
            }
        }

        @Test
        fun `very large radius gives high speed`() {
            val speedMs = SpeedAdvisor.calculateSpeedMs(1000.0, 0.35)
            val kmh = speedMs * SpeedAdvisor.MS_TO_KMH
            assertThat(kmh).isGreaterThan(100.0)
        }

        @Test
        fun `very small radius gives low speed`() {
            val speedMs = SpeedAdvisor.calculateSpeedMs(5.0, 0.35)
            val kmh = speedMs * SpeedAdvisor.MS_TO_KMH
            assertThat(kmh).isLessThan(20.0)
        }
    }
}

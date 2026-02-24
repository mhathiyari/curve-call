package com.curvecall.narration

import com.curvecall.narration.types.NarrationConfig
import com.curvecall.narration.types.VerbosityTier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested

class VerbosityTierTest {

    private val engine = TemplateEngine()

    @Nested
    @DisplayName("User Tier Resolution")
    inner class UserTierResolution {
        @Test
        fun `verbosity 1 maps to TERSE`() {
            val config = NarrationConfig(verbosity = 1)
            assertThat(engine.resolveTier(config)).isEqualTo(VerbosityTier.TERSE)
        }

        @Test
        fun `verbosity 2 maps to STANDARD`() {
            val config = NarrationConfig(verbosity = 2)
            assertThat(engine.resolveTier(config)).isEqualTo(VerbosityTier.STANDARD)
        }

        @Test
        fun `verbosity 3 maps to DESCRIPTIVE`() {
            val config = NarrationConfig(verbosity = 3)
            assertThat(engine.resolveTier(config)).isEqualTo(VerbosityTier.DESCRIPTIVE)
        }
    }

    @Nested
    @DisplayName("Speed-Adaptive Downgrade")
    inner class SpeedAdaptiveDowngrade {
        @Test
        fun `above 80 kmh downgrades to TERSE`() {
            val config = NarrationConfig(verbosity = 3) // DESCRIPTIVE
            val tier = engine.resolveTier(config, currentSpeedMs = 25.0) // 90 km/h
            assertThat(tier).isEqualTo(VerbosityTier.TERSE)
        }

        @Test
        fun `above 50 kmh downgrades to STANDARD`() {
            val config = NarrationConfig(verbosity = 3) // DESCRIPTIVE
            val tier = engine.resolveTier(config, currentSpeedMs = 16.67) // 60 km/h
            assertThat(tier).isEqualTo(VerbosityTier.STANDARD)
        }

        @Test
        fun `below 50 kmh allows DESCRIPTIVE`() {
            val config = NarrationConfig(verbosity = 3)
            val tier = engine.resolveTier(config, currentSpeedMs = 12.0) // ~43 km/h
            assertThat(tier).isEqualTo(VerbosityTier.DESCRIPTIVE)
        }

        @Test
        fun `speed downgrade does not upgrade user tier`() {
            val config = NarrationConfig(verbosity = 1) // TERSE
            val tier = engine.resolveTier(config, currentSpeedMs = 5.0) // very slow -> speed allows DESCRIPTIVE
            assertThat(tier).isEqualTo(VerbosityTier.TERSE) // but user chose TERSE
        }

        @Test
        fun `null speed returns user tier unchanged`() {
            val config = NarrationConfig(verbosity = 3)
            val tier = engine.resolveTier(config, currentSpeedMs = null)
            assertThat(tier).isEqualTo(VerbosityTier.DESCRIPTIVE)
        }

        @Test
        fun `boundary at exactly 80 kmh is STANDARD not TERSE`() {
            // 80 km/h = 22.22 m/s. The condition is >22.22, so exactly 22.22 should be STANDARD
            val config = NarrationConfig(verbosity = 3)
            val tier = engine.resolveTier(config, currentSpeedMs = 22.22)
            assertThat(tier).isEqualTo(VerbosityTier.STANDARD)
        }

        @Test
        fun `boundary just above 80 kmh is TERSE`() {
            val config = NarrationConfig(verbosity = 3)
            val tier = engine.resolveTier(config, currentSpeedMs = 22.23)
            assertThat(tier).isEqualTo(VerbosityTier.TERSE)
        }
    }
}

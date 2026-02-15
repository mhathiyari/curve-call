package com.curvecall.narration.types

/**
 * Configuration for the narration engine.
 *
 * Controls what gets narrated, how it's phrased, and when announcements trigger.
 *
 * @property mode Driving mode (CAR or MOTORCYCLE) — affects timing, speed advisories, and content.
 * @property verbosity Verbosity level: 1 = Minimal (sharp + hairpin only),
 *   2 = Standard (moderate and above), 3 = Detailed (all curves + straights).
 * @property units Speed unit for narrated speed advisories (MPH or KMH).
 * @property timingProfile Driver timing profile (RELAXED, NORMAL, SPORTY) that controls
 *   reaction time, cooldown gap, and urgency threshold.
 * @property narrateStraights Whether to narrate straight segments (only at Detailed verbosity).
 * @property narrateLeanAngle Whether to include lean angle in motorcycle mode narrations.
 * @property narrateSurface Whether to narrate surface change warnings (motorcycle mode).
 */
data class NarrationConfig(
    val mode: DrivingMode = DrivingMode.CAR,
    val verbosity: Int = 2,
    val units: SpeedUnit = SpeedUnit.KMH,
    val timingProfile: TimingProfile = TimingProfile.NORMAL,
    val narrateStraights: Boolean = false,
    val narrateLeanAngle: Boolean = true,
    val narrateSurface: Boolean = true
) {
    init {
        require(verbosity in 1..3) { "Verbosity must be 1, 2, or 3, got $verbosity" }
    }
}

package com.curvecall.narration.types

/**
 * Driver timing profile presets that control how early audio prompts fire.
 *
 * Each profile tunes two parameters:
 * - **reactionTimeSec**: Assumed time between hearing the prompt and starting to act.
 * - **urgencyThreshold**: Ratio of (distance-to-curve / braking-distance) below which
 *   an urgent "BRAKE" alert fires immediately.
 */
enum class TimingProfile {
    /** Early prompts. For distracted / unfamiliar drivers. */
    RELAXED,

    /** Balanced timing suitable for most drivers. */
    NORMAL,

    /** Late prompts. For alert, experienced drivers. */
    SPORTY
}

/**
 * Concrete timing parameters derived from a [TimingProfile].
 *
 * @property reactionTimeSec Cognitive + physical reaction time (seconds).
 * @property urgencyThreshold When `distanceToCurve / brakingDistance` falls below this
 *   ratio, an urgent immediate alert fires. Lower values = later urgent alerts.
 */
data class TimingProfileConfig(
    val reactionTimeSec: Double,
    val urgencyThreshold: Double
) {
    companion object {
        fun forProfile(profile: TimingProfile): TimingProfileConfig = when (profile) {
            TimingProfile.RELAXED -> TimingProfileConfig(
                reactionTimeSec = 2.5,
                urgencyThreshold = 0.8
            )
            TimingProfile.NORMAL -> TimingProfileConfig(
                reactionTimeSec = 1.5,
                urgencyThreshold = 0.6
            )
            TimingProfile.SPORTY -> TimingProfileConfig(
                reactionTimeSec = 1.0,
                urgencyThreshold = 0.4
            )
        }
    }
}

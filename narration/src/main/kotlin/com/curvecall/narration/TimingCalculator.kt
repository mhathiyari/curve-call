package com.curvecall.narration

import com.curvecall.narration.types.DrivingMode
import com.curvecall.narration.types.TimingProfileConfig
import com.curvecall.narration.types.TriggerDecision
import kotlin.math.max

/**
 * Evaluates narration trigger timing using real-time vehicle kinematics.
 *
 * On each GPS tick the [evaluate] method computes the total required lead distance
 * by working backwards from the action point:
 *
 * ```
 * |<-- TTS -->|<-- React -->|<-- Brake -->|
 * [trigger]    [speaking]    [processing]  [braking]  [curve entry]
 *     |                                                    |
 *     |<-------------- total lead distance --------------->|
 * ```
 *
 * The action point is the **braking point** when braking is needed (currentSpeed >
 * advisorySpeed), or the **curve entry** when the driver is already at/below
 * advisory speed (awareness prompt only).
 *
 * This class is pure Kotlin with no Android dependencies.
 */
class TimingCalculator {

    /**
     * Evaluate whether a narration event should fire on this GPS tick.
     *
     * @param distanceToCurveEntry Meters from current position to the curve's start point.
     * @param currentSpeedMs Current vehicle speed in m/s.
     * @param advisorySpeedMs Advisory entry speed in m/s, or null if no braking needed.
     * @param ttsDurationSec Estimated duration of the TTS utterance in seconds.
     * @param profile Driver timing profile (reaction time, cooldown, urgency threshold).
     * @param mode Driving mode (CAR or MOTORCYCLE) for deceleration rate selection.
     * @return [TriggerDecision.FIRE], [TriggerDecision.URGENT], or [TriggerDecision.WAIT].
     */
    fun evaluate(
        distanceToCurveEntry: Double,
        currentSpeedMs: Double,
        advisorySpeedMs: Double?,
        ttsDurationSec: Double,
        profile: TimingProfileConfig,
        mode: DrivingMode
    ): TriggerDecision {
        // Already past the curve — nothing to do
        if (distanceToCurveEntry <= 0.0) return TriggerDecision.WAIT

        val decel = decelRateForMode(mode)
        val needsBraking = advisorySpeedMs != null && currentSpeedMs > advisorySpeedMs

        // Braking distance (0 if no braking needed)
        val brakeDist = if (needsBraking) {
            brakingDistance(currentSpeedMs, advisorySpeedMs!!, decel)
        } else {
            0.0
        }

        // Urgent check: are we already dangerously close?
        if (needsBraking && brakeDist > 0.0) {
            val safetyRatio = distanceToCurveEntry / brakeDist
            if (safetyRatio < profile.urgencyThreshold) {
                return TriggerDecision.URGENT
            }
        }

        // Total lead distance = braking + reaction + TTS speaking
        val reactionDist = currentSpeedMs * profile.reactionTimeSec
        val ttsDist = currentSpeedMs * ttsDurationSec
        val leadDistance = max(brakeDist + reactionDist + ttsDist, MIN_ANNOUNCEMENT_DISTANCE)

        return if (distanceToCurveEntry <= leadDistance) {
            TriggerDecision.FIRE
        } else {
            TriggerDecision.WAIT
        }
    }

    /**
     * Estimate TTS utterance duration from the narration text.
     *
     * Uses an average speaking rate of ~2.5 words/second (150 wpm) plus
     * a 300ms startup delay for TTS engine initialization.
     *
     * @param text The narration text to estimate duration for.
     * @return Estimated duration in seconds.
     */
    fun estimateTtsDuration(text: String): Double {
        val wordCount = text.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
        return wordCount / WORDS_PER_SECOND + TTS_STARTUP_DELAY_SEC
    }

    /**
     * Calculate braking distance using the kinematic equation.
     *
     * `d = (v_initial^2 - v_final^2) / (2 * decel)`
     *
     * @param currentSpeedMs Current speed in m/s.
     * @param targetSpeedMs Target speed in m/s.
     * @param decelRate Deceleration rate in m/s^2.
     * @return Braking distance in meters. Returns 0 if no braking needed.
     */
    fun brakingDistance(
        currentSpeedMs: Double,
        targetSpeedMs: Double,
        decelRate: Double
    ): Double {
        if (targetSpeedMs >= currentSpeedMs) return 0.0
        if (decelRate <= 0.0) return Double.MAX_VALUE

        return (currentSpeedMs * currentSpeedMs - targetSpeedMs * targetSpeedMs) /
            (2.0 * decelRate)
    }

    /**
     * Get the deceleration rate for a driving mode.
     *
     * @param mode The driving mode.
     * @return Deceleration rate in m/s^2.
     */
    fun decelRateForMode(mode: DrivingMode): Double {
        return when (mode) {
            DrivingMode.CAR -> DECEL_RATE_CAR
            DrivingMode.MOTORCYCLE -> DECEL_RATE_MOTORCYCLE
        }
    }

    companion object {
        /** Minimum announcement distance regardless of speed. */
        const val MIN_ANNOUNCEMENT_DISTANCE = 100.0 // meters

        /** Comfortable braking deceleration for car mode. */
        const val DECEL_RATE_CAR = 4.0 // m/s^2

        /** More conservative braking deceleration for motorcycle mode. */
        const val DECEL_RATE_MOTORCYCLE = 3.0 // m/s^2

        /** TTS average speaking rate. */
        const val WORDS_PER_SECOND = 2.5

        /** TTS engine startup delay. */
        const val TTS_STARTUP_DELAY_SEC = 0.3
    }
}

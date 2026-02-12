package com.curvecall.narration

import com.curvecall.narration.types.DrivingMode
import com.curvecall.narration.types.NarrationConfig
import kotlin.math.max

/**
 * Calculates announcement distances based on current speed, driving mode,
 * and optional braking requirements.
 *
 * The timing model ensures narrations arrive early enough for the driver to react,
 * but not so early that they are forgotten.
 *
 * Formulas (from PRD Section 6.3):
 * ```
 * announcement_distance = max(currentSpeed * lookAheadSeconds, MIN_ANNOUNCEMENT_DISTANCE)
 *
 * If braking needed:
 *   braking_distance = (currentSpeed^2 - advisorySpeed^2) / (2 * decelRate)
 *   announcement_distance = max(announcement_distance, braking_distance * 1.5)
 * ```
 *
 * This class is pure Kotlin with no Android dependencies.
 */
class TimingCalculator {

    /**
     * Calculate the announcement distance for a curve.
     *
     * @param currentSpeedMs Current vehicle speed in meters per second.
     * @param config Narration configuration (for look-ahead seconds and driving mode).
     * @param advisorySpeedMs Advisory speed for the upcoming curve in m/s, or null if no
     *   braking is needed.
     * @return The distance in meters before the curve at which the narration should trigger.
     */
    fun announcementDistance(
        currentSpeedMs: Double,
        config: NarrationConfig,
        advisorySpeedMs: Double? = null
    ): Double {
        // Basic time-based look-ahead
        val lookAheadDistance = currentSpeedMs * config.lookAheadSeconds

        // Start with the minimum of look-ahead or MIN_ANNOUNCEMENT_DISTANCE
        var distance = max(lookAheadDistance, MIN_ANNOUNCEMENT_DISTANCE)

        // If braking is needed, ensure enough distance for comfortable braking
        if (advisorySpeedMs != null && advisorySpeedMs < currentSpeedMs) {
            val decelRate = decelRateForMode(config.mode)
            val brakingDistance = brakingDistance(currentSpeedMs, advisorySpeedMs, decelRate)
            // Add 50% safety margin on braking distance
            distance = max(distance, brakingDistance * BRAKING_SAFETY_MARGIN)
        }

        return distance
    }

    /**
     * Calculate the trigger distance from start for a curve.
     *
     * This is the route progress (distance from start) at which the narration should fire.
     * It equals the curve's start distance minus the announcement distance.
     *
     * @param curveDistanceFromStart Distance from route start to the curve's entry point (meters).
     * @param currentSpeedMs Current speed in m/s.
     * @param config Narration configuration.
     * @param advisorySpeedMs Advisory speed in m/s, or null.
     * @return The route progress value (meters from start) at which to trigger the narration.
     *   Will be clamped to 0.0 minimum (narrate immediately if already past trigger point).
     */
    fun triggerDistanceFromStart(
        curveDistanceFromStart: Double,
        currentSpeedMs: Double,
        config: NarrationConfig,
        advisorySpeedMs: Double? = null
    ): Double {
        val announceDist = announcementDistance(currentSpeedMs, config, advisorySpeedMs)
        return max(0.0, curveDistanceFromStart - announceDist)
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

        /** Safety margin multiplier applied to braking distance. */
        const val BRAKING_SAFETY_MARGIN = 1.5
    }
}

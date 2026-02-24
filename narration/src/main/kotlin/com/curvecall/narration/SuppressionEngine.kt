package com.curvecall.narration

import com.curvecall.engine.types.CurveModifier
import com.curvecall.engine.types.Severity
import com.curvecall.narration.types.NarrationEvent

/**
 * Runtime suppression engine that determines whether a narration event should
 * be suppressed at fire time. Rules are evaluated in order; first match wins.
 *
 * Never-suppress overrides are checked first: HAIRPIN, TIGHTENING, SHARP+,
 * and transition events always get narrated regardless of other rules.
 */
class SuppressionEngine {

    /** Record of a recently fired narration for repetition detection. */
    data class RecentNarration(
        val severity: Severity,
        val direction: com.curvecall.engine.types.Direction,
        val firedAtTimeSec: Double,
        val advisorySpeedMs: Double?
    )

    private val recentNarrations = mutableListOf<RecentNarration>()

    /**
     * Evaluate whether an event should be suppressed.
     *
     * @param event The narration event to evaluate.
     * @param currentSpeedMs Current vehicle speed in m/s.
     * @param currentTimeSec Current session time in seconds (for repetition window).
     * @return true if the event should be suppressed (not spoken).
     */
    fun shouldSuppress(
        event: NarrationEvent,
        currentSpeedMs: Double,
        currentTimeSec: Double
    ): Boolean {
        val curve = event.associatedCurve

        // Never-suppress overrides (checked first)
        if (curve != null) {
            if (curve.severity == Severity.HAIRPIN) return false
            if (CurveModifier.TIGHTENING in curve.modifiers) return false
            if (curve.severity >= Severity.SHARP) return false
        }
        // Transition events (no associated curve or high priority warnings) are never suppressed
        if (curve == null && event.priority >= NarrationEvent.PRIORITY_WARNING) return false

        // Rule 1: Speed floor — very slow, driver doesn't need audio guidance
        if (currentSpeedMs < SPEED_FLOOR_MS) return true

        // Rule 2: Already-slow — driver is already at or below advisory speed
        if (curve != null) {
            val advisory = curve.advisorySpeedMs
            if (advisory != null && currentSpeedMs < advisory * ALREADY_SLOW_FACTOR) return true
        }

        // Rule 3: Repetition — same severity+direction within window and already slow enough
        if (curve != null) {
            val isDuplicate = recentNarrations.any { recent ->
                recent.severity == curve.severity &&
                    recent.direction == curve.direction &&
                    (currentTimeSec - recent.firedAtTimeSec) < REPETITION_WINDOW_SEC &&
                    recent.advisorySpeedMs != null &&
                    currentSpeedMs < recent.advisorySpeedMs * ALREADY_SLOW_FACTOR
            }
            if (isDuplicate) return true
        }

        return false
    }

    /**
     * Record that an event was fired (for repetition tracking).
     * Call this AFTER an event passes suppression and is actually delivered.
     */
    fun recordFired(event: NarrationEvent, currentTimeSec: Double) {
        val curve = event.associatedCurve ?: return
        recentNarrations.add(
            RecentNarration(
                severity = curve.severity,
                direction = curve.direction,
                firedAtTimeSec = currentTimeSec,
                advisorySpeedMs = curve.advisorySpeedMs
            )
        )
        // Prune old entries
        recentNarrations.removeAll { currentTimeSec - it.firedAtTimeSec > REPETITION_WINDOW_SEC * 2 }
    }

    /**
     * Clear all state (call on session stop/reset).
     */
    fun reset() {
        recentNarrations.clear()
    }

    companion object {
        /** Below 15 km/h (4.17 m/s), suppress all narrations. */
        const val SPEED_FLOOR_MS = 4.17

        /** Suppress when current speed < advisory * 1.1 (already slow enough). */
        const val ALREADY_SLOW_FACTOR = 1.1

        /** Window for repetition detection (seconds). */
        const val REPETITION_WINDOW_SEC = 20.0
    }
}

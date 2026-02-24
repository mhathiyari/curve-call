package com.curvecall.narration.types

import com.curvecall.engine.types.CurveSegment
import com.curvecall.engine.types.Direction
import com.curvecall.engine.types.Severity

/**
 * A single narration event ready for delivery via TTS.
 *
 * Events are generated from analyzed curve segments and evaluated dynamically
 * on each GPS tick. The timing model uses current speed, braking kinematics,
 * TTS duration estimation, and the driver's [TimingProfile] to decide when
 * to fire each event.
 *
 * @property text The spoken text to deliver, e.g. "Sharp right ahead, tightening, slow to 35".
 * @property priority Priority for queue ordering and interrupt logic.
 *   Higher values = more important. Maps to severity:
 *   hairpin=6, sharp=5, firm=4, moderate=3, gentle=2, straight=1, urgent=8.
 * @property curveDistanceFromStart Distance in meters from route start to the curve's
 *   entry point. Used at runtime to compute distance-to-curve for trigger evaluation.
 * @property advisorySpeedMs Advisory speed for the curve in m/s, or null if no braking
 *   is needed. Used at runtime for braking distance calculations.
 * @property associatedCurve The CurveSegment this narration describes. Null for
 *   non-curve narrations (e.g., sparse data warnings, straight segments).
 * @property delivered Whether this event has already been spoken. Once delivered,
 *   it will not be re-triggered even if the driver passes through the trigger zone again.
 */
data class NarrationEvent(
    val text: String,
    val priority: Int,
    val curveDistanceFromStart: Double,
    val advisorySpeedMs: Double?,
    val associatedCurve: CurveSegment?,
    val delivered: Boolean = false,
    /** Curve direction for spatial audio pre-cue (populated from associatedCurve). */
    val direction: Direction? = null,
    /** Curve severity for spatial audio pre-cue (populated from associatedCurve). */
    val severity: Severity? = null
) {
    companion object {
        /** Priority constants mapping to severity levels. */
        const val PRIORITY_STRAIGHT = 1
        const val PRIORITY_GENTLE = 2
        const val PRIORITY_MODERATE = 3
        const val PRIORITY_FIRM = 4
        const val PRIORITY_SHARP = 5
        const val PRIORITY_HAIRPIN = 6

        /** Priority for system warnings (off-route, sparse data). */
        const val PRIORITY_WARNING = 7

        /** Priority for urgent brake alerts — highest, bypasses cooldown. */
        const val PRIORITY_URGENT = 8
    }
}

package com.curvecall.narration.types

import com.curvecall.engine.types.CurveSegment

/**
 * A single narration event ready for delivery via TTS.
 *
 * Events are generated from analyzed curve segments and queued for delivery
 * when the driver reaches the appropriate trigger distance.
 *
 * @property text The spoken text to deliver, e.g. "Sharp right ahead, tightening, slow to 35".
 * @property priority Priority for queue ordering and interrupt logic.
 *   Higher values = more important. Maps to severity:
 *   hairpin=6, sharp=5, firm=4, moderate=3, gentle=2, straight=1.
 * @property triggerDistanceFromStart Route distance (meters from route start) at which
 *   this narration should be triggered. Computed from the curve's position minus the
 *   announcement distance.
 * @property associatedCurve The CurveSegment this narration describes. Null for
 *   non-curve narrations (e.g., sparse data warnings, straight segments).
 * @property delivered Whether this event has already been spoken. Once delivered,
 *   it will not be re-triggered even if the driver passes through the trigger zone again.
 */
data class NarrationEvent(
    val text: String,
    val priority: Int,
    val triggerDistanceFromStart: Double,
    val associatedCurve: CurveSegment?,
    val delivered: Boolean = false
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
    }
}

package com.curvecall.narration.types

/**
 * Result of evaluating whether a narration event should fire on this GPS tick.
 */
enum class TriggerDecision {
    /** The driver is within the required lead distance — fire the prompt now. */
    FIRE,

    /** The driver is dangerously close to the braking point — fire an urgent alert immediately. */
    URGENT,

    /** The driver is still too far from the curve — wait and re-evaluate next tick. */
    WAIT
}

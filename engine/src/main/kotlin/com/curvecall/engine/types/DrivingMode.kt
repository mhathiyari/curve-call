package com.curvecall.engine.types

/**
 * Driving mode that affects narration behavior, timing, and content.
 *
 * CAR mode uses standard speed advisories and timing.
 * MOTORCYCLE mode adds lean angle narration, earlier warnings,
 * more conservative speed advisories, and surface warnings.
 */
enum class DrivingMode {
    /** Standard car driving mode. */
    CAR,

    /** Motorcycle mode with lean angles, surface warnings, and earlier timing. */
    MOTORCYCLE
}

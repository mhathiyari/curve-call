package com.curvecall.engine.types

/**
 * Configurable radius thresholds (in meters) for mapping curve radius to severity.
 *
 * A curve's minimum radius is compared against these thresholds:
 * - radius > [gentle] -> GENTLE
 * - radius > [moderate] -> MODERATE
 * - radius > [firm] -> FIRM
 * - radius > [sharp] -> SHARP
 * - radius <= [sharp] -> HAIRPIN
 */
data class SeverityThresholds(
    /** Radius above this is GENTLE. Default 200m. */
    val gentle: Double = 200.0,

    /** Radius above this (but below gentle) is MODERATE. Default 100m. */
    val moderate: Double = 100.0,

    /** Radius above this (but below moderate) is FIRM. Default 50m. */
    val firm: Double = 50.0,

    /** Radius above this (but below firm) is SHARP. Below this is HAIRPIN. Default 25m. */
    val sharp: Double = 25.0
)

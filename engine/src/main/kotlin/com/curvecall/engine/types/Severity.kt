package com.curvecall.engine.types

/**
 * Curve severity classification based on minimum radius.
 *
 * Ordered from least severe to most severe.
 * See PRD Section 7.5 for radius thresholds.
 *
 * WARNING: Enum order is semantic and MUST NOT be changed.
 * GENTLE must be first and HAIRPIN must be last. Multiple comparison
 * operators (>=, <=, compareTo) throughout the codebase depend on ordinal
 * ordering to filter curves by severity (e.g., `severity >= MODERATE`
 * in verbosity filtering, priority ordering in narration queue).
 * Reordering these entries will silently break narration filtering,
 * priority logic, and speed advisory decisions.
 */
enum class Severity {
    /** Barely needs steering input. Radius > 200m. */
    GENTLE,

    /** Clear curve, comfortable at moderate speed. Radius 100-200m. */
    MODERATE,

    /** Requires meaningful speed reduction. Radius 50-100m. */
    FIRM,

    /** Significant braking required. Radius 25-50m. */
    SHARP,

    /** Near-180 degree direction change. Radius < 25m. */
    HAIRPIN
}

package com.curvecall.engine.types

/**
 * Modifiers that describe how a curve's geometry changes through its arc.
 * A curve can have multiple modifiers simultaneously.
 */
enum class CurveModifier {
    /** Radius decreases through the curve - dangerous, always narrate. */
    TIGHTENING,

    /** Radius increases through the curve - generally safer. */
    OPENING,

    /** Constant radius over a long arc (>200m). */
    HOLDS,

    /** Arc length exceeds 200m. */
    LONG
}

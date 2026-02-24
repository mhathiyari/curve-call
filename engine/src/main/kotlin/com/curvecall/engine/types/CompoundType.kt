package com.curvecall.engine.types

/**
 * Types of compound curve patterns detected from sequences of individual curves.
 */
enum class CompoundType {
    /** Two curves in opposite directions (left-right or right-left) with < 50m gap. */
    S_BEND,

    /** An S-bend where both curves are SHARP or tighter. */
    CHICANE,

    /** Three or more curves linked with < 50m gaps. */
    SERIES,

    /** Same-direction curves where each is tighter than the last. */
    TIGHTENING_SEQUENCE,

    /** 3+ consecutive sharp/hairpin curves with alternating directions and <200m gaps. */
    SWITCHBACKS
}

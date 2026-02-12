package com.curvecall.engine.types

/**
 * A fully classified curve segment with all computed properties.
 * This is the primary output of the analysis pipeline for curved road sections.
 */
data class CurveSegment(
    /** Direction of the curve (LEFT or RIGHT). */
    val direction: Direction,

    /** Severity classification based on minimum radius. */
    val severity: Severity,

    /** Smallest smoothed radius within the curve, in meters. */
    val minRadius: Double,

    /** Total distance along the curve arc, in meters. */
    val arcLength: Double,

    /** Set of modifiers describing how the curve geometry changes. */
    val modifiers: Set<CurveModifier>,

    /** Difference between entry and exit bearing, in degrees. */
    val totalAngleChange: Double,

    /** True if this is a 90-degree junction turn (85-95 degree angle, <50m arc). */
    val is90Degree: Boolean,

    /** Recommended entry speed in m/s, or null if no advisory needed (gentle curves). */
    val advisorySpeedMs: Double?,

    /** Recommended lean angle in degrees (motorcycle mode only), or null. */
    val leanAngleDeg: Double?,

    /** Compound pattern this curve belongs to, or null if standalone. */
    val compoundType: CompoundType?,

    /** Number of curves in the compound group, or null if standalone. */
    val compoundSize: Int?,

    /** Data quality confidence score from 0.0 (lowest) to 1.0 (highest). */
    val confidence: Float,

    /** Index of the first point of this curve in the interpolated route point list. */
    val startIndex: Int,

    /** Index of the last point of this curve in the interpolated route point list. */
    val endIndex: Int,

    /** Geographic coordinate of the curve start. */
    val startPoint: LatLon,

    /** Geographic coordinate of the curve end. */
    val endPoint: LatLon,

    /** Distance from the route start to this curve's start, in meters. */
    val distanceFromStart: Double
)

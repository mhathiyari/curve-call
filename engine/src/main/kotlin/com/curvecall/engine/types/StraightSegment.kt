package com.curvecall.engine.types

/**
 * A straight road segment between curves.
 */
data class StraightSegment(
    /** Length of the straight section in meters. */
    val length: Double,

    /** Index of the first point of this segment in the interpolated route point list. */
    val startIndex: Int,

    /** Index of the last point of this segment in the interpolated route point list. */
    val endIndex: Int,

    /** Distance from the route start to this segment's start, in meters. */
    val distanceFromStart: Double
)

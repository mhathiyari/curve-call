package com.curvecall.engine.types

/**
 * A segment of the route — either a curve or a straight section.
 * The analysis pipeline produces a sequential list of these covering the entire route.
 */
sealed class RouteSegment {
    /** A curved section of road with full classification. */
    data class Curve(val data: CurveSegment) : RouteSegment()

    /** A straight section of road between curves. */
    data class Straight(val data: StraightSegment) : RouteSegment()
}

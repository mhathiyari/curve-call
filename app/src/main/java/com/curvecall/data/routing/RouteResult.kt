package com.curvecall.data.routing

import com.curvecall.engine.types.LatLon

/**
 * Result of an on-device route computation via GraphHopper.
 */
data class RouteResult(
    /** Route coordinates — feed directly to RouteAnalyzer.analyzeRoute(). */
    val points: List<LatLon>,
    val distanceMeters: Double,
    val timeMillis: Long,
)

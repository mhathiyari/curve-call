package com.curvecall.data.routing

import com.curvecall.engine.types.LatLon
import com.curvecall.engine.types.RouteMetadata

/**
 * Result of a route computation (on-device via GraphHopper or online via OSRM).
 */
data class RouteResult(
    /** Route coordinates — feed directly to RouteAnalyzer.analyzeRoute(). */
    val points: List<LatLon>,
    val distanceMeters: Double,
    val timeMillis: Long,
    /** Road metadata from GraphHopper (null for online/OSRM routes). */
    val metadata: RouteMetadata? = null,
)

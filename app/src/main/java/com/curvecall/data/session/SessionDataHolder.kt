package com.curvecall.data.session

import com.curvecall.engine.types.LatLon
import com.curvecall.engine.types.RouteSegment
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton holder for passing route analysis data from HomeViewModel
 * to SessionViewModel via DI, avoiding the need for large Parcelable
 * navigation arguments.
 *
 * The HomeViewModel stores the analysis results here after GPX loading,
 * and the SessionViewModel reads them when starting a session.
 *
 * This is cleared when the session ends.
 */
@Singleton
class SessionDataHolder @Inject constructor() {

    /** The analyzed route segments from the engine. */
    @Volatile
    var routeSegments: List<RouteSegment>? = null
        private set

    /** The original route points for map matching. */
    @Volatile
    var routePoints: List<LatLon>? = null
        private set

    /** The interpolated (uniformly spaced) points from analysis. Segment indices reference this list. */
    @Volatile
    var interpolatedPoints: List<LatLon>? = null
        private set

    /** Optional route name from GPX metadata. */
    @Volatile
    var routeName: String? = null
        private set

    /**
     * Store analysis results for the session to consume.
     * Thread-safe: @Volatile ensures visibility across threads
     * (HomeViewModel writes on Dispatchers.Default, SessionViewModel reads on Main).
     */
    @Synchronized
    fun setRouteData(
        segments: List<RouteSegment>,
        points: List<LatLon>,
        interpolated: List<LatLon>,
        name: String?
    ) {
        routeSegments = segments
        routePoints = points
        interpolatedPoints = interpolated
        routeName = name
    }

    /**
     * Clear all stored data (called when session ends).
     */
    @Synchronized
    fun clear() {
        routeSegments = null
        routePoints = null
        interpolatedPoints = null
        routeName = null
    }

    /**
     * Check if route data is available.
     */
    fun hasData(): Boolean = routeSegments != null && routePoints != null
}

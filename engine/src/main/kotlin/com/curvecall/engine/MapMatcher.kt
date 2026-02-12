package com.curvecall.engine

import com.curvecall.engine.geo.GeoMath
import com.curvecall.engine.types.LatLon
import com.curvecall.engine.types.RouteSegment

/**
 * Snaps GPS positions to the nearest point on a route polyline,
 * computes route progress, distance to next curve, and detects off-route conditions.
 *
 * Uses a sliding window approach for efficient matching: since GPS updates are
 * sequential, we search near the last matched position first.
 */
class MapMatcher(
    private val routePoints: List<LatLon>,
    private val segments: List<RouteSegment>
) {
    /** Off-route threshold in meters. GPS > this distance from route = off-route. */
    companion object {
        const val OFF_ROUTE_THRESHOLD_M = 100.0
        private const val SEARCH_WINDOW = 50 // segments to search around last match
    }

    /** Cumulative distances from route start for each point. */
    private val cumulativeDistances: DoubleArray

    /** Total route distance in meters. */
    val totalDistance: Double

    /** Last matched segment index for sliding window optimization. */
    private var lastMatchedIndex: Int = 0

    init {
        require(routePoints.size >= 2) { "Route must have at least 2 points" }
        cumulativeDistances = DoubleArray(routePoints.size)
        for (i in 1 until routePoints.size) {
            cumulativeDistances[i] = cumulativeDistances[i - 1] +
                    GeoMath.haversineDistance(routePoints[i - 1], routePoints[i])
        }
        totalDistance = cumulativeDistances.last()
    }

    /**
     * Result of matching a GPS position to the route.
     */
    data class MatchResult(
        /** GPS position snapped to the nearest point on the route. */
        val snappedPosition: LatLon,

        /** Distance along the route from the start to the snapped position, in meters. */
        val routeProgress: Double,

        /** Fraction of route completed (0.0 to 1.0). */
        val progressFraction: Double,

        /** Distance from the snapped position to the start of the next curve, in meters.
         *  Null if no more curves ahead. */
        val distanceToNextCurve: Double?,

        /** The next curve segment ahead, or null if none. */
        val nextCurve: RouteSegment.Curve?,

        /** Distance from GPS position to the route (perpendicular), in meters. */
        val distanceFromRoute: Double,

        /** True if the GPS position is more than [OFF_ROUTE_THRESHOLD_M] from the route. */
        val isOffRoute: Boolean,

        /** Index of the matched route segment. */
        val matchedSegmentIndex: Int
    )

    /**
     * Matches a GPS position to the route.
     *
     * @param gpsPosition Current GPS coordinates.
     * @return [MatchResult] with snapped position, progress, and distance to next curve.
     */
    fun matchToRoute(gpsPosition: LatLon): MatchResult {
        // Find the nearest segment using sliding window around last match
        val searchFrom = maxOf(0, lastMatchedIndex - SEARCH_WINDOW)
        val searchTo = minOf(routePoints.size - 2, lastMatchedIndex + SEARCH_WINDOW)

        var bestSegIdx = searchFrom
        var bestFraction = 0.0
        var bestDistance = Double.MAX_VALUE

        // First search the window
        for (i in searchFrom..searchTo) {
            val (fraction, dist) = GeoMath.projectOntoSegment(
                gpsPosition, routePoints[i], routePoints[i + 1]
            )
            if (dist < bestDistance) {
                bestDistance = dist
                bestSegIdx = i
                bestFraction = fraction
            }
        }

        // If window didn't find a close match (> OFF_ROUTE_THRESHOLD), search globally
        if (bestDistance > OFF_ROUTE_THRESHOLD_M) {
            for (i in 0 until routePoints.size - 1) {
                if (i in searchFrom..searchTo) continue // already checked
                val (fraction, dist) = GeoMath.projectOntoSegment(
                    gpsPosition, routePoints[i], routePoints[i + 1]
                )
                if (dist < bestDistance) {
                    bestDistance = dist
                    bestSegIdx = i
                    bestFraction = fraction
                }
            }
        }

        lastMatchedIndex = bestSegIdx

        // Compute snapped position
        val snapped = GeoMath.interpolate(
            routePoints[bestSegIdx], routePoints[bestSegIdx + 1], bestFraction
        )

        // Compute route progress
        val segStartDist = cumulativeDistances[bestSegIdx]
        val segLength = GeoMath.haversineDistance(routePoints[bestSegIdx], routePoints[bestSegIdx + 1])
        val routeProgress = segStartDist + segLength * bestFraction

        // Find next curve ahead
        val (distToNextCurve, nextCurve) = findNextCurve(routeProgress)

        return MatchResult(
            snappedPosition = snapped,
            routeProgress = routeProgress,
            progressFraction = if (totalDistance > 0) routeProgress / totalDistance else 0.0,
            distanceToNextCurve = distToNextCurve,
            nextCurve = nextCurve,
            distanceFromRoute = bestDistance,
            isOffRoute = bestDistance > OFF_ROUTE_THRESHOLD_M,
            matchedSegmentIndex = bestSegIdx
        )
    }

    /**
     * Finds the next curve segment ahead of the current route progress.
     *
     * @param currentProgress Distance along route from start (meters).
     * @return Pair of (distance to curve start in meters, the curve segment), or (null, null).
     */
    private fun findNextCurve(currentProgress: Double): Pair<Double?, RouteSegment.Curve?> {
        for (segment in segments) {
            if (segment is RouteSegment.Curve) {
                val curveStart = segment.data.distanceFromStart
                if (curveStart > currentProgress) {
                    return Pair(curveStart - currentProgress, segment)
                }
            }
        }
        return Pair(null, null)
    }

    /**
     * Finds all upcoming curves within the given look-ahead distance.
     *
     * @param currentProgress Distance along route from start (meters).
     * @param lookAheadM Maximum look-ahead distance in meters.
     * @return List of (distance to curve, curve segment) pairs.
     */
    fun findUpcomingCurves(
        currentProgress: Double,
        lookAheadM: Double
    ): List<Pair<Double, RouteSegment.Curve>> {
        val upcoming = mutableListOf<Pair<Double, RouteSegment.Curve>>()

        for (segment in segments) {
            if (segment is RouteSegment.Curve) {
                val curveStart = segment.data.distanceFromStart
                val distanceToCurve = curveStart - currentProgress

                if (distanceToCurve > 0 && distanceToCurve <= lookAheadM) {
                    upcoming.add(Pair(distanceToCurve, segment))
                }
            }
        }

        return upcoming.sortedBy { it.first }
    }

    /**
     * Resets the sliding window to the beginning of the route.
     * Call this when the user restarts navigation or returns to route.
     */
    fun reset() {
        lastMatchedIndex = 0
    }
}

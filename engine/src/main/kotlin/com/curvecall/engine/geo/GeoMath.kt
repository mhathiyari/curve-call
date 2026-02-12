package com.curvecall.engine.geo

import com.curvecall.engine.types.LatLon
import kotlin.math.*

/**
 * Geographic math utilities for distance, bearing, and interpolation
 * on the WGS-84 ellipsoid using the Haversine formula.
 */
object GeoMath {

    /** Earth's mean radius in meters (WGS-84). */
    const val EARTH_RADIUS_M = 6_371_000.0

    /**
     * Computes the great-circle distance between two points using the Haversine formula.
     *
     * @return Distance in meters.
     */
    fun haversineDistance(p1: LatLon, p2: LatLon): Double {
        val lat1 = Math.toRadians(p1.lat)
        val lat2 = Math.toRadians(p2.lat)
        val dLat = Math.toRadians(p2.lat - p1.lat)
        val dLon = Math.toRadians(p2.lon - p1.lon)

        val a = sin(dLat / 2).pow(2) +
                cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))

        return EARTH_RADIUS_M * c
    }

    /**
     * Computes the initial bearing (forward azimuth) from [from] to [to].
     *
     * @return Bearing in degrees, normalized to [0, 360).
     */
    fun bearing(from: LatLon, to: LatLon): Double {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLon = Math.toRadians(to.lon - from.lon)

        val x = sin(dLon) * cos(lat2)
        val y = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)

        val bearingRad = atan2(x, y)
        return (Math.toDegrees(bearingRad) + 360.0) % 360.0
    }

    /**
     * Interpolates a point at the given [fraction] (0.0 to 1.0) along the
     * great-circle path from [p1] to [p2].
     *
     * @param fraction 0.0 returns [p1], 1.0 returns [p2].
     */
    fun interpolate(p1: LatLon, p2: LatLon, fraction: Double): LatLon {
        require(fraction in 0.0..1.0) { "Fraction must be in [0, 1], got $fraction" }

        if (fraction == 0.0) return p1
        if (fraction == 1.0) return p2

        val lat1 = Math.toRadians(p1.lat)
        val lon1 = Math.toRadians(p1.lon)
        val lat2 = Math.toRadians(p2.lat)
        val lon2 = Math.toRadians(p2.lon)

        val d = haversineDistance(p1, p2) / EARTH_RADIUS_M // angular distance in radians

        if (d < 1e-12) return p1 // points are coincident

        val a = sin((1.0 - fraction) * d) / sin(d)
        val b = sin(fraction * d) / sin(d)

        val x = a * cos(lat1) * cos(lon1) + b * cos(lat2) * cos(lon2)
        val y = a * cos(lat1) * sin(lon1) + b * cos(lat2) * sin(lon2)
        val z = a * sin(lat1) + b * sin(lat2)

        val lat = atan2(z, sqrt(x * x + y * y))
        val lon = atan2(y, x)

        return LatLon(Math.toDegrees(lat), Math.toDegrees(lon))
    }

    /**
     * Computes the signed angular difference between two bearings.
     * Positive = clockwise (right), Negative = counter-clockwise (left).
     *
     * @return Angle in degrees, in the range (-180, 180].
     */
    fun bearingDifference(bearing1: Double, bearing2: Double): Double {
        var diff = bearing2 - bearing1
        while (diff > 180.0) diff -= 360.0
        while (diff <= -180.0) diff += 360.0
        return diff
    }

    /**
     * Projects a point onto the closest position on a line segment (p1 -> p2),
     * returning the fraction along the segment and the perpendicular distance.
     *
     * @return Pair of (fraction along segment 0..1, perpendicular distance in meters).
     */
    fun projectOntoSegment(point: LatLon, segStart: LatLon, segEnd: LatLon): Pair<Double, Double> {
        val dTotal = haversineDistance(segStart, segEnd)
        if (dTotal < 1e-6) {
            return Pair(0.0, haversineDistance(point, segStart))
        }

        // Use a flat-earth approximation for the projection since segments are short (~10m)
        val cosLat = cos(Math.toRadians(segStart.lat))
        val dx1 = (segEnd.lon - segStart.lon) * cosLat
        val dy1 = segEnd.lat - segStart.lat
        val dx2 = (point.lon - segStart.lon) * cosLat
        val dy2 = point.lat - segStart.lat

        val dot = dx1 * dx2 + dy1 * dy2
        val lenSq = dx1 * dx1 + dy1 * dy1

        val t = if (lenSq < 1e-18) 0.0 else (dot / lenSq).coerceIn(0.0, 1.0)

        val projected = interpolate(segStart, segEnd, t)
        val dist = haversineDistance(point, projected)

        return Pair(t, dist)
    }
}

package com.curvecall.engine.geo

import com.curvecall.engine.types.Direction
import com.curvecall.engine.types.LatLon
import kotlin.math.*

/**
 * Computes the Menger curvature (circumscribed circle radius) for three points
 * and determines the curve direction via cross product.
 */
object MengerCurvature {

    /**
     * Computes the radius of the circumscribed circle through three points.
     *
     * Uses Heron's formula for the area of the triangle and the relation:
     *   R = (a * b * c) / (4 * area)
     *
     * @return Radius in meters. Returns [Double.MAX_VALUE] if points are collinear (straight).
     */
    fun radius(p1: LatLon, p2: LatLon, p3: LatLon): Double {
        val a = GeoMath.haversineDistance(p1, p2)
        val b = GeoMath.haversineDistance(p2, p3)
        val c = GeoMath.haversineDistance(p1, p3)

        val s = (a + b + c) / 2.0
        val areaSquared = s * (s - a) * (s - b) * (s - c)

        // Guard against negative values from floating-point imprecision
        if (areaSquared <= 0.0) return Double.MAX_VALUE

        val area = sqrt(areaSquared)

        return if (area < 1e-10) {
            Double.MAX_VALUE // collinear points = straight
        } else {
            (a * b * c) / (4.0 * area)
        }
    }

    /**
     * Determines the curve direction from the cross product of vectors P1->P2 and P2->P3.
     *
     * Uses a local tangent plane approximation (adequate for nearby points).
     * Positive cross product (counter-clockwise turn) = LEFT.
     * Negative cross product (clockwise turn) = RIGHT.
     *
     * @return LEFT or RIGHT, or null if points are collinear.
     */
    fun direction(p1: LatLon, p2: LatLon, p3: LatLon): Direction? {
        // Convert to local tangent plane centered at p2, using meters
        val cosLat = cos(Math.toRadians(p2.lat))

        // Vector from p1 to p2 in local coordinates (approximate meters)
        val v1x = (p2.lon - p1.lon) * cosLat * GeoMath.EARTH_RADIUS_M * Math.PI / 180.0
        val v1y = (p2.lat - p1.lat) * GeoMath.EARTH_RADIUS_M * Math.PI / 180.0

        // Vector from p2 to p3 in local coordinates
        val v2x = (p3.lon - p2.lon) * cosLat * GeoMath.EARTH_RADIUS_M * Math.PI / 180.0
        val v2y = (p3.lat - p2.lat) * GeoMath.EARTH_RADIUS_M * Math.PI / 180.0

        // Cross product z-component
        val cross = v1x * v2y - v1y * v2x

        return when {
            cross > 1e-6 -> Direction.LEFT
            cross < -1e-6 -> Direction.RIGHT
            else -> null // collinear
        }
    }
}

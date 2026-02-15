package com.curvecall.data.tiles

import com.curvecall.engine.types.LatLon
import kotlin.math.*

/**
 * Calculates the set of OSM tiles needed to cover a route corridor.
 *
 * Algorithm: Walk route points, sample at ~50m intervals, expand each by a buffer,
 * collect unique tile coordinates at zoom levels 13-16.
 */
object CorridorTileCalculator {

    private const val SAMPLE_INTERVAL_M = 50.0
    private const val BUFFER_M = 500.0
    private val ZOOM_LEVELS = 13..16
    private const val EARTH_RADIUS = 6_371_000.0

    /**
     * Calculate all tile coordinates needed for a route corridor.
     *
     * @param routePoints The route points to cover.
     * @return Set of unique tile coordinates across zoom levels 13-16.
     */
    fun calculateTiles(routePoints: List<LatLon>): Set<TileCoordinate> {
        if (routePoints.isEmpty()) return emptySet()

        val tiles = mutableSetOf<TileCoordinate>()

        // Sample points along the route at ~50m intervals
        val sampledPoints = sampleRoute(routePoints)

        for (point in sampledPoints) {
            // Expand by buffer: compute bounding box corners
            val latDelta = Math.toDegrees(BUFFER_M / EARTH_RADIUS)
            val lonDelta = Math.toDegrees(BUFFER_M / (EARTH_RADIUS * cos(Math.toRadians(point.lat))))

            val minLat = point.lat - latDelta
            val maxLat = point.lat + latDelta
            val minLon = point.lon - lonDelta
            val maxLon = point.lon + lonDelta

            for (zoom in ZOOM_LEVELS) {
                val minTileX = lonToTileX(minLon, zoom)
                val maxTileX = lonToTileX(maxLon, zoom)
                val minTileY = latToTileY(maxLat, zoom) // Note: y is inverted
                val maxTileY = latToTileY(minLat, zoom)

                for (x in minTileX..maxTileX) {
                    for (y in minTileY..maxTileY) {
                        tiles.add(TileCoordinate(zoom, x, y))
                    }
                }
            }
        }

        return tiles
    }

    /**
     * Sample route points at approximately [SAMPLE_INTERVAL_M] intervals.
     */
    private fun sampleRoute(points: List<LatLon>): List<LatLon> {
        if (points.size <= 1) return points.toList()

        val sampled = mutableListOf(points.first())
        var accumulatedDistance = 0.0

        for (i in 1 until points.size) {
            val dist = haversine(points[i - 1], points[i])
            accumulatedDistance += dist
            if (accumulatedDistance >= SAMPLE_INTERVAL_M) {
                sampled.add(points[i])
                accumulatedDistance = 0.0
            }
        }

        // Always include last point
        if (sampled.last() != points.last()) {
            sampled.add(points.last())
        }

        return sampled
    }

    private fun haversine(p1: LatLon, p2: LatLon): Double {
        val dLat = Math.toRadians(p2.lat - p1.lat)
        val dLon = Math.toRadians(p2.lon - p1.lon)
        val lat1 = Math.toRadians(p1.lat)
        val lat2 = Math.toRadians(p2.lat)
        val a = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS * asin(sqrt(a))
    }

    /**
     * Standard slippy map tile X from longitude.
     * x = floor((lon + 180) / 360 * 2^zoom)
     */
    private fun lonToTileX(lon: Double, zoom: Int): Int {
        return floor((lon + 180.0) / 360.0 * (1 shl zoom).toDouble()).toInt()
            .coerceIn(0, (1 shl zoom) - 1)
    }

    /**
     * Standard slippy map tile Y from latitude.
     * y = floor((1 - ln(tan(lat_rad) + 1/cos(lat_rad)) / pi) / 2 * 2^zoom)
     */
    private fun latToTileY(lat: Double, zoom: Int): Int {
        val latRad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        val n = 1 shl zoom
        return floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt()
            .coerceIn(0, n - 1)
    }
}

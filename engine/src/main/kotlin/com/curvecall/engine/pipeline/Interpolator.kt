package com.curvecall.engine.pipeline

import com.curvecall.engine.geo.GeoMath
import com.curvecall.engine.types.LatLon

/**
 * Resamples a polyline of geographic points to uniform spacing.
 *
 * This normalizes curvature computation regardless of the density of the input
 * data source (GPX files, routing engine geometry, etc.).
 */
object Interpolator {

    /**
     * Resamples the given [points] so that consecutive output points are approximately
     * [spacingMeters] apart along the polyline.
     *
     * The first point is always included. The last point is included if the remaining
     * distance from the last emitted point is greater than half the spacing.
     *
     * @param points Ordered list of route coordinates. Must contain at least 2 points.
     * @param spacingMeters Target distance between consecutive output points. Default 10.0m.
     * @return Uniformly spaced point list.
     * @throws IllegalArgumentException if fewer than 2 points are provided.
     */
    fun resample(points: List<LatLon>, spacingMeters: Double = 10.0): List<LatLon> {
        require(points.size >= 2) { "Need at least 2 points to resample, got ${points.size}" }
        require(spacingMeters > 0.0) { "Spacing must be positive, got $spacingMeters" }

        val result = mutableListOf(points.first())

        var segmentIndex = 0
        var distanceAlongCurrentSegment = 0.0
        var remaining = spacingMeters // distance until the next emitted point

        while (segmentIndex < points.size - 1) {
            val segStart = points[segmentIndex]
            val segEnd = points[segmentIndex + 1]
            val segLength = GeoMath.haversineDistance(segStart, segEnd)

            var positionInSegment = distanceAlongCurrentSegment

            while (positionInSegment + remaining <= segLength) {
                positionInSegment += remaining
                val fraction = positionInSegment / segLength
                val interpolated = GeoMath.interpolate(segStart, segEnd, fraction.coerceIn(0.0, 1.0))
                result.add(interpolated)
                remaining = spacingMeters
            }

            // We've consumed part of the spacing budget in this segment
            remaining -= (segLength - positionInSegment)
            distanceAlongCurrentSegment = 0.0
            segmentIndex++
        }

        // Include the last point if we haven't just emitted something very close to it
        val lastEmitted = result.last()
        val lastOriginal = points.last()
        val distToEnd = GeoMath.haversineDistance(lastEmitted, lastOriginal)
        if (distToEnd > spacingMeters * 0.5) {
            result.add(lastOriginal)
        }

        return result
    }
}

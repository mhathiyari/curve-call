package com.curvecall.data.routing

import com.curvecall.engine.types.IntersectionPoint
import com.curvecall.engine.types.MetadataSpan
import com.curvecall.engine.types.RoadClass
import com.curvecall.engine.types.RouteMetadata
import com.curvecall.engine.types.Surface
import com.curvecall.engine.types.TurnType
import com.graphhopper.ResponsePath
import com.graphhopper.util.Instruction

/**
 * Extracts [RouteMetadata] from a GraphHopper [ResponsePath].
 *
 * Converts GraphHopper path details (which use point-index ranges) into
 * distance-based [MetadataSpan]s that are invariant across resampling.
 * Also converts routing instructions into [IntersectionPoint]s.
 */
object RouteMetadataExtractor {

    /**
     * Extract metadata from a completed GraphHopper route.
     *
     * @param path The GraphHopper response path (must have been requested with path details).
     * @return RouteMetadata with distance-based spans and intersection data.
     */
    fun extract(path: ResponsePath): RouteMetadata {
        val cumulativeDistances = computeCumulativeDistances(path)
        val allDetails = path.pathDetails ?: emptyMap()

        val roadClassSpans = extractPathDetails(
            allDetails["road_class"],
            cumulativeDistances
        ) { value -> RoadClass.fromString(value.toString()) }

        val surfaceSpans = extractPathDetails(
            allDetails["surface"],
            cumulativeDistances
        ) { value -> Surface.fromString(value.toString()) }

        val maxSpeedSpans = extractPathDetails(
            allDetails["max_speed"],
            cumulativeDistances
        ) { value ->
            when (value) {
                is Number -> {
                    val speed = value.toDouble()
                    if (speed > 0) speed else null
                }
                else -> null
            }
        }

        val intersections = extractIntersections(path)

        return RouteMetadata(
            roadClassSpans = roadClassSpans,
            surfaceSpans = surfaceSpans,
            maxSpeedSpans = maxSpeedSpans,
            intersections = intersections
        )
    }

    /**
     * Compute cumulative distances along the route from GraphHopper's PointList.
     * Index i = cumulative distance from point 0 to point i.
     */
    private fun computeCumulativeDistances(path: ResponsePath): DoubleArray {
        val pointList = path.points
        val size = pointList.size()
        val distances = DoubleArray(size)

        for (i in 1 until size) {
            val lat1 = pointList.getLat(i - 1)
            val lon1 = pointList.getLon(i - 1)
            val lat2 = pointList.getLat(i)
            val lon2 = pointList.getLon(i)
            distances[i] = distances[i - 1] + haversineDistance(lat1, lon1, lat2, lon2)
        }

        return distances
    }

    /**
     * Convert GraphHopper PathDetail list (index-based) to distance-based MetadataSpans.
     *
     * Each PathDetail has: first (start index, inclusive), last (end index, exclusive), value.
     */
    private fun <T> extractPathDetails(
        details: List<com.graphhopper.util.details.PathDetail>?,
        cumulativeDistances: DoubleArray,
        mapper: (Any) -> T
    ): List<MetadataSpan<T>> {
        if (details.isNullOrEmpty()) return emptyList()

        return details.mapNotNull { detail ->
            val startIdx = detail.first
            val endIdx = detail.last
            if (startIdx < 0 || endIdx > cumulativeDistances.size || startIdx >= endIdx) {
                return@mapNotNull null
            }

            val startDist = cumulativeDistances[startIdx]
            val endDist = cumulativeDistances[minOf(endIdx, cumulativeDistances.size - 1)]
            val value = detail.value ?: return@mapNotNull null

            MetadataSpan(
                startDistanceM = startDist,
                endDistanceM = endDist,
                value = mapper(value)
            )
        }
    }

    /**
     * Extract intersection points from GraphHopper routing instructions.
     *
     * Accumulates distances from each instruction to compute distance from route start.
     * Filters out CONTINUE_ON_STREET (sign == 0) since those aren't real intersections.
     */
    private fun extractIntersections(path: ResponsePath): List<IntersectionPoint> {
        val instructions = path.instructions ?: return emptyList()
        val result = mutableListOf<IntersectionPoint>()
        var cumulativeDistance = 0.0

        for (instruction in instructions) {
            val turnType = mapTurnType(instruction.sign)
            if (turnType.isTurn) {
                result.add(
                    IntersectionPoint(
                        distanceFromStartM = cumulativeDistance,
                        turnType = turnType,
                        name = instruction.name.takeIf { it.isNotBlank() }
                    )
                )
            }
            cumulativeDistance += instruction.distance
        }

        return result
    }

    /**
     * Map GraphHopper instruction sign codes to our TurnType enum.
     */
    private fun mapTurnType(sign: Int): TurnType {
        return when (sign) {
            Instruction.TURN_SHARP_LEFT -> TurnType.TURN_SHARP_LEFT
            Instruction.TURN_LEFT -> TurnType.TURN_LEFT
            Instruction.TURN_SLIGHT_LEFT -> TurnType.TURN_SLIGHT_LEFT
            Instruction.TURN_SLIGHT_RIGHT -> TurnType.TURN_SLIGHT_RIGHT
            Instruction.TURN_RIGHT -> TurnType.TURN_RIGHT
            Instruction.TURN_SHARP_RIGHT -> TurnType.TURN_SHARP_RIGHT
            Instruction.KEEP_LEFT -> TurnType.KEEP_LEFT
            Instruction.KEEP_RIGHT -> TurnType.KEEP_RIGHT
            Instruction.U_TURN_LEFT, Instruction.U_TURN_RIGHT, Instruction.U_TURN_UNKNOWN -> TurnType.U_TURN
            Instruction.USE_ROUNDABOUT -> TurnType.ROUNDABOUT
            Instruction.LEAVE_ROUNDABOUT -> TurnType.ROUNDABOUT
            Instruction.FINISH -> TurnType.ARRIVE
            Instruction.REACHED_VIA -> TurnType.OTHER
            else -> TurnType.OTHER
        }
    }

    /**
     * Simple Haversine distance in meters (avoids depending on GeoMath from engine).
     */
    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }
}

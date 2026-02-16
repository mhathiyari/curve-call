package com.curvecall.engine.types

/**
 * Road classification from OpenStreetMap, ordered from highest to lowest importance.
 */
enum class RoadClass {
    MOTORWAY,
    TRUNK,
    PRIMARY,
    SECONDARY,
    TERTIARY,
    RESIDENTIAL,
    UNCLASSIFIED,
    SERVICE,
    TRACK,
    OTHER;

    companion object {
        fun fromString(value: String): RoadClass {
            return when (value.lowercase()) {
                "motorway", "motorway_link" -> MOTORWAY
                "trunk", "trunk_link" -> TRUNK
                "primary", "primary_link" -> PRIMARY
                "secondary", "secondary_link" -> SECONDARY
                "tertiary", "tertiary_link" -> TERTIARY
                "residential", "living_street" -> RESIDENTIAL
                "unclassified" -> UNCLASSIFIED
                "service" -> SERVICE
                "track" -> TRACK
                else -> OTHER
            }
        }
    }
}

/**
 * Road surface type from OpenStreetMap.
 */
enum class Surface {
    PAVED,
    ASPHALT,
    CONCRETE,
    COBBLESTONE,
    GRAVEL,
    DIRT,
    SAND,
    UNPAVED,
    OTHER;

    val isPaved: Boolean
        get() = this in setOf(PAVED, ASPHALT, CONCRETE)

    companion object {
        fun fromString(value: String): Surface {
            return when (value.lowercase()) {
                "paved" -> PAVED
                "asphalt" -> ASPHALT
                "concrete", "concrete:plates", "concrete:lanes" -> CONCRETE
                "cobblestone", "sett", "paving_stones" -> COBBLESTONE
                "gravel", "fine_gravel", "compacted" -> GRAVEL
                "dirt", "earth", "mud" -> DIRT
                "sand" -> SAND
                "unpaved" -> UNPAVED
                else -> OTHER
            }
        }
    }
}

/**
 * Turn instruction type, used to identify intersections on the route.
 */
enum class TurnType {
    TURN_LEFT,
    TURN_RIGHT,
    TURN_SHARP_LEFT,
    TURN_SHARP_RIGHT,
    TURN_SLIGHT_LEFT,
    TURN_SLIGHT_RIGHT,
    KEEP_LEFT,
    KEEP_RIGHT,
    U_TURN,
    ROUNDABOUT,
    DEPART,
    ARRIVE,
    OTHER;

    val isTurn: Boolean
        get() = this in setOf(
            TURN_LEFT, TURN_RIGHT,
            TURN_SHARP_LEFT, TURN_SHARP_RIGHT,
            TURN_SLIGHT_LEFT, TURN_SLIGHT_RIGHT,
            U_TURN, ROUNDABOUT
        )
}

/**
 * A span of road metadata over a distance range along the route.
 *
 * @param T The type of metadata value (e.g., [RoadClass], [Surface], [Double]).
 * @param startDistanceM Distance from route start where this span begins.
 * @param endDistanceM Distance from route start where this span ends.
 * @param value The metadata value for this span.
 */
data class MetadataSpan<T>(
    val startDistanceM: Double,
    val endDistanceM: Double,
    val value: T
)

/**
 * An intersection point on the route, derived from routing instructions.
 *
 * @param distanceFromStartM Distance from route start to this intersection.
 * @param turnType The type of turn at this intersection.
 * @param name Optional street name at the intersection.
 */
data class IntersectionPoint(
    val distanceFromStartM: Double,
    val turnType: TurnType,
    val name: String? = null
)

/**
 * Road metadata extracted from a routing engine (e.g., GraphHopper) for an entire route.
 *
 * Metadata is stored as distance-based spans, which are invariant across resampling.
 * The engine pipeline uses lookup functions to query metadata at any distance along
 * the route without needing to change the core geometry processing.
 */
data class RouteMetadata(
    val roadClassSpans: List<MetadataSpan<RoadClass>> = emptyList(),
    val surfaceSpans: List<MetadataSpan<Surface>> = emptyList(),
    val maxSpeedSpans: List<MetadataSpan<Double?>> = emptyList(),
    val intersections: List<IntersectionPoint> = emptyList()
) {
    /**
     * Find the road class at a given distance from route start.
     * Returns null if no span covers the given distance.
     */
    fun roadClassAt(distanceM: Double): RoadClass? {
        return findSpanValue(roadClassSpans, distanceM)
    }

    /**
     * Find the surface type at a given distance from route start.
     * Returns null if no span covers the given distance.
     */
    fun surfaceAt(distanceM: Double): Surface? {
        return findSpanValue(surfaceSpans, distanceM)
    }

    /**
     * Find the max speed (km/h) at a given distance from route start.
     * Returns null if no span covers the given distance or if the span value is null.
     */
    fun maxSpeedAt(distanceM: Double): Double? {
        return findSpanValue(maxSpeedSpans, distanceM)
    }

    /**
     * Check whether a given distance is near an intersection (within tolerance).
     *
     * @param distanceM Distance from route start.
     * @param toleranceM How close (in meters) counts as "near". Default 30m.
     * @return true if any intersection is within tolerance of the given distance.
     */
    fun isNearIntersection(distanceM: Double, toleranceM: Double = 30.0): Boolean {
        return intersections.any { intersection ->
            kotlin.math.abs(intersection.distanceFromStartM - distanceM) <= toleranceM
        }
    }

    /**
     * Generic span lookup using linear scan.
     * Spans are expected to be non-overlapping and sorted by start distance.
     */
    private fun <T> findSpanValue(spans: List<MetadataSpan<T>>, distanceM: Double): T? {
        for (span in spans) {
            if (distanceM >= span.startDistanceM && distanceM < span.endDistanceM) {
                return span.value
            }
            // Early exit: if we've passed the distance, no need to check further
            if (span.startDistanceM > distanceM) break
        }
        return null
    }
}

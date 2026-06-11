package com.curvecall.companion

import com.curvecall.data.routing.GraphHopperRouter
import com.curvecall.engine.types.LatLon
import com.graphhopper.routing.ev.BooleanEncodedValue
import com.graphhopper.routing.ev.EncodedValueLookup
import com.graphhopper.routing.ev.EnumEncodedValue
import com.graphhopper.routing.ev.RoadClass
import com.graphhopper.routing.util.EdgeFilter
import com.graphhopper.storage.BaseGraph
import com.graphhopper.storage.index.LocationIndex
import com.graphhopper.storage.index.Snap
import com.graphhopper.util.EdgeExplorer
import com.graphhopper.util.EdgeIteratorState
import com.graphhopper.util.FetchMode
import com.graphhopper.util.PointList
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Scans the road graph ahead of the driver's current position without a pre-computed route.
 *
 * This is the core component of companion mode. Given a GPS position and heading,
 * it snaps to the nearest road in the GraphHopper graph, then walks forward along
 * edges for 3-5 km, producing a polyline that can be fed into [RouteAnalyzer] for
 * curve detection.
 *
 * Algorithm:
 * 1. Snap GPS to nearest accessible road edge via GraphHopper's LocationIndex
 * 2. Walk forward from the snap point to the end of the current edge
 * 3. At each node, score candidate edges and pick the best continuation
 * 4. Accumulate edge geometries into a single polyline
 * 5. Stop when distance >= [SCAN_DISTANCE_M] or no suitable next edge
 *
 * Fork resolution is score-based (lower = better): the angular difference from the
 * current travel direction, minus bonuses for street-name continuity, same road
 * class, and bigger roads. Edges that are inaccessible in the travel direction
 * (one-way against us) or nearly exactly reversed (true U-turns) are rejected
 * outright. Sharp-but-legal turns such as hairpin apexes survive because the
 * hard rejection threshold is near-180° and name continuity outweighs a moderate
 * angle penalty when the same road continues through the bend.
 */
class RoadScanner(
    private val graphHopperRouter: GraphHopperRouter? = null
) {

    companion object {
        /** Maximum distance to scan ahead in meters. */
        const val SCAN_DISTANCE_M = 5000.0

        /** Distance threshold for rescan: when driver has traveled this far, rescan. */
        const val RESCAN_DISTANCE_M = 2000.0

        /** Maximum acceptable snap distance in meters. Beyond this = off-road. */
        const val MAX_SNAP_DISTANCE_M = 100.0

        /**
         * Hard rejection threshold (degrees) for U-turn candidates. Near-exact
         * reversals are parallel carriageways or turnaround ramps; anything less
         * sharp may be a legitimate hairpin and is left to scoring.
         */
        private const val U_TURN_THRESHOLD_DEG = 170.0

        /** Score bonus (degrees-equivalent) for continuing on the same street name. */
        private const val NAME_CONTINUITY_BONUS = 40.0

        /** Score bonus for staying on the same road class. */
        private const val SAME_CLASS_BONUS = 15.0

        /** Score bonus per road-class rank, mildly preferring bigger roads. */
        private const val CLASS_RANK_WEIGHT = 1.0

        /** Safety limit on edge-walk iterations. */
        private const val MAX_ITERATIONS = 500
    }

    /**
     * Result of a road scan: a polyline ahead plus snap metadata.
     */
    data class ScanResult(
        /** Points forming the road polyline 3-5km ahead. */
        val polyline: List<LatLon>,
        /** GPS position snapped to the nearest road. */
        val snappedPosition: LatLon,
        /** Snap error distance in meters. */
        val distanceFromRoad: Double,
        /** Edge IDs traversed, for change detection between scans. */
        val edgeIds: List<Int>
    )

    /** Encoded-value lookups resolved once per scan from the loaded graph. */
    private class GraphContext(
        val accessEnc: BooleanEncodedValue?,
        val roadClassEnc: EnumEncodedValue<RoadClass>?
    )

    /**
     * Scan the road ahead from the given GPS position.
     *
     * @param lat GPS latitude
     * @param lon GPS longitude
     * @param headingDeg travel heading in degrees (0=north, 90=east)
     * @param speedMs current speed in m/s (used for scan distance tuning)
     * @param profile routing profile ("car" or "motorcycle") for one-way access checks
     * @return scan result with polyline and metadata, or null if off-road
     */
    fun scan(
        lat: Double,
        lon: Double,
        headingDeg: Double,
        speedMs: Double,
        profile: String = "car"
    ): ScanResult? {
        val hopper = graphHopperRouter?.getHopper() ?: return null
        return scan(
            hopper.baseGraph, hopper.locationIndex, hopper.encodingManager,
            lat, lon, headingDeg, speedMs, profile
        )
    }

    /**
     * Scan with explicit graph components. Exposed internally for tests that
     * build a synthetic in-memory graph without a full GraphHopper instance.
     */
    internal fun scan(
        baseGraph: BaseGraph,
        locationIndex: LocationIndex,
        evLookup: EncodedValueLookup,
        lat: Double,
        lon: Double,
        headingDeg: Double,
        speedMs: Double,
        profile: String = "car"
    ): ScanResult? {
        val ctx = resolveGraphContext(evLookup, profile)

        // 1. Snap to the nearest edge that is drivable in at least one direction
        val snapFilter = ctx.accessEnc?.let { enc ->
            EdgeFilter { edge -> edge.get(enc) || edge.getReverse(enc) }
        } ?: EdgeFilter.ALL_EDGES
        val snap = locationIndex.findClosest(lat, lon, snapFilter)
        if (!snap.isValid) return null

        val snapDistM = distanceMeters(lat, lon, snap.snappedPoint.lat, snap.snappedPoint.lon)
        if (snapDistM > MAX_SNAP_DISTANCE_M) return null

        val snappedLat = snap.snappedPoint.lat
        val snappedLon = snap.snappedPoint.lon

        // 2. Start edge walk from the snap point
        val edgeExplorer = baseGraph.createEdgeExplorer()
        val polyline = mutableListOf<LatLon>()
        val edgeIds = mutableListOf<Int>()
        var totalDistance = 0.0
        var currentHeading = headingDeg

        // Add snapped position as the first point
        polyline.add(LatLon(snappedLat, snappedLon))

        // Get the snapped edge and determine which direction to walk
        val snappedEdge = snap.closestEdge
        val snappedEdgeId = snappedEdge.edge
        edgeIds.add(snappedEdgeId)

        // Extract geometry from snap point to the end of the current edge
        val edgeGeom = snappedEdge.fetchWayGeometry(FetchMode.ALL)
        val forwardNode: Int
        val partialPoints: List<LatLon>

        // Determine which end of the edge aligns with our heading, respecting
        // one-way access: walking towards adjNode needs forward access, towards
        // baseNode needs reverse access.
        val baseNodeLat = edgeGeom.getLat(0)
        val baseNodeLon = edgeGeom.getLon(0)
        val adjNodeLat = edgeGeom.getLat(edgeGeom.size() - 1)
        val adjNodeLon = edgeGeom.getLon(edgeGeom.size() - 1)

        val canGoForward = ctx.accessEnc?.let { snappedEdge.get(it) } ?: true
        val canGoBackward = ctx.accessEnc?.let { snappedEdge.getReverse(it) } ?: true

        val diffToAdj = angleDifference(
            currentHeading, bearingDeg(snappedLat, snappedLon, adjNodeLat, adjNodeLon)
        )
        val diffToBase = angleDifference(
            currentHeading, bearingDeg(snappedLat, snappedLon, baseNodeLat, baseNodeLon)
        )

        val walkTowardsAdj = when {
            canGoForward && !canGoBackward -> true
            canGoBackward && !canGoForward -> false
            else -> diffToAdj <= diffToBase
        }

        if (walkTowardsAdj) {
            forwardNode = snappedEdge.adjNode
            partialPoints = extractPointsFromSnapForward(edgeGeom, snap)
        } else {
            forwardNode = snappedEdge.baseNode
            partialPoints = extractPointsFromSnapBackward(edgeGeom, snap)
        }

        // Add partial edge geometry and accumulate distance
        for (pt in partialPoints) {
            if (polyline.isNotEmpty()) {
                val last = polyline.last()
                totalDistance += distanceMeters(last.lat, last.lon, pt.lat, pt.lon)
            }
            polyline.add(pt)
        }

        // Update heading from the last two points
        if (polyline.size >= 2) {
            val prev = polyline[polyline.size - 2]
            val curr = polyline.last()
            currentHeading = bearingDeg(prev.lat, prev.lon, curr.lat, curr.lon)
        }

        // 3. Walk forward through subsequent edges
        var currentNode = forwardNode
        var previousEdgeId = snappedEdgeId
        var previousName = snappedEdge.name ?: ""
        var previousRoadClass = ctx.roadClassEnc?.let { snappedEdge.get(it) }
        var iterations = 0

        while (totalDistance < SCAN_DISTANCE_M && iterations < MAX_ITERATIONS) {
            iterations++

            val nextEdge = pickNextEdge(
                edgeExplorer, baseGraph, currentNode, previousEdgeId, currentHeading,
                previousName, previousRoadClass, ctx
            ) ?: break

            edgeIds.add(nextEdge.edge)

            // Extract geometry for this edge
            val geom = nextEdge.fetchWayGeometry(FetchMode.PILLAR_ONLY)
            val adjLat = baseGraph.getNodeAccess().getLat(nextEdge.adjNode)
            val adjLon = baseGraph.getNodeAccess().getLon(nextEdge.adjNode)

            // Add pillar (intermediate) nodes
            for (i in 0 until geom.size()) {
                val pt = LatLon(geom.getLat(i), geom.getLon(i))
                if (polyline.isNotEmpty()) {
                    val last = polyline.last()
                    totalDistance += distanceMeters(last.lat, last.lon, pt.lat, pt.lon)
                }
                polyline.add(pt)
                if (totalDistance >= SCAN_DISTANCE_M) break
            }

            // Add the end (adj) node
            if (totalDistance < SCAN_DISTANCE_M) {
                val adjPt = LatLon(adjLat, adjLon)
                if (polyline.isNotEmpty()) {
                    val last = polyline.last()
                    totalDistance += distanceMeters(last.lat, last.lon, adjPt.lat, adjPt.lon)
                }
                polyline.add(adjPt)
            }

            // Update heading
            if (polyline.size >= 2) {
                val prev = polyline[polyline.size - 2]
                val curr = polyline.last()
                currentHeading = bearingDeg(prev.lat, prev.lon, curr.lat, curr.lon)
            }

            currentNode = nextEdge.adjNode
            previousEdgeId = nextEdge.edge
            previousName = nextEdge.name ?: ""
            previousRoadClass = ctx.roadClassEnc?.let { nextEdge.get(it) }
        }

        // Need at least 3 points for route analysis
        if (polyline.size < 3) return null

        return ScanResult(
            polyline = polyline,
            snappedPosition = LatLon(snappedLat, snappedLon),
            distanceFromRoad = snapDistM,
            edgeIds = edgeIds
        )
    }

    /**
     * Resolve encoded values for access and road class from the loaded graph.
     * Falls back gracefully when a graph was built without them.
     */
    private fun resolveGraphContext(em: EncodedValueLookup, profile: String): GraphContext {
        val accessEnc = listOf("${profile}_access", "car_access")
            .firstOrNull { em.hasEncodedValue(it) }
            ?.let {
                try {
                    em.getBooleanEncodedValue(it)
                } catch (e: Exception) {
                    null
                }
            }

        val roadClassEnc = if (em.hasEncodedValue(RoadClass.KEY)) {
            try {
                em.getEnumEncodedValue(RoadClass.KEY, RoadClass::class.java)
            } catch (e: Exception) {
                null
            }
        } else null

        return GraphContext(accessEnc, roadClassEnc)
    }

    /**
     * Pick the best next edge at a node using a continuation score.
     *
     * Score = angular difference from current heading, minus bonuses for street-name
     * continuity, same road class, and road-class rank. Lower is better.
     *
     * @return the chosen edge iterator, or null if dead end / no suitable edge
     */
    private fun pickNextEdge(
        edgeExplorer: EdgeExplorer,
        baseGraph: BaseGraph,
        nodeId: Int,
        previousEdgeId: Int,
        currentHeading: Double,
        previousName: String,
        previousRoadClass: RoadClass?,
        ctx: GraphContext
    ): EdgeIteratorState? {
        val nodeAccess = baseGraph.getNodeAccess()
        val nodeLat = nodeAccess.getLat(nodeId)
        val nodeLon = nodeAccess.getLon(nodeId)

        var bestEdge: EdgeIteratorState? = null
        var bestScore = Double.MAX_VALUE
        val iter = edgeExplorer.setBaseNode(nodeId)

        while (iter.next()) {
            // Skip the edge we just came from (avoid immediate U-turn)
            if (iter.edge == previousEdgeId) continue

            // Skip edges that are one-way against our travel direction.
            // The iterator is oriented from this node outward, so get() reads
            // access in the direction we would drive.
            if (ctx.accessEnc != null && !iter.get(ctx.accessEnc)) continue

            // Use pillar nodes if available for more accurate initial bearing
            val geom = iter.fetchWayGeometry(FetchMode.PILLAR_ONLY)
            val targetLat: Double
            val targetLon: Double
            if (geom.size() > 0) {
                targetLat = geom.getLat(0)
                targetLon = geom.getLon(0)
            } else {
                targetLat = nodeAccess.getLat(iter.adjNode)
                targetLon = nodeAccess.getLon(iter.adjNode)
            }

            val bearing = bearingDeg(nodeLat, nodeLon, targetLat, targetLon)
            val angleDiff = angleDifference(currentHeading, bearing)

            // Reject near-exact reversals (parallel carriageway / turnaround)
            if (angleDiff > U_TURN_THRESHOLD_DEG) continue

            var score = angleDiff

            val name = iter.name ?: ""
            if (name.isNotBlank() && name == previousName) {
                score -= NAME_CONTINUITY_BONUS
            }

            val roadClass = ctx.roadClassEnc?.let { iter.get(it) }
            if (roadClass != null) {
                if (roadClass == previousRoadClass) score -= SAME_CLASS_BONUS
                score -= roadClassRank(roadClass) * CLASS_RANK_WEIGHT
            }

            if (score < bestScore) {
                bestScore = score
                bestEdge = iter.detach(false)
            }
        }

        return bestEdge
    }

    /**
     * Rank a road class: higher = bigger road. Mirrors typical OSM hierarchy.
     */
    private fun roadClassRank(roadClass: RoadClass): Int = when (roadClass) {
        RoadClass.MOTORWAY -> 10
        RoadClass.TRUNK -> 9
        RoadClass.PRIMARY -> 8
        RoadClass.SECONDARY -> 7
        RoadClass.TERTIARY -> 6
        RoadClass.UNCLASSIFIED -> 5
        RoadClass.RESIDENTIAL -> 4
        RoadClass.LIVING_STREET -> 3
        RoadClass.SERVICE -> 2
        RoadClass.TRACK -> 1
        else -> 0
    }

    /**
     * Extract points from the snap position forward to the end of edge geometry.
     */
    private fun extractPointsFromSnapForward(geom: PointList, snap: Snap): List<LatLon> {
        val points = mutableListOf<LatLon>()
        val snapLat = snap.snappedPoint.lat
        val snapLon = snap.snappedPoint.lon

        // Find the closest segment in the geometry to determine where to start
        var bestIdx = 0
        var bestDist = Double.MAX_VALUE
        for (i in 0 until geom.size() - 1) {
            val dist = pointToSegmentDistance(
                snapLat, snapLon,
                geom.getLat(i), geom.getLon(i),
                geom.getLat(i + 1), geom.getLon(i + 1)
            )
            if (dist < bestDist) {
                bestDist = dist
                bestIdx = i
            }
        }

        // Add points from after the snap position to the end
        for (i in (bestIdx + 1) until geom.size()) {
            points.add(LatLon(geom.getLat(i), geom.getLon(i)))
        }
        return points
    }

    /**
     * Extract points from the snap position backward to the start of edge geometry.
     */
    private fun extractPointsFromSnapBackward(geom: PointList, snap: Snap): List<LatLon> {
        val points = mutableListOf<LatLon>()
        val snapLat = snap.snappedPoint.lat
        val snapLon = snap.snappedPoint.lon

        // Find the closest segment
        var bestIdx = 0
        var bestDist = Double.MAX_VALUE
        for (i in 0 until geom.size() - 1) {
            val dist = pointToSegmentDistance(
                snapLat, snapLon,
                geom.getLat(i), geom.getLon(i),
                geom.getLat(i + 1), geom.getLon(i + 1)
            )
            if (dist < bestDist) {
                bestDist = dist
                bestIdx = i
            }
        }

        // Add points from before the snap position to the start, reversed
        for (i in bestIdx downTo 0) {
            points.add(LatLon(geom.getLat(i), geom.getLon(i)))
        }
        return points
    }

    // -- Geometry helpers --

    /** Compute distance between two lat/lon points in meters (Haversine). */
    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    /** Compute bearing in degrees from point 1 to point 2. */
    private fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
        val bearing = Math.toDegrees(atan2(y, x))
        return (bearing + 360) % 360
    }

    /** Compute the smallest angular difference between two bearings (0-180). */
    private fun angleDifference(bearing1: Double, bearing2: Double): Double {
        var diff = abs(bearing1 - bearing2) % 360
        if (diff > 180) diff = 360 - diff
        return diff
    }

    /** Distance from a point to a line segment (approximate, in coordinate units). */
    private fun pointToSegmentDistance(
        px: Double, py: Double,
        ax: Double, ay: Double,
        bx: Double, by: Double
    ): Double {
        val dx = bx - ax
        val dy = by - ay
        if (dx == 0.0 && dy == 0.0) {
            return distanceMeters(px, py, ax, ay)
        }
        val t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)
        val clampedT = t.coerceIn(0.0, 1.0)
        val projX = ax + clampedT * dx
        val projY = ay + clampedT * dy
        return distanceMeters(px, py, projX, projY)
    }
}

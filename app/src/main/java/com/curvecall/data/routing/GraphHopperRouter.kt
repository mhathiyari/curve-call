package com.curvecall.data.routing

import android.content.Context
import com.curvecall.engine.types.LatLon
import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.config.CHProfile
import com.graphhopper.config.Profile
import com.graphhopper.util.GHUtility
import java.io.File
import java.util.Locale

/**
 * On-device routing via GraphHopper. Loads pre-processed graph directories
 * (built server-side from OSM PBF extracts) and computes routes locally.
 *
 * Usage:
 * 1. Copy a graph directory to app internal storage
 * 2. Call [loadGraph] with the directory
 * 3. Call [route] to compute a route
 * 4. Feed [RouteResult.points] into RouteAnalyzer
 */
class GraphHopperRouter(private val context: Context) {

    private var hopper: GraphHopper? = null
    private var loadedGraphDir: String? = null

    val isGraphLoaded: Boolean get() = hopper != null

    /**
     * Load a pre-processed GraphHopper graph from the given directory.
     * The directory must be under app internal storage and contain files
     * produced by GraphHopper's importOrLoad() on a server.
     *
     * @param graphDir directory containing the pre-built graph files
     * @throws IllegalStateException if the graph fails to load
     */
    fun loadGraph(graphDir: File) {
        // Close any previously loaded graph
        close()

        require(graphDir.exists() && graphDir.isDirectory) {
            "Graph directory does not exist: ${graphDir.absolutePath}"
        }

        val gh = GraphHopper().apply {
            graphHopperLocation = graphDir.absolutePath
            setAllowWrites(false)

            // Profiles must match what was used during graph preparation
            setProfiles(
                Profile("car").setCustomModel(
                    GHUtility.loadCustomModelFromJar("car.json")
                ),
                Profile("motorcycle").setCustomModel(
                    GHUtility.loadCustomModelFromJar("motorcycle.json")
                )
            )
            chPreparationHandler.setCHProfiles(
                CHProfile("car"),
                CHProfile("motorcycle")
            )
        }

        check(gh.load()) {
            "Failed to load GraphHopper graph from: ${graphDir.absolutePath}"
        }

        hopper = gh
        loadedGraphDir = graphDir.absolutePath
    }

    /**
     * Compute a route between two points.
     *
     * @param from starting coordinate
     * @param to destination coordinate
     * @param profile routing profile — "car" or "motorcycle"
     * @return route result with coordinates suitable for RouteAnalyzer
     * @throws IllegalStateException if no graph is loaded
     * @throws RoutingException if routing fails
     */
    fun route(from: LatLon, to: LatLon, profile: String = "car"): RouteResult {
        val gh = hopper ?: throw IllegalStateException("No graph loaded. Call loadGraph() first.")

        require(profile == "car" || profile == "motorcycle") {
            "Unknown profile: $profile. Use \"car\" or \"motorcycle\"."
        }

        val request = GHRequest(from.lat, from.lon, to.lat, to.lon)
            .setProfile(profile)
            .setLocale(Locale.US)
            .setPathDetails(listOf("road_class", "surface", "max_speed"))

        // Disable CH to allow path detail extraction (flexible routing ~1-3s vs CH <100ms)
        request.putHint("ch.disable", true)

        val response = gh.route(request)

        if (response.hasErrors()) {
            throw RoutingException(
                "Routing failed: ${response.errors.joinToString { it.message ?: it.toString() }}"
            )
        }

        val path = response.best
        val pointList = path.points
        val points = ArrayList<LatLon>(pointList.size())
        for (i in 0 until pointList.size()) {
            points.add(LatLon(pointList.getLat(i), pointList.getLon(i)))
        }

        // Extract road metadata from path details and instructions
        val metadata = RouteMetadataExtractor.extract(path)

        return RouteResult(
            points = points,
            distanceMeters = path.distance,
            timeMillis = path.time,
            metadata = metadata,
        )
    }

    /**
     * Release resources. Safe to call multiple times.
     */
    fun close() {
        hopper?.close()
        hopper = null
        loadedGraphDir = null
    }

    /** Directory where graph data should be stored on this device. */
    fun graphStorageDir(): File = File(context.filesDir, GRAPHS_DIR_NAME)

    companion object {
        const val GRAPHS_DIR_NAME = "graphhopper-graphs"
    }
}

class RoutingException(message: String) : RuntimeException(message)

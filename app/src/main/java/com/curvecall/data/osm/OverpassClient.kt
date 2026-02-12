package com.curvecall.data.osm

import com.curvecall.engine.types.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject

/**
 * Client for querying the Overpass API to retrieve OSM surface tags
 * along a route corridor. Used in motorcycle mode for surface warnings.
 *
 * Surface data is pre-fetched at GPX load time (not during real-time driving)
 * and cached locally. Fails gracefully when offline.
 *
 * PRD Section 9.1: "Pre-fetch surface data at GPX load time. Cache locally.
 * If network unavailable, silently skip surface warnings."
 */
class OverpassClient @Inject constructor(
    private val httpClient: OkHttpClient
) {

    companion object {
        private const val OVERPASS_API_URL = "https://overpass-api.de/api/interpreter"

        /** Width of the corridor around the route to query for surface tags, in meters. */
        private const val CORRIDOR_WIDTH_METERS = 50

        /** Maximum number of route points to include in a single query to avoid timeout. */
        private const val MAX_POINTS_PER_QUERY = 200

        /** Minimum distance between sampled points for the query polygon. */
        private const val SAMPLE_SPACING_METERS = 100.0
    }

    /**
     * A surface segment along the route.
     *
     * @param surface The OSM surface tag value (e.g., "asphalt", "gravel", "cobblestone")
     * @param startPoint Approximate start of this surface section
     * @param endPoint Approximate end of this surface section
     */
    data class SurfaceSegment(
        val surface: String,
        val startPoint: LatLon,
        val endPoint: LatLon
    )

    /** In-memory cache of surface data keyed by a hash of the route points. */
    private val surfaceCache = mutableMapOf<Int, List<SurfaceSegment>>()

    /**
     * Query the Overpass API for surface tags along a route corridor.
     *
     * @param routePoints The full route as a list of LatLon coordinates
     * @return List of SurfaceSegment describing surface changes, or empty list on failure
     */
    suspend fun fetchSurfaceData(routePoints: List<LatLon>): List<SurfaceSegment> {
        val cacheKey = routePoints.hashCode()
        surfaceCache[cacheKey]?.let { return it }

        return try {
            val result = withContext(Dispatchers.IO) {
                val sampledPoints = sampleRoutePoints(routePoints)
                val query = buildOverpassQuery(sampledPoints)
                val response = executeQuery(query)
                parseSurfaceResponse(response)
            }
            surfaceCache[cacheKey] = result
            result
        } catch (e: Exception) {
            // Graceful failure: return empty list, don't crash
            emptyList()
        }
    }

    /**
     * Sample the route to reduce the number of points in the Overpass query.
     * Takes every Nth point such that spacing is approximately SAMPLE_SPACING_METERS.
     */
    private fun sampleRoutePoints(points: List<LatLon>): List<LatLon> {
        if (points.size <= MAX_POINTS_PER_QUERY) return points

        val step = (points.size / MAX_POINTS_PER_QUERY).coerceAtLeast(1)
        return points.filterIndexed { index, _ -> index % step == 0 }
    }

    /**
     * Build an Overpass QL query that retrieves all ways with surface tags
     * within a corridor around the given route points.
     *
     * Uses the `around` filter to find ways near the route polyline.
     */
    private fun buildOverpassQuery(points: List<LatLon>): String {
        val coordList = points.joinToString(",") { "${it.lat},${it.lon}" }

        return """
            [out:json][timeout:30];
            way["highway"]["surface"](around:$CORRIDOR_WIDTH_METERS,$coordList);
            out body;
            >;
            out skel qt;
        """.trimIndent()
    }

    /**
     * Execute an Overpass QL query via HTTP POST.
     */
    private fun executeQuery(query: String): String {
        val requestBody = "data=$query".toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val request = Request.Builder()
            .url(OVERPASS_API_URL)
            .post(requestBody)
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw OverpassException("Overpass API returned ${response.code}: ${response.message}")
        }

        return response.body?.string()
            ?: throw OverpassException("Empty response from Overpass API")
    }

    /**
     * Parse the JSON response from Overpass to extract surface segments.
     */
    private fun parseSurfaceResponse(jsonResponse: String): List<SurfaceSegment> {
        val segments = mutableListOf<SurfaceSegment>()
        val json = JSONObject(jsonResponse)
        val elements = json.getJSONArray("elements")

        // First pass: collect all nodes for coordinate lookup
        val nodeMap = mutableMapOf<Long, LatLon>()
        for (i in 0 until elements.length()) {
            val element = elements.getJSONObject(i)
            if (element.getString("type") == "node") {
                val id = element.getLong("id")
                val lat = element.getDouble("lat")
                val lon = element.getDouble("lon")
                try {
                    nodeMap[id] = LatLon(lat, lon)
                } catch (e: IllegalArgumentException) {
                    // Skip invalid coordinates
                }
            }
        }

        // Second pass: extract ways with surface tags
        for (i in 0 until elements.length()) {
            val element = elements.getJSONObject(i)
            if (element.getString("type") != "way") continue

            val tags = element.optJSONObject("tags") ?: continue
            val surface = tags.optString("surface", "")
            if (surface.isBlank()) continue

            val nodes = element.optJSONArray("nodes") ?: continue
            if (nodes.length() < 2) continue

            val firstNodeId = nodes.getLong(0)
            val lastNodeId = nodes.getLong(nodes.length() - 1)

            val startPoint = nodeMap[firstNodeId] ?: continue
            val endPoint = nodeMap[lastNodeId] ?: continue

            segments.add(
                SurfaceSegment(
                    surface = surface,
                    startPoint = startPoint,
                    endPoint = endPoint
                )
            )
        }

        return segments
    }

    /**
     * Check if a surface type is considered non-paved and potentially dangerous
     * for motorcycles.
     */
    fun isNonPavedSurface(surface: String): Boolean {
        val nonPaved = setOf(
            "unpaved", "gravel", "dirt", "sand", "grass", "mud",
            "ground", "earth", "fine_gravel", "compacted",
            "cobblestone", "sett", "unhewn_cobblestone",
            "pebblestone", "wood"
        )
        return surface.lowercase() in nonPaved
    }

    /**
     * Generate a human-readable surface warning string for narration.
     */
    fun surfaceWarningText(surface: String): String {
        return when (surface.lowercase()) {
            "gravel", "fine_gravel" -> "Caution, gravel surface ahead"
            "dirt", "earth", "ground" -> "Caution, unpaved section ahead"
            "cobblestone", "sett", "unhewn_cobblestone" -> "Surface changes to cobblestone"
            "sand" -> "Caution, sandy surface ahead"
            "grass" -> "Caution, grass surface ahead"
            "mud" -> "Caution, muddy surface ahead"
            "wood" -> "Caution, wooden surface ahead"
            "compacted" -> "Caution, compacted gravel surface ahead"
            "pebblestone" -> "Caution, loose pebble surface ahead"
            else -> "Caution, surface changes to $surface"
        }
    }

    /**
     * Clear the in-memory cache.
     */
    fun clearCache() {
        surfaceCache.clear()
    }
}

class OverpassException(message: String) : Exception(message)

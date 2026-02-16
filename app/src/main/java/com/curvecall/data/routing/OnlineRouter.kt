package com.curvecall.data.routing

import com.curvecall.engine.types.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Online routing fallback via OSRM's public API.
 *
 * Used when no offline GraphHopper region covers the destination.
 * Returns the same [RouteResult] as [GraphHopperRouter] so the
 * downstream pipeline (analysis, session, tile caching) is unaffected.
 *
 * OSRM only has a "driving" profile — motorcycle routes use the same
 * road network, which is fine for curve detection purposes.
 */
class OnlineRouter(private val okHttpClient: OkHttpClient) {

    suspend fun route(from: LatLon, to: LatLon): RouteResult = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/${from.lon},${from.lat};${to.lon},${to.lat}" +
            "?overview=full&geometries=geojson"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw RoutingException("Online routing failed: HTTP ${response.code}")
        }

        val body = response.body?.string()
            ?: throw RoutingException("Empty response from routing server")

        val json = JSONObject(body)
        val code = json.getString("code")
        if (code != "Ok") {
            throw RoutingException("Online routing error: $code")
        }

        val route = json.getJSONArray("routes").getJSONObject(0)
        val geometry = route.getJSONObject("geometry")
        val coordinates = geometry.getJSONArray("coordinates")

        val points = ArrayList<LatLon>(coordinates.length())
        for (i in 0 until coordinates.length()) {
            val coord = coordinates.getJSONArray(i)
            // GeoJSON is [lon, lat]
            points.add(LatLon(coord.getDouble(1), coord.getDouble(0)))
        }

        if (points.size < 2) {
            throw RoutingException("Route has insufficient points")
        }

        RouteResult(
            points = points,
            distanceMeters = route.getDouble("distance"),
            timeMillis = (route.getDouble("duration") * 1000).toLong()
        )
    }

    companion object {
        private const val BASE_URL = "https://router.project-osrm.org/route/v1/driving"
    }
}

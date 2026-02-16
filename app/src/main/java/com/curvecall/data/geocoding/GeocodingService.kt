package com.curvecall.data.geocoding

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Geocoding service backed by Nominatim (OpenStreetMap).
 *
 * Provides forward search (text → coordinates) and reverse geocoding
 * (coordinates → display name). Runs all network calls on [Dispatchers.IO].
 *
 * Usage policy: Nominatim requires a descriptive User-Agent header
 * and limits requests to ~1/sec. The debounced search in the UI layer
 * naturally satisfies the rate limit.
 */
class GeocodingService(private val okHttpClient: OkHttpClient) {

    /**
     * A single geocoding search result.
     *
     * @param displayName Human-readable location name (e.g. "Blue Ridge Parkway, NC")
     * @param lat Latitude in decimal degrees (WGS-84)
     * @param lon Longitude in decimal degrees (WGS-84)
     * @param type Location type from Nominatim (e.g. "city", "road", "amenity")
     */
    data class SearchResult(
        val displayName: String,
        val lat: Double,
        val lon: Double,
        val type: String
    )

    companion object {
        private const val BASE_URL = "https://nominatim.openstreetmap.org"
        private const val USER_AGENT = "CurveCall/1.0"
        private const val MAX_RESULTS = 8
    }

    /**
     * Search for locations matching the given query string.
     *
     * @param query Free-form search text (e.g. "Deals Gap, NC" or "Tail of the Dragon")
     * @return List of matching results, up to [MAX_RESULTS]. Empty list on error.
     */
    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$BASE_URL/search?q=$encoded&format=json&limit=$MAX_RESULTS&addressdetails=0"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        try {
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyList()

            val body = response.body?.string() ?: return@withContext emptyList()
            val jsonArray = JSONArray(body)
            val results = mutableListOf<SearchResult>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                results.add(
                    SearchResult(
                        displayName = obj.optString("display_name", "Unknown"),
                        lat = obj.optString("lat", "0").toDoubleOrNull() ?: 0.0,
                        lon = obj.optString("lon", "0").toDoubleOrNull() ?: 0.0,
                        type = obj.optString("type", "unknown")
                    )
                )
            }

            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Reverse geocode a coordinate pair into a human-readable display name.
     *
     * @param lat Latitude in decimal degrees
     * @param lon Longitude in decimal degrees
     * @return Display name string, or null if the lookup fails
     */
    suspend fun reverseGeocode(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/reverse?lat=$lat&lon=$lon&format=json&zoom=16"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        try {
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)
            json.optString("display_name", null)
        } catch (e: Exception) {
            null
        }
    }
}

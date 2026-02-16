package com.curvecall.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.curvecall.engine.types.DrivingMode
import com.curvecall.engine.types.SpeedUnit
import com.curvecall.narration.types.TimingProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "curvecall_prefs")

/**
 * DataStore-backed storage for all user preferences (PRD Section 8.1).
 *
 * Each preference is exposed as a Kotlin Flow for reactive UI updates
 * and has a suspend setter for coroutine-context writes.
 *
 * Default values are mode-aware: some defaults change when switching
 * between Car and Motorcycle mode.
 */
class UserPreferences @Inject constructor(
    private val context: Context
) {
    private val dataStore get() = context.dataStore

    // -- Preference Keys --

    private object Keys {
        val MODE = stringPreferencesKey("driving_mode")
        val UNITS = stringPreferencesKey("speed_units")
        val VERBOSITY = intPreferencesKey("verbosity")
        val LATERAL_G = doublePreferencesKey("lateral_g")
        val LATERAL_G_CUSTOM = booleanPreferencesKey("lateral_g_custom")
        val TIMING_PROFILE = stringPreferencesKey("timing_profile")
        val TTS_SPEECH_RATE = floatPreferencesKey("tts_speech_rate")
        val TTS_VOICE_NAME = stringPreferencesKey("tts_voice_name")
        val NARRATE_STRAIGHTS = booleanPreferencesKey("narrate_straights")
        val AUDIO_DUCKING = booleanPreferencesKey("audio_ducking")
        val LEAN_ANGLE_NARRATION = booleanPreferencesKey("lean_angle_narration")
        val SURFACE_WARNINGS = booleanPreferencesKey("surface_warnings")
        val DISCLAIMER_ACCEPTED = booleanPreferencesKey("disclaimer_accepted")
        val RECENT_ROUTES = stringSetPreferencesKey("recent_routes")
        val RECENT_DESTINATIONS = stringPreferencesKey("recent_destinations")
    }

    // -- Driving Mode --

    val drivingMode: Flow<DrivingMode> = dataStore.data.map { prefs ->
        when (prefs[Keys.MODE]) {
            "MOTORCYCLE" -> DrivingMode.MOTORCYCLE
            else -> DrivingMode.CAR
        }
    }

    suspend fun setDrivingMode(mode: DrivingMode) {
        dataStore.edit { prefs ->
            prefs[Keys.MODE] = mode.name

            // Auto-adjust defaults when mode changes, unless user has customized them
            if (prefs[Keys.LATERAL_G_CUSTOM] != true) {
                prefs[Keys.LATERAL_G] = when (mode) {
                    DrivingMode.CAR -> 0.35
                    DrivingMode.MOTORCYCLE -> 0.25
                }
            }
            // Timing profile is mode-independent (Relaxed/Normal/Sporty)
        }
    }

    // -- Speed Units --

    val speedUnits: Flow<SpeedUnit> = dataStore.data.map { prefs ->
        when (prefs[Keys.UNITS]) {
            "MPH" -> SpeedUnit.MPH
            else -> SpeedUnit.KMH
        }
    }

    suspend fun setSpeedUnits(units: SpeedUnit) {
        dataStore.edit { it[Keys.UNITS] = units.name }
    }

    // -- Verbosity --

    val verbosity: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.VERBOSITY] ?: 2  // Default: Standard
    }

    suspend fun setVerbosity(level: Int) {
        require(level in 1..3) { "Verbosity must be 1 (Minimal), 2 (Standard), or 3 (Detailed)" }
        dataStore.edit { it[Keys.VERBOSITY] = level }
    }

    // -- Lateral G Threshold --

    val lateralG: Flow<Double> = dataStore.data.map { prefs ->
        prefs[Keys.LATERAL_G] ?: when (prefs[Keys.MODE]) {
            "MOTORCYCLE" -> 0.25
            else -> 0.35
        }
    }

    suspend fun setLateralG(value: Double) {
        require(value in 0.20..0.50) { "Lateral G must be between 0.20 and 0.50" }
        dataStore.edit {
            it[Keys.LATERAL_G] = value
            it[Keys.LATERAL_G_CUSTOM] = true
        }
    }

    // -- Timing Profile --

    val timingProfile: Flow<TimingProfile> = dataStore.data.map { prefs ->
        when (prefs[Keys.TIMING_PROFILE]) {
            "RELAXED" -> TimingProfile.RELAXED
            "SPORTY" -> TimingProfile.SPORTY
            else -> TimingProfile.NORMAL
        }
    }

    suspend fun setTimingProfile(profile: TimingProfile) {
        dataStore.edit { it[Keys.TIMING_PROFILE] = profile.name }
    }

    // -- TTS Speech Rate --

    val ttsSpeechRate: Flow<Float> = dataStore.data.map { prefs ->
        prefs[Keys.TTS_SPEECH_RATE] ?: 1.0f
    }

    suspend fun setTtsSpeechRate(rate: Float) {
        require(rate in 0.5f..2.0f) { "TTS speech rate must be between 0.5x and 2.0x" }
        dataStore.edit { it[Keys.TTS_SPEECH_RATE] = rate }
    }

    // -- TTS Voice Name --

    val ttsVoiceName: Flow<String?> = dataStore.data.map { prefs ->
        prefs[Keys.TTS_VOICE_NAME]
    }

    suspend fun setTtsVoiceName(name: String) {
        dataStore.edit { it[Keys.TTS_VOICE_NAME] = name }
    }

    // -- Narrate Straights --

    val narrateStraights: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.NARRATE_STRAIGHTS] ?: false
    }

    suspend fun setNarrateStraights(enabled: Boolean) {
        dataStore.edit { it[Keys.NARRATE_STRAIGHTS] = enabled }
    }

    // -- Audio Ducking --

    val audioDucking: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.AUDIO_DUCKING] ?: true
    }

    suspend fun setAudioDucking(enabled: Boolean) {
        dataStore.edit { it[Keys.AUDIO_DUCKING] = enabled }
    }

    // -- Lean Angle Narration (Motorcycle Only) --

    val leanAngleNarration: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.LEAN_ANGLE_NARRATION] ?: true
    }

    suspend fun setLeanAngleNarration(enabled: Boolean) {
        dataStore.edit { it[Keys.LEAN_ANGLE_NARRATION] = enabled }
    }

    // -- Surface Warnings (Motorcycle Only) --

    val surfaceWarnings: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.SURFACE_WARNINGS] ?: true
    }

    suspend fun setSurfaceWarnings(enabled: Boolean) {
        dataStore.edit { it[Keys.SURFACE_WARNINGS] = enabled }
    }

    // -- Disclaimer Accepted --

    val disclaimerAccepted: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.DISCLAIMER_ACCEPTED] ?: false
    }

    suspend fun setDisclaimerAccepted(accepted: Boolean) {
        dataStore.edit { it[Keys.DISCLAIMER_ACCEPTED] = accepted }
    }

    /**
     * Blocking variant for use from Activity.onCreate where a coroutine scope
     * may not be conveniently available. Use sparingly.
     */
    fun setDisclaimerAcceptedBlocking(accepted: Boolean) {
        runBlocking { setDisclaimerAccepted(accepted) }
    }

    // -- Recent Routes --

    val recentRoutes: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[Keys.RECENT_ROUTES] ?: emptySet()
    }

    /**
     * Add a route URI to the recent routes list. Keeps at most 10 entries.
     */
    suspend fun addRecentRoute(routeUri: String) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.RECENT_ROUTES]?.toMutableSet() ?: mutableSetOf()
            current.add(routeUri)
            // Limit to 10 most recent
            if (current.size > 10) {
                val trimmed = current.toList().takeLast(10).toSet()
                prefs[Keys.RECENT_ROUTES] = trimmed
            } else {
                prefs[Keys.RECENT_ROUTES] = current
            }
        }
    }

    suspend fun removeRecentRoute(routeUri: String) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.RECENT_ROUTES]?.toMutableSet() ?: mutableSetOf()
            current.remove(routeUri)
            prefs[Keys.RECENT_ROUTES] = current
        }
    }

    // -- Recent Destinations (for Destination Picker) --

    /**
     * A saved destination (recent or favorite) for the destination picker.
     * Stored as JSON in DataStore.
     */
    data class SavedDestination(
        val name: String,
        val lat: Double,
        val lon: Double,
        val timestamp: Long,
        val isFavorite: Boolean = false
    )

    /**
     * Flow of all saved destinations (both recent and favorites).
     * Parsed from a JSON string stored in DataStore.
     */
    val recentDestinations: Flow<List<SavedDestination>> = dataStore.data.map { prefs ->
        val json = prefs[Keys.RECENT_DESTINATIONS] ?: return@map emptyList()
        parseDestinationsJson(json)
    }

    /**
     * Add a destination to the recent destinations list.
     * If the destination already exists (by lat/lon), it is updated with the new timestamp.
     * Keeps at most 20 entries.
     */
    suspend fun addRecentDestination(name: String, lat: Double, lon: Double) {
        dataStore.edit { prefs ->
            val json = prefs[Keys.RECENT_DESTINATIONS] ?: "[]"
            val destinations = parseDestinationsJson(json).toMutableList()

            // Remove existing entry with same coordinates (if any)
            destinations.removeAll { isSameLocation(it.lat, it.lon, lat, lon) }

            // Add new entry at the front
            destinations.add(
                0,
                SavedDestination(
                    name = name,
                    lat = lat,
                    lon = lon,
                    timestamp = System.currentTimeMillis(),
                    isFavorite = false
                )
            )

            // Keep at most 20 (but never remove favorites)
            val favorites = destinations.filter { it.isFavorite }
            val recents = destinations.filter { !it.isFavorite }
            val trimmedRecents = recents.take(20)
            val merged = (favorites + trimmedRecents).distinctBy { "${it.lat},${it.lon}" }

            prefs[Keys.RECENT_DESTINATIONS] = destinationsToJson(merged)
        }
    }

    /**
     * Toggle the favorite status of a destination identified by lat/lon.
     */
    suspend fun toggleFavorite(lat: Double, lon: Double) {
        dataStore.edit { prefs ->
            val json = prefs[Keys.RECENT_DESTINATIONS] ?: "[]"
            val destinations = parseDestinationsJson(json).toMutableList()

            val index = destinations.indexOfFirst { isSameLocation(it.lat, it.lon, lat, lon) }
            if (index >= 0) {
                val dest = destinations[index]
                destinations[index] = dest.copy(isFavorite = !dest.isFavorite)
                prefs[Keys.RECENT_DESTINATIONS] = destinationsToJson(destinations)
            }
        }
    }

    /**
     * Parse a JSON string into a list of [SavedDestination].
     */
    private fun parseDestinationsJson(json: String): List<SavedDestination> {
        return try {
            val array = JSONArray(json)
            val results = mutableListOf<SavedDestination>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                results.add(
                    SavedDestination(
                        name = obj.optString("name", "Unknown"),
                        lat = obj.optDouble("lat", 0.0),
                        lon = obj.optDouble("lon", 0.0),
                        timestamp = obj.optLong("timestamp", 0L),
                        isFavorite = obj.optBoolean("isFavorite", false)
                    )
                )
            }
            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Serialize a list of [SavedDestination] into a JSON string.
     */
    private fun destinationsToJson(destinations: List<SavedDestination>): String {
        val array = JSONArray()
        for (dest in destinations) {
            val obj = JSONObject().apply {
                put("name", dest.name)
                put("lat", dest.lat)
                put("lon", dest.lon)
                put("timestamp", dest.timestamp)
                put("isFavorite", dest.isFavorite)
            }
            array.put(obj)
        }
        return array.toString()
    }

    /**
     * Check if two lat/lon pairs refer to the same location (within ~11m tolerance).
     */
    private fun isSameLocation(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Boolean {
        return Math.abs(lat1 - lat2) < 0.0001 && Math.abs(lon1 - lon2) < 0.0001
    }
}

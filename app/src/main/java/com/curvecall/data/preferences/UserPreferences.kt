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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
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
        val LOOK_AHEAD_TIME = doublePreferencesKey("look_ahead_time")
        val LOOK_AHEAD_CUSTOM = booleanPreferencesKey("look_ahead_custom")
        val TTS_SPEECH_RATE = floatPreferencesKey("tts_speech_rate")
        val TTS_VOICE_NAME = stringPreferencesKey("tts_voice_name")
        val NARRATE_STRAIGHTS = booleanPreferencesKey("narrate_straights")
        val AUDIO_DUCKING = booleanPreferencesKey("audio_ducking")
        val LEAN_ANGLE_NARRATION = booleanPreferencesKey("lean_angle_narration")
        val SURFACE_WARNINGS = booleanPreferencesKey("surface_warnings")
        val DISCLAIMER_ACCEPTED = booleanPreferencesKey("disclaimer_accepted")
        val RECENT_ROUTES = stringSetPreferencesKey("recent_routes")
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
            if (prefs[Keys.LOOK_AHEAD_CUSTOM] != true) {
                prefs[Keys.LOOK_AHEAD_TIME] = when (mode) {
                    DrivingMode.CAR -> 8.0
                    DrivingMode.MOTORCYCLE -> 10.0
                }
            }
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

    // -- Look-Ahead Time --

    val lookAheadTime: Flow<Double> = dataStore.data.map { prefs ->
        prefs[Keys.LOOK_AHEAD_TIME] ?: when (prefs[Keys.MODE]) {
            "MOTORCYCLE" -> 10.0
            else -> 8.0
        }
    }

    suspend fun setLookAheadTime(value: Double) {
        require(value in 5.0..15.0) { "Look-ahead time must be between 5 and 15 seconds" }
        dataStore.edit {
            it[Keys.LOOK_AHEAD_TIME] = value
            it[Keys.LOOK_AHEAD_CUSTOM] = true
        }
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
}

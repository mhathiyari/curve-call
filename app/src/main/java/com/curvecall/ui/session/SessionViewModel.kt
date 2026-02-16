package com.curvecall.ui.session

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.curvecall.data.location.LocationProvider
import com.curvecall.data.preferences.UserPreferences
import com.curvecall.data.session.SessionDataHolder
import com.curvecall.engine.MapMatcher
import com.curvecall.engine.types.CurveSegment
import com.curvecall.engine.types.DrivingMode
import com.curvecall.engine.types.LatLon
import com.curvecall.engine.types.RouteSegment
import com.curvecall.engine.types.Severity
import com.curvecall.engine.types.SpeedUnit
import com.curvecall.narration.NarrationManager
import com.curvecall.narration.TtsEngine
import com.curvecall.narration.types.NarrationConfig
import com.curvecall.narration.types.NarrationEvent
import com.curvecall.narration.types.TimingProfile
import com.curvecall.service.SessionForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the active driving session screen.
 *
 * Wires together:
 * - GPS location flow -> MapMatcher -> NarrationManager -> TtsEngine
 * - Exposes upcoming curves, current narration, speed, GPS position/bearing,
 *   and session state as StateFlows for the Compose UI.
 *
 * Handles play/pause/stop lifecycle for the narration session.
 *
 * MapMatcher is created locally per-route (not injected via DI) because it
 * requires route-specific data (points and segments) available only at runtime.
 *
 * NarrationManager communicates via a NarrationListener callback pattern,
 * not return values. This ViewModel implements the listener to bridge
 * narration events to TTS and UI updates.
 */
@HiltViewModel
class SessionViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val locationProvider: LocationProvider,
    private val narrationManager: NarrationManager,
    private val ttsEngine: TtsEngine,
    private val userPreferences: UserPreferences,
    private val sessionDataHolder: SessionDataHolder
) : ViewModel(), NarrationManager.NarrationListener {

    /**
     * Session state enumeration.
     */
    enum class SessionState {
        /** Session not started or fully stopped. */
        IDLE,
        /** Actively tracking GPS and narrating. */
        PLAYING,
        /** Temporarily paused (user-initiated or off-route). */
        PAUSED,
        /** Session complete or stopped by user. */
        STOPPED
    }

    /**
     * Represents the full UI state of the session screen.
     */
    data class SessionUiState(
        val sessionState: SessionState = SessionState.IDLE,
        val currentSpeedKmh: Double = 0.0,
        val currentSpeedMph: Double = 0.0,
        val lastNarrationText: String = "",
        val upcomingCurves: List<UpcomingCurve> = emptyList(),
        val activeAdvisorySpeedKmh: Double? = null,
        val activeAdvisorySpeedMph: Double? = null,
        val activeLeanAngle: Double? = null,
        val isOffRoute: Boolean = false,
        val offRouteDistanceM: Double = 0.0,
        val isSparseDataWarning: Boolean = false,
        val isMuted: Boolean = false,
        val verbosity: Int = 2,
        val isMotorcycleMode: Boolean = false,
        val usesMph: Boolean = false,
        val routeProgressPercent: Float = 0f,
        val distanceRemainingM: Double = 0.0,
        // Map state — updated on each GPS tick
        val currentLatitude: Double = 0.0,
        val currentLongitude: Double = 0.0,
        val currentBearing: Float = 0f,
        val currentAccuracy: Float = 0f,
        // Dynamic zoom — computed from speed + curve proximity + severity
        val targetZoom: Double = 16.0
    )

    /**
     * An upcoming curve with display-ready information.
     */
    data class UpcomingCurve(
        val curveSegment: CurveSegment,
        val distanceToM: Double,
        val briefDescription: String
    )

    private val _uiState = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    /** The analyzed route segments from the engine (exposed for map overlay). */
    var routeSegments: List<RouteSegment> = emptyList()
        private set

    /** The original route points for map matching. */
    private var routePoints: List<LatLon> = emptyList()

    /** The interpolated points for map overlay rendering. */
    var interpolatedPoints: List<LatLon> = emptyList()
        private set

    /** Total route distance in meters. */
    private var totalRouteDistanceM: Double = 0.0

    /** MapMatcher created per-route after route data is loaded. */
    private var mapMatcher: MapMatcher? = null

    /** Job for GPS location collection. */
    private var locationJob: Job? = null

    /** Tracks whether sparse data warning has been delivered for the current segment. */
    private var sparseWarningDelivered = false

    /** Current narration config built from user preferences. */
    private var currentNarrationConfig = NarrationConfig()

    /** Smoothed bearing for heading-up map (low-pass filter). */
    private var smoothedBearing: Float = 0f
    private val bearingSmoothingFactor = 0.15f

    /** Previous location for computing bearing/speed when GPS doesn't provide them (e.g. emulator). */
    private var previousLocation: Location? = null

    init {
        // Load route data from the shared session data holder (set by HomeViewModel)
        val segments = sessionDataHolder.routeSegments
        val points = sessionDataHolder.routePoints
        val interpolated = sessionDataHolder.interpolatedPoints
        if (segments != null && points != null) {
            initializeRoute(segments, points, interpolated ?: points)
        }
    }

    // -- Public API --

    /**
     * Initialize the session with pre-analyzed route data from the HomeViewModel.
     * This should be called before startSession().
     */
    fun initializeRoute(
        segments: List<RouteSegment>,
        points: List<LatLon>,
        interpolated: List<LatLon> = points
    ) {
        routeSegments = segments
        routePoints = points
        interpolatedPoints = interpolated

        // Create MapMatcher with route-specific data
        mapMatcher = MapMatcher(points, segments)

        // Calculate total route distance
        totalRouteDistanceM = mapMatcher?.totalDistance ?: run {
            segments.lastOrNull()?.let { segment ->
                when (segment) {
                    is RouteSegment.Curve -> segment.data.distanceFromStart + segment.data.arcLength
                    is RouteSegment.Straight -> segment.data.distanceFromStart + segment.data.length
                }
            } ?: 0.0
        }

        // Generate initial upcoming curves list
        updateUpcomingCurves(0.0)

        viewModelScope.launch {
            val mode = userPreferences.drivingMode.first()
            val units = userPreferences.speedUnits.first()
            val verbosity = userPreferences.verbosity.first()
            _uiState.value = _uiState.value.copy(
                isMotorcycleMode = mode == DrivingMode.MOTORCYCLE,
                usesMph = units == SpeedUnit.MPH,
                verbosity = verbosity
            )

            // Build initial narration config from preferences
            val timingProfile = userPreferences.timingProfile.first()
            currentNarrationConfig = NarrationConfig(
                mode = mode,
                verbosity = verbosity,
                units = units,
                timingProfile = timingProfile,
                narrateStraights = userPreferences.narrateStraights.first(),
                narrateLeanAngle = userPreferences.leanAngleNarration.first(),
                narrateSurface = userPreferences.surfaceWarnings.first()
            )
        }
    }

    /**
     * Start the GPS tracking and narration session.
     */
    fun startSession() {
        if (routePoints.isEmpty()) return

        smoothedBearing = 0f
        previousLocation = null
        _uiState.value = _uiState.value.copy(sessionState = SessionState.PLAYING)

        // Start foreground service for background GPS + TTS
        SessionForegroundService.start(appContext)

        // Initialize TTS engine and set up completion callback for cooldown tracking
        ttsEngine.initialize()
        ttsEngine.setTtsListener(object : TtsEngine.TtsListener {
            override fun onSpeechComplete(event: NarrationEvent) {
                narrationManager.onNarrationComplete()
            }
            override fun onSpeechInterrupted(interruptedEvent: NarrationEvent, interruptingEvent: NarrationEvent) {
                narrationManager.onNarrationComplete()
            }
            override fun onSpeechError(event: NarrationEvent, error: String) {
                narrationManager.onNarrationComplete()
            }
        })

        // Set up narration manager: register listener and load route
        narrationManager.setListener(this)
        narrationManager.loadRoute(routeSegments, routePoints)

        // Apply current narration config
        narrationManager.updateConfig(currentNarrationConfig)

        // Start collecting GPS locations
        locationJob = viewModelScope.launch {
            locationProvider.locationUpdates().collect { location ->
                onLocationUpdate(location)
            }
        }
    }

    /**
     * Pause the session. GPS stops, TTS stops mid-utterance.
     */
    fun pauseSession() {
        locationJob?.cancel()
        locationJob = null
        ttsEngine.stop()
        narrationManager.pause()
        _uiState.value = _uiState.value.copy(sessionState = SessionState.PAUSED)
    }

    /**
     * Resume a paused session.
     */
    fun resumeSession() {
        _uiState.value = _uiState.value.copy(
            sessionState = SessionState.PLAYING,
            isOffRoute = false
        )

        narrationManager.resume()

        locationJob = viewModelScope.launch {
            locationProvider.locationUpdates().collect { location ->
                onLocationUpdate(location)
            }
        }
    }

    /**
     * Stop the session completely.
     */
    fun stopSession() {
        locationJob?.cancel()
        locationJob = null
        ttsEngine.stop()
        ttsEngine.shutdown()
        narrationManager.stop()
        narrationManager.setListener(null)
        sessionDataHolder.clear()

        // Stop foreground service
        SessionForegroundService.stop(appContext)

        _uiState.value = _uiState.value.copy(sessionState = SessionState.STOPPED)
    }

    /**
     * Toggle mute state. When muted, TTS is silenced but narration text
     * still updates on screen.
     */
    fun toggleMute() {
        val newMuted = !_uiState.value.isMuted
        _uiState.value = _uiState.value.copy(isMuted = newMuted)
        if (newMuted) {
            ttsEngine.stop()
        }
    }

    /**
     * Cycle verbosity level: Minimal (1) -> Standard (2) -> Detailed (3) -> Minimal (1).
     */
    fun cycleVerbosity() {
        val current = _uiState.value.verbosity
        val next = if (current >= 3) 1 else current + 1
        _uiState.value = _uiState.value.copy(verbosity = next)
        viewModelScope.launch {
            userPreferences.setVerbosity(next)
        }
        // Update narration config with new verbosity
        currentNarrationConfig = currentNarrationConfig.copy(verbosity = next)
        narrationManager.updateConfig(currentNarrationConfig)
    }

    // -- NarrationManager.NarrationListener implementation --

    override fun onNarration(event: NarrationEvent) {
        _uiState.value = _uiState.value.copy(lastNarrationText = event.text)
        if (!_uiState.value.isMuted) {
            ttsEngine.speak(event)
        }
    }

    override fun onInterrupt(event: NarrationEvent) {
        _uiState.value = _uiState.value.copy(lastNarrationText = event.text)
        if (!_uiState.value.isMuted) {
            ttsEngine.interrupt(event)
        }
    }

    override fun onUrgentAlert(event: NarrationEvent) {
        _uiState.value = _uiState.value.copy(lastNarrationText = event.text)
        if (!_uiState.value.isMuted) {
            // Urgent alerts always interrupt — use QUEUE_FLUSH via interrupt()
            ttsEngine.interrupt(event)
        }
    }

    override fun onPaused(reason: String) {
        _uiState.value = _uiState.value.copy(lastNarrationText = reason)
    }

    override fun onResumed() {
        _uiState.value = _uiState.value.copy(
            lastNarrationText = "Narration resumed"
        )
    }

    // -- Internal --

    /**
     * Process a new GPS location update.
     * This is the main integration point wiring GPS -> MapMatcher -> NarrationManager.
     */
    private fun onLocationUpdate(location: Location) {
        if (_uiState.value.sessionState != SessionState.PLAYING) return

        val matcher = mapMatcher ?: return
        val gpsLatLon = LatLon(location.latitude, location.longitude)

        // Speed: prefer GPS speed, fall back to computing from consecutive positions
        // (emulator GPX playback may not provide speed)
        val speedMs = if (location.hasSpeed() && location.speed > 0f) {
            location.speed.toDouble()
        } else {
            val prev = previousLocation
            if (prev != null && location.time > prev.time) {
                val dist = prev.distanceTo(location).toDouble()
                val dt = (location.time - prev.time) / 1000.0
                if (dt > 0) dist / dt else 0.0
            } else 0.0
        }
        val speedKmh = speedMs * 3.6
        val speedMph = speedMs * 2.23694

        // Bearing: prefer GPS bearing, fall back to computing from consecutive positions
        // (emulator GPX playback doesn't provide bearing)
        val rawBearing = if (location.hasBearing() && location.bearing != 0f) {
            location.bearing
        } else {
            val prev = previousLocation
            if (prev != null) {
                val dist = prev.distanceTo(location).toDouble()
                if (dist > 2.0) prev.bearingTo(location) else smoothedBearing
            } else smoothedBearing
        }
        smoothedBearing = if (speedMs > 1.0) {
            // Apply low-pass filter for smooth heading-up rotation
            val delta = normalizeAngleDelta(rawBearing - smoothedBearing)
            smoothedBearing + delta * bearingSmoothingFactor
        } else {
            // At very low speed, don't update bearing (GPS bearing is unreliable)
            smoothedBearing
        }
        previousLocation = location

        // Map match to route (single argument - route data is in the MapMatcher instance)
        val matchResult = matcher.matchToRoute(gpsLatLon)

        // Off-route detection (>100m from route)
        val distFromRoute = matchResult.distanceFromRoute
        if (matchResult.isOffRoute) {
            if (!_uiState.value.isOffRoute) {
                _uiState.value = _uiState.value.copy(
                    isOffRoute = true,
                    offRouteDistanceM = distFromRoute,
                    lastNarrationText = "Off route -- curve narration paused",
                    currentLatitude = location.latitude,
                    currentLongitude = location.longitude,
                    currentBearing = smoothedBearing,
                    currentAccuracy = location.accuracy
                )
                if (!_uiState.value.isMuted) {
                    ttsEngine.speak("Off route. Curve narration paused.", priority = 10)
                }
            }
            return
        } else if (_uiState.value.isOffRoute) {
            // Back on route
            _uiState.value = _uiState.value.copy(isOffRoute = false)
            if (!_uiState.value.isMuted) {
                ttsEngine.speak("Back on route. Resuming narration.", priority = 10)
            }
        }

        // Route progress (using the correct field name from MatchResult)
        val routeProgress = matchResult.routeProgress
        val progressPercent = if (totalRouteDistanceM > 0) {
            (routeProgress / totalRouteDistanceM * 100).toFloat().coerceIn(0f, 100f)
        } else 0f

        // Update narration manager with route progress and speed
        // NarrationManager uses a listener pattern (implemented above), not return values
        narrationManager.onLocationUpdate(routeProgress, speedMs)

        // Sparse data warning: check if current position is in a sparse data region
        // by looking at the confidence of the nearest upcoming curve
        val nearestCurve = _uiState.value.upcomingCurves.firstOrNull()?.curveSegment
        val isSparse = nearestCurve != null && nearestCurve.confidence < 0.3f
        if (isSparse && !sparseWarningDelivered) {
            sparseWarningDelivered = true
            if (!_uiState.value.isMuted) {
                ttsEngine.speak(
                    "Low data quality ahead. Curve information may be incomplete.",
                    priority = 8
                )
            }
        } else if (!isSparse) {
            sparseWarningDelivered = false
        }

        // Update upcoming curves
        updateUpcomingCurves(routeProgress)

        // Find the nearest active advisory
        val activeCurve = _uiState.value.upcomingCurves.firstOrNull()?.curveSegment
        val activeAdvisoryKmh = activeCurve?.advisorySpeedMs?.let { it * 3.6 }
        val activeAdvisoryMph = activeCurve?.advisorySpeedMs?.let { it * 2.23694 }
        val activeLean = if (_uiState.value.isMotorcycleMode) activeCurve?.leanAngleDeg else null

        // Compute dynamic zoom from speed + next curve proximity/severity
        val nextUpcoming = _uiState.value.upcomingCurves.firstOrNull()
        val targetZoom = computeTargetZoom(speedKmh, nextUpcoming)

        _uiState.value = _uiState.value.copy(
            currentSpeedKmh = speedKmh,
            currentSpeedMph = speedMph,
            activeAdvisorySpeedKmh = activeAdvisoryKmh,
            activeAdvisorySpeedMph = activeAdvisoryMph,
            activeLeanAngle = activeLean,
            isSparseDataWarning = isSparse,
            routeProgressPercent = progressPercent,
            distanceRemainingM = (totalRouteDistanceM - routeProgress).coerceAtLeast(0.0),
            currentLatitude = location.latitude,
            currentLongitude = location.longitude,
            currentBearing = smoothedBearing,
            currentAccuracy = location.accuracy,
            targetZoom = targetZoom
        )
    }

    /**
     * Compute dynamic zoom level based on current speed and proximity to the next curve.
     *
     * Strategy:
     * - Speed-based baseline: zoom out at higher speeds so the driver sees more road ahead.
     * - Curve proximity boost: zoom in when approaching a curve for detail.
     * - Severity modifier: sharper curves get more zoom-in.
     *
     * Result is clamped to [14.0, 18.0] and smoothed to avoid jitter.
     */
    private fun computeTargetZoom(speedKmh: Double, nextCurve: UpcomingCurve?): Double {
        // Speed-based baseline: linear interpolation from 17.5 (stopped) to 14.5 (120+ km/h)
        val speedFactor = (speedKmh / 120.0).coerceIn(0.0, 1.0)
        val speedZoom = 17.5 - speedFactor * 3.0 // 17.5 at 0 km/h, 14.5 at 120 km/h

        if (nextCurve == null) return speedZoom.coerceIn(14.0, 18.0)

        val distanceM = nextCurve.distanceToM
        val severity = nextCurve.curveSegment.severity

        // Severity bonus: how much extra zoom-in for this curve type
        val severityBonus = when (severity) {
            Severity.GENTLE -> 0.0
            Severity.MODERATE -> 0.3
            Severity.FIRM -> 0.5
            Severity.SHARP -> 0.8
            Severity.HAIRPIN -> 1.2
        }

        // Proximity factor: ramp up as we get closer (0.0 at 500m+, 1.0 at 0m)
        val proximityFactor = when {
            distanceM >= 500.0 -> 0.0
            distanceM <= 0.0 -> 1.0
            else -> 1.0 - (distanceM / 500.0)
        }

        // Total zoom = speed baseline + (severity bonus * proximity ramp)
        val zoom = speedZoom + severityBonus * proximityFactor

        return zoom.coerceIn(14.0, 18.0)
    }

    /**
     * Normalize an angle delta to [-180, 180] for smooth interpolation.
     */
    private fun normalizeAngleDelta(delta: Float): Float {
        var d = delta % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    /**
     * Update the list of upcoming curves based on current route progress.
     * Shows the next 5 curves ahead of the current position.
     */
    private fun updateUpcomingCurves(currentProgress: Double) {
        val curves = routeSegments
            .filterIsInstance<RouteSegment.Curve>()
            .map { it.data }
            .filter { it.distanceFromStart > currentProgress }
            .take(5)
            .map { curve ->
                UpcomingCurve(
                    curveSegment = curve,
                    distanceToM = curve.distanceFromStart - currentProgress,
                    briefDescription = buildCurveDescription(curve)
                )
            }

        _uiState.value = _uiState.value.copy(upcomingCurves = curves)
    }

    /**
     * Build a brief text description for an upcoming curve in the list.
     */
    private fun buildCurveDescription(curve: CurveSegment): String {
        val parts = mutableListOf<String>()

        // Direction + severity
        val dir = curve.direction.name.lowercase().replaceFirstChar { it.uppercase() }
        val sev = curve.severity.name.lowercase()
        parts.add("$sev $dir")

        // Modifiers
        if (com.curvecall.engine.types.CurveModifier.TIGHTENING in curve.modifiers) {
            parts.add("tightening")
        }
        if (com.curvecall.engine.types.CurveModifier.OPENING in curve.modifiers) {
            parts.add("opening")
        }

        // Compound type
        curve.compoundType?.let { compound ->
            when (compound) {
                com.curvecall.engine.types.CompoundType.S_BEND -> parts.add("S-bend")
                com.curvecall.engine.types.CompoundType.CHICANE -> parts.add("chicane")
                com.curvecall.engine.types.CompoundType.SERIES -> parts.add("series of ${curve.compoundSize ?: "?"}")
                com.curvecall.engine.types.CompoundType.TIGHTENING_SEQUENCE -> parts.add("tightening sequence")
            }
        }

        // Speed advisory
        curve.advisorySpeedMs?.let { speedMs ->
            val displaySpeed = if (_uiState.value.usesMph) {
                "${(speedMs * 2.23694).toInt() / 5 * 5} mph"
            } else {
                "${(speedMs * 3.6).toInt() / 5 * 5} km/h"
            }
            parts.add("slow to $displaySpeed")
        }

        return parts.joinToString(", ").replaceFirstChar { it.uppercase() }
    }

    override fun onCleared() {
        super.onCleared()
        locationJob?.cancel()
        ttsEngine.stop()
        ttsEngine.shutdown()
        narrationManager.stop()
        narrationManager.setListener(null)
        // Stop foreground service to prevent it from running indefinitely
        SessionForegroundService.stop(appContext)
    }
}

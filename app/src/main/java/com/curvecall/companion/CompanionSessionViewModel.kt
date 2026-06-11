package com.curvecall.companion

import android.content.Context
import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.curvecall.audio.AndroidTtsEngine
import com.curvecall.data.location.LocationProvider
import com.curvecall.data.preferences.UserPreferences
import com.curvecall.data.regions.RegionRepository
import com.curvecall.data.routing.GraphHopperRouter
import com.curvecall.engine.MapMatcher
import com.curvecall.engine.RouteAnalyzer
import com.curvecall.engine.types.AnalysisConfig
import com.curvecall.engine.types.CurveSegment
import com.curvecall.engine.types.DrivingMode
import com.curvecall.engine.types.LatLon
import com.curvecall.engine.types.Severity
import com.curvecall.engine.types.SpeedUnit
import com.curvecall.narration.NarrationManager
import com.curvecall.narration.TtsDurationCalibrator
import com.curvecall.narration.TtsEngine
import com.curvecall.narration.types.NarrationConfig
import com.curvecall.narration.types.NarrationEvent
import com.curvecall.service.CompanionForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ViewModel for the companion mode active session.
 *
 * Simplified version of SessionViewModel that uses [RoadScanner] instead of
 * MapMatcher + pre-computed route. The "route" is a sliding window: a 3-5km
 * polyline ahead of the driver, rescanned every ~2km of travel.
 *
 * Wires together:
 * GPS → RoadScanner → RouteAnalyzer → NarrationManager → TtsEngine (per-utterance focus)
 *
 * The NarrationManager is reloaded with each rescan. Already-passed curves are
 * behind the driver (routeProgress ~0 in new scan) so they won't re-fire.
 */
@HiltViewModel
class CompanionSessionViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val locationProvider: LocationProvider,
    private val narrationManager: NarrationManager,
    private val routeAnalyzer: RouteAnalyzer,
    private val userPreferences: UserPreferences,
    private val graphHopperRouter: GraphHopperRouter,
    private val regionRepository: RegionRepository,
    private val roadScanner: RoadScanner,
    private val calibrator: TtsDurationCalibrator
) : ViewModel(), NarrationManager.NarrationListener {

    private val _uiState = MutableStateFlow(CompanionUiState())
    val uiState: StateFlow<CompanionUiState> = _uiState.asStateFlow()

    init {
        // Bridge UI state to the service overlay
        _uiState.onEach { state ->
            CompanionForegroundService.updateState(state)
        }.launchIn(viewModelScope)
    }

    private var ttsEngine: TtsEngine? = null
    private var locationJob: Job? = null
    private var gpsWatchdogJob: Job? = null
    private var scanJob: Job? = null
    private var currentMapMatcher: MapMatcher? = null
    private var currentNarrationConfig = NarrationConfig()
    private var lastLocationTimeMs: Long = 0L
    private var previousLocation: Location? = null
    private var smoothedBearing: Float = 0f

    companion object {
        /** If no GPS for this duration, consider signal lost. */
        private const val GPS_LOST_TIMEOUT_MS = 5000L
        private const val BEARING_SMOOTHING = 0.15f

        /**
         * Max distance from the scanned polyline before we consider the driver
         * to have diverged from the predicted path and trigger a rescan.
         */
        private const val ON_PATH_TOLERANCE_M = 30.0
    }

    /**
     * Start the companion mode session.
     *
     * Loads the graph for the current region, initializes TTS with per-utterance
     * focus, starts the foreground service, and begins GPS collection.
     */
    fun start() {
        if (_uiState.value.state is CompanionState.Active ||
            _uiState.value.state is CompanionState.Starting) return

        _uiState.value = _uiState.value.copy(state = CompanionState.Starting)

        viewModelScope.launch {
            try {
                // Load preferences
                val mode = userPreferences.drivingMode.first()
                val units = userPreferences.speedUnits.first()
                val verbosity = userPreferences.verbosity.first()
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

                _uiState.value = _uiState.value.copy(verbosity = verbosity)

                // Load graph if not already loaded
                if (!graphHopperRouter.isGraphLoaded) {
                    val location = locationProvider.getLastLocation()
                    if (location != null) {
                        val region = regionRepository.findRegionForCoordinate(
                            location.latitude, location.longitude
                        )
                        if (region != null) {
                            graphHopperRouter.loadGraph(
                                regionRepository.getGraphDir(region.id)
                            )
                        } else {
                            _uiState.value = _uiState.value.copy(
                                state = CompanionState.Error("No offline region for current location")
                            )
                            return@launch
                        }
                    } else {
                        _uiState.value = _uiState.value.copy(
                            state = CompanionState.Error("Cannot determine current location")
                        )
                        return@launch
                    }
                }

                // Create TTS engine with per-utterance audio focus
                val engine = AndroidTtsEngine(appContext, calibrator, perUtteranceFocus = true)
                engine.initialize()
                engine.setTtsListener(object : TtsEngine.TtsListener {
                    override fun onSpeechComplete(event: NarrationEvent) {
                        narrationManager.onNarrationComplete()
                    }
                    override fun onSpeechInterrupted(
                        interruptedEvent: NarrationEvent,
                        interruptingEvent: NarrationEvent
                    ) {
                        narrationManager.onNarrationComplete()
                    }
                    override fun onSpeechError(event: NarrationEvent, error: String) {
                        narrationManager.onNarrationComplete()
                    }
                })
                ttsEngine = engine

                // Set up narration manager
                narrationManager.setListener(this@CompanionSessionViewModel)

                // Start foreground service
                CompanionForegroundService.start(appContext)

                _uiState.value = _uiState.value.copy(state = CompanionState.Active)

                // Start GPS collection
                locationJob = viewModelScope.launch {
                    locationProvider.locationUpdates().collect { location ->
                        onLocationUpdate(location)
                    }
                }

                // Watchdog: flag GPS loss when no fix arrives for a while
                gpsWatchdogJob = viewModelScope.launch {
                    while (true) {
                        delay(1000)
                        val last = lastLocationTimeMs
                        if (last > 0 && !_uiState.value.isGpsLost &&
                            System.currentTimeMillis() - last > GPS_LOST_TIMEOUT_MS
                        ) {
                            _uiState.value = _uiState.value.copy(isGpsLost = true)
                            if (!_uiState.value.isMuted) {
                                ttsEngine?.speak("GPS signal lost", 10)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    state = CompanionState.Error(e.message ?: "Failed to start companion mode")
                )
            }
        }
    }

    /**
     * Stop the companion mode session completely.
     */
    fun stop() {
        locationJob?.cancel()
        locationJob = null
        gpsWatchdogJob?.cancel()
        gpsWatchdogJob = null
        scanJob?.cancel()
        scanJob = null
        ttsEngine?.stop()
        ttsEngine?.shutdown()
        ttsEngine = null
        narrationManager.stop()
        narrationManager.setListener(null)
        currentMapMatcher = null

        CompanionForegroundService.stop(appContext)

        _uiState.value = CompanionUiState(state = CompanionState.Stopped)
    }

    /**
     * Toggle mute state.
     */
    fun toggleMute() {
        val newMuted = !_uiState.value.isMuted
        _uiState.value = _uiState.value.copy(isMuted = newMuted)
        if (newMuted) {
            ttsEngine?.stop()
        }
    }

    /**
     * Cycle verbosity: Minimal (1) → Standard (2) → Detailed (3) → Minimal (1).
     */
    fun cycleVerbosity() {
        val current = _uiState.value.verbosity
        val next = if (current >= 3) 1 else current + 1
        _uiState.value = _uiState.value.copy(verbosity = next)
        viewModelScope.launch {
            userPreferences.setVerbosity(next)
        }
        currentNarrationConfig = currentNarrationConfig.copy(verbosity = next)
        narrationManager.updateConfig(currentNarrationConfig)
    }

    // -- NarrationManager.NarrationListener --

    override fun onNarration(event: NarrationEvent) {
        _uiState.value = _uiState.value.copy(
            lastNarrationText = event.text,
            narrationCount = _uiState.value.narrationCount + 1
        )
        if (!_uiState.value.isMuted) {
            ttsEngine?.speak(event)
        }
    }

    override fun onInterrupt(event: NarrationEvent) {
        _uiState.value = _uiState.value.copy(
            lastNarrationText = event.text,
            narrationCount = _uiState.value.narrationCount + 1
        )
        if (!_uiState.value.isMuted) {
            ttsEngine?.interrupt(event)
        }
    }

    override fun onUrgentAlert(event: NarrationEvent) {
        _uiState.value = _uiState.value.copy(
            lastNarrationText = event.text,
            narrationCount = _uiState.value.narrationCount + 1
        )
        if (!_uiState.value.isMuted) {
            ttsEngine?.interrupt(event)
        }
    }

    override fun onPaused(reason: String) {
        _uiState.value = _uiState.value.copy(lastNarrationText = reason)
    }

    override fun onResumed() {
        _uiState.value = _uiState.value.copy(lastNarrationText = "Narration resumed")
    }

    // -- GPS processing --

    private fun onLocationUpdate(location: Location) {
        if (_uiState.value.state !is CompanionState.Active) return

        val now = System.currentTimeMillis()

        // GPS signal restored after loss
        if (_uiState.value.isGpsLost) {
            _uiState.value = _uiState.value.copy(isGpsLost = false)
            if (!_uiState.value.isMuted) {
                ttsEngine?.speak("GPS signal restored", 10)
            }
        }
        lastLocationTimeMs = now

        // Compute speed
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

        // Compute heading
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
            val delta = normalizeAngleDelta(rawBearing - smoothedBearing)
            smoothedBearing + delta * BEARING_SMOOTHING
        } else {
            smoothedBearing
        }
        previousLocation = location

        // Match against the current scanned window (cheap, every fix)
        val matchResult = currentMapMatcher?.matchToRoute(
            LatLon(location.latitude, location.longitude)
        )

        // Full graph scans are expensive, so only rescan when we have no window
        // yet, the driver diverged from the predicted path (took a different
        // fork), or we've consumed enough of the window that it needs extending.
        val needsRescan = matchResult == null
                || matchResult.distanceFromRoute > ON_PATH_TOLERANCE_M
                || matchResult.routeProgress > RoadScanner.RESCAN_DISTANCE_M
        if (needsRescan) {
            requestRescan(location, smoothedBearing.toDouble(), speedMs)
        }

        // Feed narration only while we're actually on the predicted path;
        // after a divergence the rescan will reload it for the new road.
        if (matchResult != null && matchResult.distanceFromRoute <= ON_PATH_TOLERANCE_M) {
            narrationManager.onLocationUpdate(matchResult.routeProgress, speedMs)

            val nextCurve = matchResult.nextCurve?.data
            _uiState.value = _uiState.value.copy(
                currentSpeedKmh = speedKmh,
                currentSpeedMph = speedMph,
                nextCurvePreview = nextCurve?.let { buildCurvePreview(it) },
                nextCurveDistanceM = matchResult.distanceToNextCurve
            )
        } else {
            _uiState.value = _uiState.value.copy(
                currentSpeedKmh = speedKmh,
                currentSpeedMph = speedMph
            )
        }
    }

    /**
     * Scan the road ahead and reload the narration pipeline with the result.
     * Runs the graph walk + curve analysis on a background dispatcher; at most
     * one scan is in flight at a time (later GPS fixes will retrigger if needed).
     */
    private fun requestRescan(location: Location, headingDeg: Double, speedMs: Double) {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            val profile = if (currentNarrationConfig.mode == DrivingMode.MOTORCYCLE) {
                "motorcycle"
            } else {
                "car"
            }
            val scanned = withContext(Dispatchers.Default) {
                val scan = roadScanner.scan(
                    location.latitude, location.longitude, headingDeg, speedMs, profile
                ) ?: return@withContext null
                scan to routeAnalyzer.analyzeRouteDetailed(scan.polyline)
            }

            if (_uiState.value.state !is CompanionState.Active) return@launch

            if (scanned == null) {
                // Off-road: no drivable road within snap distance
                if (!_uiState.value.isOffRoad) {
                    _uiState.value = _uiState.value.copy(
                        isOffRoad = true,
                        nextCurvePreview = null,
                        nextCurveDistanceM = null
                    )
                    if (!_uiState.value.isMuted) {
                        ttsEngine?.speak("No road data available", 10)
                    }
                }
                return@launch
            }

            val (_, result) = scanned

            // Reload narration pipeline for the new window. Stopping first
            // cancels anything queued for the old (possibly wrong) path.
            narrationManager.stop()
            narrationManager.loadRoute(result.segments, result.interpolatedPoints)
            narrationManager.updateConfig(currentNarrationConfig)

            currentMapMatcher = MapMatcher(result.interpolatedPoints, result.segments)

            if (_uiState.value.isOffRoad) {
                _uiState.value = _uiState.value.copy(isOffRoad = false)
                if (!_uiState.value.isMuted) {
                    ttsEngine?.speak("Back on road. Resuming narration.", 10)
                }
            }
        }
    }

    private fun buildCurvePreview(curve: CurveSegment): String {
        val dir = curve.direction.name.lowercase().replaceFirstChar { it.uppercase() }
        val sev = curve.severity.name.lowercase()
        return "${sev.replaceFirstChar { it.uppercase() }} $dir"
    }

    private fun normalizeAngleDelta(delta: Float): Float {
        var d = delta % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    override fun onCleared() {
        super.onCleared()
        stop()
    }
}

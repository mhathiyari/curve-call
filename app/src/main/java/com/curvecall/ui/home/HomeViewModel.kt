package com.curvecall.ui.home

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.curvecall.data.gpx.GpxParser
import com.curvecall.data.gpx.GpxParseException
import com.curvecall.data.osm.OverpassClient
import com.curvecall.data.preferences.UserPreferences
import com.curvecall.data.session.SessionDataHolder
import com.curvecall.engine.RouteAnalyzer
import com.curvecall.engine.types.DrivingMode
import com.curvecall.engine.types.AnalysisConfig
import com.curvecall.engine.types.LatLon
import com.curvecall.engine.types.RouteSegment
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Home screen.
 *
 * Handles GPX file loading, route analysis via the engine, and navigation
 * to the session screen once analysis is complete.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val gpxParser: GpxParser,
    private val routeAnalyzer: RouteAnalyzer,
    private val overpassClient: OverpassClient,
    private val userPreferences: UserPreferences,
    private val sessionDataHolder: SessionDataHolder
) : ViewModel() {

    /**
     * Represents the current state of the home screen.
     */
    data class HomeUiState(
        val isLoading: Boolean = false,
        val loadingMessage: String = "",
        val routeName: String? = null,
        val routePointCount: Int = 0,
        val routeSegments: List<RouteSegment>? = null,
        val routePoints: List<LatLon>? = null,
        val errorMessage: String? = null,
        val isReadyForSession: Boolean = false,
        val recentRoutes: List<String> = emptyList(),
        val drivingMode: DrivingMode = DrivingMode.CAR
    )

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        // Observe preferences
        viewModelScope.launch {
            userPreferences.drivingMode.collect { mode ->
                _uiState.value = _uiState.value.copy(drivingMode = mode)
            }
        }
        viewModelScope.launch {
            userPreferences.recentRoutes.collect { routes ->
                _uiState.value = _uiState.value.copy(recentRoutes = routes.toList())
            }
        }
    }

    /**
     * Handle a GPX file selected by the user via the file picker.
     *
     * Opens the InputStream from the URI on the background thread (not the UI thread)
     * to avoid lifecycle issues. Parses the GPX, runs the route analysis engine,
     * optionally fetches surface data for motorcycle mode, and prepares the session.
     *
     * @param uri The URI of the selected GPX file
     */
    fun loadGpxFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.Default) {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                loadingMessage = "Parsing GPX file...",
                errorMessage = null,
                isReadyForSession = false,
                routeSegments = null
            )

            try {
                // Step 1: Open InputStream from URI using application context (thread-safe)
                val inputStream = appContext.contentResolver.openInputStream(uri)
                    ?: throw GpxParseException("Could not open file. The file may have been moved or deleted.")

                // Step 2: Parse GPX (closes stream automatically via use{})
                val gpxResult = inputStream.use { stream ->
                    gpxParser.parse(stream)
                }

                if (gpxResult.points.size < 3) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "GPX file must contain at least 3 track points for curve analysis."
                    )
                    return@launch
                }

                _uiState.value = _uiState.value.copy(
                    loadingMessage = "Analyzing route geometry (${gpxResult.pointCount} points)...",
                    routeName = gpxResult.routeName,
                    routePointCount = gpxResult.pointCount
                )

                // Step 2: Build analysis config from current preferences
                val mode = userPreferences.drivingMode.first()
                val lateralG = userPreferences.lateralG.first()
                val config = AnalysisConfig(
                    lateralG = lateralG,
                    isMotorcycleMode = mode == DrivingMode.MOTORCYCLE
                )

                // Step 3: Run route analysis
                val segments = routeAnalyzer.analyzeRoute(gpxResult.points, config)

                _uiState.value = _uiState.value.copy(
                    loadingMessage = "Route analyzed successfully.",
                    routeSegments = segments,
                    routePoints = gpxResult.points
                )

                // Step 4: Fetch surface data for motorcycle mode (non-blocking)
                if (mode == DrivingMode.MOTORCYCLE) {
                    _uiState.value = _uiState.value.copy(
                        loadingMessage = "Fetching surface data..."
                    )
                    try {
                        overpassClient.fetchSurfaceData(gpxResult.points)
                    } catch (e: Exception) {
                        // Surface data is optional, continue without it
                    }
                }

                // Step 5: Store data for session consumption
                sessionDataHolder.setRouteData(
                    segments = segments,
                    points = gpxResult.points,
                    name = gpxResult.routeName
                )

                // Step 6: Save to recent routes
                userPreferences.addRecentRoute(uri.toString())

                // Ready for session
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isReadyForSession = true,
                    loadingMessage = ""
                )

            } catch (e: SecurityException) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Permission denied. Please select the file again."
                )
            } catch (e: GpxParseException) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Could not parse GPX file: ${e.message}"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Error loading route: ${e.message}"
                )
            }
        }
    }

    /**
     * Toggle between Car and Motorcycle driving modes.
     */
    fun toggleDrivingMode() {
        viewModelScope.launch {
            val currentMode = userPreferences.drivingMode.first()
            val newMode = when (currentMode) {
                DrivingMode.CAR -> DrivingMode.MOTORCYCLE
                DrivingMode.MOTORCYCLE -> DrivingMode.CAR
            }
            userPreferences.setDrivingMode(newMode)
        }
    }

    /**
     * Clear any displayed error message.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /**
     * Reset the session-ready state (used when navigating back from session).
     */
    fun resetSessionState() {
        _uiState.value = _uiState.value.copy(
            isReadyForSession = false,
            routeSegments = null,
            routePoints = null
        )
    }
}

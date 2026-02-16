package com.curvecall.ui.destination

import android.annotation.SuppressLint
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.curvecall.data.geocoding.GeocodingService
import com.curvecall.data.location.LocationProvider
import com.curvecall.data.preferences.UserPreferences
import com.curvecall.data.routing.RoutePipeline
import com.curvecall.engine.types.LatLon
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * ViewModel for the Destination picker screen.
 *
 * Handles Nominatim search with debounce, map pin drop with reverse geocoding,
 * recent/favorite destination management, and triggering the route pipeline
 * when a destination is confirmed.
 */
@HiltViewModel
class DestinationViewModel @Inject constructor(
    private val geocodingService: GeocodingService,
    private val userPreferences: UserPreferences,
    private val routePipeline: RoutePipeline,
    private val locationProvider: LocationProvider
) : ViewModel() {

    data class SavedDestination(
        val name: String,
        val lat: Double,
        val lon: Double,
        val timestamp: Long,
        val isFavorite: Boolean = false
    )

    data class SelectedDestination(
        val name: String,
        val latLon: LatLon
    )

    data class DestinationUiState(
        val searchQuery: String = "",
        val searchResults: List<GeocodingService.SearchResult> = emptyList(),
        val isSearching: Boolean = false,
        val selectedDestination: SelectedDestination? = null,
        val recentDestinations: List<SavedDestination> = emptyList(),
        val favoriteDestinations: List<SavedDestination> = emptyList(),
        val errorMessage: String? = null,
        // Routing state
        val isRouting: Boolean = false,
        val routingMessage: String = "",
        val isRouteReady: Boolean = false
    )

    private val _uiState = MutableStateFlow(DestinationUiState())
    val uiState: StateFlow<DestinationUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        // Reset pipeline so stale Ready state from a previous route doesn't
        // immediately trigger navigation to RoutePreview
        routePipeline.reset()

        // Observe saved destinations from DataStore
        viewModelScope.launch {
            userPreferences.recentDestinations.collect { destinations ->
                val mapped = destinations.map { it.toSavedDestination() }
                val recent = mapped.filter { !it.isFavorite }
                    .sortedByDescending { it.timestamp }
                val favorites = mapped.filter { it.isFavorite }
                    .sortedByDescending { it.timestamp }
                _uiState.value = _uiState.value.copy(
                    recentDestinations = recent,
                    favoriteDestinations = favorites
                )
            }
        }

        // Observe pipeline state for routing progress
        viewModelScope.launch {
            routePipeline.state.collect { pipelineState ->
                when (pipelineState) {
                    is RoutePipeline.PipelineState.Idle -> {
                        // Don't reset routing state on Idle — we control that ourselves
                    }
                    is RoutePipeline.PipelineState.Routing -> {
                        _uiState.value = _uiState.value.copy(
                            isRouting = true,
                            routingMessage = pipelineState.message
                        )
                    }
                    is RoutePipeline.PipelineState.Analyzing -> {
                        _uiState.value = _uiState.value.copy(
                            routingMessage = pipelineState.message
                        )
                    }
                    is RoutePipeline.PipelineState.CachingTiles -> {
                        _uiState.value = _uiState.value.copy(
                            routingMessage = pipelineState.message
                        )
                    }
                    is RoutePipeline.PipelineState.Ready -> {
                        _uiState.value = _uiState.value.copy(
                            isRouting = false,
                            routingMessage = "",
                            isRouteReady = true
                        )
                    }
                    is RoutePipeline.PipelineState.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isRouting = false,
                            routingMessage = "",
                            errorMessage = pipelineState.message
                        )
                    }
                }
            }
        }
    }

    private fun UserPreferences.SavedDestination.toSavedDestination() = SavedDestination(
        name = name,
        lat = lat,
        lon = lon,
        timestamp = timestamp,
        isFavorite = isFavorite
    )

    fun onSearchQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        searchJob?.cancel()

        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(
                searchResults = emptyList(),
                isSearching = false
            )
            return
        }

        searchJob = viewModelScope.launch {
            delay(300L)
            _uiState.value = _uiState.value.copy(isSearching = true)
            try {
                val results = geocodingService.search(query)
                _uiState.value = _uiState.value.copy(
                    searchResults = results,
                    isSearching = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    errorMessage = "Search failed: ${e.message}"
                )
            }
        }
    }

    fun onSearchResultSelected(result: GeocodingService.SearchResult) {
        _uiState.value = _uiState.value.copy(
            selectedDestination = SelectedDestination(
                name = result.displayName,
                latLon = LatLon(result.lat, result.lon)
            ),
            searchResults = emptyList(),
            searchQuery = ""
        )
    }

    fun onMapLongPress(lat: Double, lon: Double) {
        val coordName = "%.4f, %.4f".format(lat, lon)
        _uiState.value = _uiState.value.copy(
            selectedDestination = SelectedDestination(
                name = coordName,
                latLon = LatLon(lat, lon)
            )
        )

        viewModelScope.launch(Dispatchers.IO) {
            val displayName = geocodingService.reverseGeocode(lat, lon)
            if (displayName != null) {
                val current = _uiState.value.selectedDestination
                if (current != null &&
                    current.latLon.lat == lat &&
                    current.latLon.lon == lon
                ) {
                    _uiState.value = _uiState.value.copy(
                        selectedDestination = current.copy(name = displayName)
                    )
                }
            }
        }
    }

    fun onSavedDestinationSelected(destination: SavedDestination) {
        _uiState.value = _uiState.value.copy(
            selectedDestination = SelectedDestination(
                name = destination.name,
                latLon = LatLon(destination.lat, destination.lon)
            )
        )
    }

    /**
     * Called when the user confirms the selected destination ("Route Here").
     * Gets current GPS location, saves to recents, and triggers the RoutePipeline.
     * Observe [uiState].isRouteReady for navigation trigger.
     */
    @SuppressLint("MissingPermission")
    fun onDestinationConfirmed() {
        val selected = _uiState.value.selectedDestination ?: return
        if (_uiState.value.isRouting) return // prevent double-tap

        // Save to recents
        viewModelScope.launch {
            userPreferences.addRecentDestination(
                name = selected.name,
                lat = selected.latLon.lat,
                lon = selected.latLon.lon
            )
        }

        // Get GPS location and trigger routing
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isRouting = true,
                routingMessage = "Getting GPS location...",
                isRouteReady = false
            )

            // Get current location (wait up to 10 seconds)
            val location = try {
                withTimeoutOrNull(10_000L) {
                    locationProvider.locationUpdates().first()
                }
            } catch (e: SecurityException) {
                _uiState.value = _uiState.value.copy(
                    isRouting = false,
                    errorMessage = "Location permission required. Please grant location access."
                )
                return@launch
            }

            if (location == null) {
                _uiState.value = _uiState.value.copy(
                    isRouting = false,
                    errorMessage = "Could not get GPS location. Make sure GPS is enabled."
                )
                return@launch
            }

            val from = LatLon(location.latitude, location.longitude)

            // Reset pipeline and start routing
            routePipeline.reset()
            routePipeline.computeRoute(
                from = from,
                to = selected.latLon,
                routeName = selected.name
            )
        }
    }

    /** Reset routing ready state after navigation has occurred. */
    fun onRouteNavigated() {
        _uiState.value = _uiState.value.copy(isRouteReady = false)
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedDestination = null)
    }

    fun toggleFavorite(destination: SavedDestination) {
        viewModelScope.launch {
            userPreferences.toggleFavorite(destination.lat, destination.lon)
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}

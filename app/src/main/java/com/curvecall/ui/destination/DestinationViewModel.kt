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

enum class ActiveField { FROM, TO }

sealed class LocationSelection {
    object CurrentLocation : LocationSelection()
    data class SpecificLocation(val name: String, val latLon: LatLon) : LocationSelection()
}

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
        val activeField: ActiveField = ActiveField.TO,
        val fromSelection: LocationSelection = LocationSelection.CurrentLocation,
        val toSelection: LocationSelection? = null,
        val searchQuery: String = "",
        val searchResults: List<GeocodingService.SearchResult> = emptyList(),
        val isSearching: Boolean = false,
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

    fun onActiveFieldChanged(field: ActiveField) {
        _uiState.value = _uiState.value.copy(
            activeField = field,
            searchQuery = "",
            searchResults = emptyList(),
            isSearching = false
        )
    }

    fun onSwapFields() {
        val current = _uiState.value
        val toSel = current.toSelection ?: return // nothing to swap if TO is empty

        // If FROM was CurrentLocation, we can't place it as TO (no coordinates yet),
        // so TO becomes null and user must re-pick.
        val newTo: LocationSelection? = when (current.fromSelection) {
            is LocationSelection.CurrentLocation -> null
            is LocationSelection.SpecificLocation -> current.fromSelection
        }

        _uiState.value = current.copy(
            fromSelection = toSel,
            toSelection = newTo,
            searchQuery = "",
            searchResults = emptyList()
        )
    }

    fun onResetToCurrentLocation() {
        _uiState.value = _uiState.value.copy(
            fromSelection = LocationSelection.CurrentLocation,
            searchQuery = "",
            searchResults = emptyList()
        )
    }

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
        val selection = LocationSelection.SpecificLocation(
            name = result.displayName,
            latLon = LatLon(result.lat, result.lon)
        )
        applySelectionToActiveField(selection)
    }

    fun onMapLongPress(lat: Double, lon: Double) {
        val coordName = "%.4f, %.4f".format(lat, lon)
        val selection = LocationSelection.SpecificLocation(
            name = coordName,
            latLon = LatLon(lat, lon)
        )
        val fieldAtTimeOfPress = _uiState.value.activeField
        applySelectionToActiveField(selection)

        viewModelScope.launch(Dispatchers.IO) {
            val displayName = geocodingService.reverseGeocode(lat, lon)
            if (displayName != null) {
                val current = _uiState.value
                val updated = LocationSelection.SpecificLocation(
                    name = displayName,
                    latLon = LatLon(lat, lon)
                )
                when (fieldAtTimeOfPress) {
                    ActiveField.FROM -> {
                        val existing = current.fromSelection as? LocationSelection.SpecificLocation
                        if (existing?.latLon?.lat == lat && existing.latLon.lon == lon) {
                            _uiState.value = current.copy(fromSelection = updated)
                        }
                    }
                    ActiveField.TO -> {
                        val existing = current.toSelection as? LocationSelection.SpecificLocation
                        if (existing?.latLon?.lat == lat && existing.latLon.lon == lon) {
                            _uiState.value = current.copy(toSelection = updated)
                        }
                    }
                }
            }
        }
    }

    fun onSavedDestinationSelected(destination: SavedDestination) {
        val selection = LocationSelection.SpecificLocation(
            name = destination.name,
            latLon = LatLon(destination.lat, destination.lon)
        )
        applySelectionToActiveField(selection)
    }

    private fun applySelectionToActiveField(selection: LocationSelection.SpecificLocation) {
        val current = _uiState.value
        _uiState.value = when (current.activeField) {
            ActiveField.FROM -> current.copy(
                fromSelection = selection,
                searchQuery = "",
                searchResults = emptyList()
            )
            ActiveField.TO -> current.copy(
                toSelection = selection,
                searchQuery = "",
                searchResults = emptyList()
            )
        }
    }

    /**
     * Called when the user confirms the route.
     * Resolves the FROM location (GPS or specific), saves TO to recents,
     * and triggers the RoutePipeline.
     * Observe [uiState].isRouteReady for navigation trigger.
     */
    @SuppressLint("MissingPermission")
    fun onDestinationConfirmed() {
        val current = _uiState.value
        val toSelection = current.toSelection as? LocationSelection.SpecificLocation ?: return
        if (current.isRouting) return // prevent double-tap

        // Save TO destination to recents
        viewModelScope.launch {
            userPreferences.addRecentDestination(
                name = toSelection.name,
                lat = toSelection.latLon.lat,
                lon = toSelection.latLon.lon
            )
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isRouting = true,
                routingMessage = "Preparing route...",
                isRouteReady = false
            )

            // Resolve FROM coordinates
            val from: LatLon = when (current.fromSelection) {
                is LocationSelection.CurrentLocation -> {
                    _uiState.value = _uiState.value.copy(routingMessage = "Getting GPS location...")
                    val location = try {
                        locationProvider.getLastLocation()
                            ?: withTimeoutOrNull(15_000L) {
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
                    LatLon(location.latitude, location.longitude)
                }
                is LocationSelection.SpecificLocation -> {
                    current.fromSelection.latLon
                }
            }

            // Reset pipeline and start routing
            routePipeline.reset()
            routePipeline.computeRoute(
                from = from,
                to = toSelection.latLon,
                routeName = toSelection.name
            )
        }
    }

    /** Reset routing ready state after navigation has occurred. */
    fun onRouteNavigated() {
        _uiState.value = _uiState.value.copy(isRouteReady = false)
    }

    fun clearActiveField() {
        val current = _uiState.value
        _uiState.value = when (current.activeField) {
            ActiveField.FROM -> current.copy(fromSelection = LocationSelection.CurrentLocation)
            ActiveField.TO -> current.copy(toSelection = null)
        }
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

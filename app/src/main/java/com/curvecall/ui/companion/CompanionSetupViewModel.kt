package com.curvecall.ui.companion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.curvecall.data.location.LocationProvider
import com.curvecall.data.preferences.UserPreferences
import com.curvecall.data.regions.DownloadedRegion
import com.curvecall.data.regions.RegionRepository
import com.curvecall.engine.types.DrivingMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the companion mode setup screen.
 *
 * Checks required permissions (location, notification, overlay) and region
 * coverage before allowing the user to start companion mode.
 */
@HiltViewModel
class CompanionSetupViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val locationProvider: LocationProvider,
    private val regionRepository: RegionRepository,
    private val userPreferences: UserPreferences
) : ViewModel() {

    data class SetupState(
        val hasLocationPermission: Boolean = false,
        val hasNotificationPermission: Boolean = false,
        val hasOverlayPermission: Boolean = false,
        val currentRegion: DownloadedRegion? = null,
        val isCheckingRegion: Boolean = true,
        val drivingMode: DrivingMode = DrivingMode.CAR,
        val verbosity: Int = 2
    ) {
        val isReady: Boolean
            get() = hasLocationPermission && hasOverlayPermission && currentRegion != null
    }

    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state.asStateFlow()

    init {
        refreshPermissions()
        checkRegionCoverage()
        loadPreferences()
    }

    fun refreshPermissions() {
        _state.value = _state.value.copy(
            hasLocationPermission = ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED,
            hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    appContext, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else true,
            hasOverlayPermission = Settings.canDrawOverlays(appContext)
        )
    }

    private fun checkRegionCoverage() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isCheckingRegion = true)
            val location = locationProvider.getLastLocation()
            val region = if (location != null) {
                regionRepository.findRegionForCoordinate(
                    location.latitude, location.longitude
                )
            } else null
            _state.value = _state.value.copy(
                currentRegion = region,
                isCheckingRegion = false
            )
        }
    }

    private fun loadPreferences() {
        viewModelScope.launch {
            val mode = userPreferences.drivingMode.first()
            val verbosity = userPreferences.verbosity.first()
            _state.value = _state.value.copy(
                drivingMode = mode,
                verbosity = verbosity
            )
        }
    }

    fun setDrivingMode(mode: DrivingMode) {
        _state.value = _state.value.copy(drivingMode = mode)
        viewModelScope.launch { userPreferences.setDrivingMode(mode) }
    }

    fun cycleVerbosity() {
        val current = _state.value.verbosity
        val next = if (current >= 3) 1 else current + 1
        _state.value = _state.value.copy(verbosity = next)
        viewModelScope.launch { userPreferences.setVerbosity(next) }
    }
}

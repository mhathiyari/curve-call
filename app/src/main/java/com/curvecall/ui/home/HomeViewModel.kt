package com.curvecall.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.curvecall.data.preferences.UserPreferences
import com.curvecall.data.regions.RegionRepository
import com.curvecall.engine.types.DrivingMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
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
 * Manages driving mode selection and offline region status.
 * Route loading is handled entirely via the destination picker + RoutePipeline.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val userPreferences: UserPreferences,
    private val regionRepository: RegionRepository
) : ViewModel() {

    data class HomeUiState(
        val drivingMode: DrivingMode = DrivingMode.CAR,
        val hasOfflineRegions: Boolean = false
    )

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            userPreferences.drivingMode.collect { mode ->
                _uiState.value = _uiState.value.copy(drivingMode = mode)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val hasRegions = regionRepository.getDownloadedRegions().isNotEmpty()
            _uiState.value = _uiState.value.copy(hasOfflineRegions = hasRegions)
        }
    }

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
}

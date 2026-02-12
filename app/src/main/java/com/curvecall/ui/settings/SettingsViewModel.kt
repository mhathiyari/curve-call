package com.curvecall.ui.settings

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.curvecall.data.preferences.UserPreferences
import com.curvecall.engine.types.DrivingMode
import com.curvecall.engine.types.SpeedUnit
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Settings screen.
 * Reads and writes all user preferences (PRD Section 8.1).
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userPreferences: UserPreferences
) : ViewModel() {

    data class SettingsUiState(
        val drivingMode: DrivingMode = DrivingMode.CAR,
        val speedUnits: SpeedUnit = SpeedUnit.KMH,
        val verbosity: Int = 2,
        val lateralG: Double = 0.35,
        val lookAheadTime: Double = 8.0,
        val ttsSpeechRate: Float = 1.0f,
        val ttsVoiceName: String? = null,
        val narrateStraights: Boolean = false,
        val audioDucking: Boolean = true,
        val leanAngleNarration: Boolean = true,
        val surfaceWarnings: Boolean = true,
        val availableVoices: List<Voice> = emptyList()
    )

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        observePreferences()
    }

    private fun observePreferences() {
        viewModelScope.launch {
            combine(
                userPreferences.drivingMode,
                userPreferences.speedUnits,
                userPreferences.verbosity,
                userPreferences.lateralG,
                userPreferences.lookAheadTime
            ) { mode, units, verbosity, lateralG, lookAhead ->
                _uiState.value.copy(
                    drivingMode = mode,
                    speedUnits = units,
                    verbosity = verbosity,
                    lateralG = lateralG,
                    lookAheadTime = lookAhead
                )
            }.collect { state ->
                _uiState.value = state
            }
        }

        viewModelScope.launch {
            userPreferences.ttsSpeechRate.collect { rate ->
                _uiState.value = _uiState.value.copy(ttsSpeechRate = rate)
            }
        }

        viewModelScope.launch {
            userPreferences.ttsVoiceName.collect { name ->
                _uiState.value = _uiState.value.copy(ttsVoiceName = name)
            }
        }

        viewModelScope.launch {
            userPreferences.narrateStraights.collect { enabled ->
                _uiState.value = _uiState.value.copy(narrateStraights = enabled)
            }
        }

        viewModelScope.launch {
            userPreferences.audioDucking.collect { enabled ->
                _uiState.value = _uiState.value.copy(audioDucking = enabled)
            }
        }

        viewModelScope.launch {
            userPreferences.leanAngleNarration.collect { enabled ->
                _uiState.value = _uiState.value.copy(leanAngleNarration = enabled)
            }
        }

        viewModelScope.launch {
            userPreferences.surfaceWarnings.collect { enabled ->
                _uiState.value = _uiState.value.copy(surfaceWarnings = enabled)
            }
        }
    }

    fun setDrivingMode(mode: DrivingMode) {
        viewModelScope.launch { userPreferences.setDrivingMode(mode) }
    }

    fun setSpeedUnits(units: SpeedUnit) {
        viewModelScope.launch { userPreferences.setSpeedUnits(units) }
    }

    fun setVerbosity(level: Int) {
        viewModelScope.launch { userPreferences.setVerbosity(level) }
    }

    fun setLateralG(value: Double) {
        viewModelScope.launch { userPreferences.setLateralG(value) }
    }

    fun setLookAheadTime(value: Double) {
        viewModelScope.launch { userPreferences.setLookAheadTime(value) }
    }

    fun setTtsSpeechRate(rate: Float) {
        viewModelScope.launch { userPreferences.setTtsSpeechRate(rate) }
    }

    fun setTtsVoiceName(name: String) {
        viewModelScope.launch { userPreferences.setTtsVoiceName(name) }
    }

    fun setNarrateStraights(enabled: Boolean) {
        viewModelScope.launch { userPreferences.setNarrateStraights(enabled) }
    }

    fun setAudioDucking(enabled: Boolean) {
        viewModelScope.launch { userPreferences.setAudioDucking(enabled) }
    }

    fun setLeanAngleNarration(enabled: Boolean) {
        viewModelScope.launch { userPreferences.setLeanAngleNarration(enabled) }
    }

    fun setSurfaceWarnings(enabled: Boolean) {
        viewModelScope.launch { userPreferences.setSurfaceWarnings(enabled) }
    }

    /**
     * Update the list of available TTS voices. Called from the composable
     * after initializing the TTS engine to enumerate system voices.
     */
    fun updateAvailableVoices(voices: List<Voice>) {
        _uiState.value = _uiState.value.copy(availableVoices = voices)
    }
}

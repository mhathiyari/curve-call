package com.curvecall.companion

/**
 * State sealed classes and UI state for companion mode.
 *
 * Companion mode runs CurveCue as a background curve narrator alongside
 * another navigation app (e.g., Google Maps). The user navigates with their
 * preferred app; CurveCue independently detects and narrates curves ahead.
 */

/** Session lifecycle state for companion mode. */
sealed class CompanionState {
    data object Idle : CompanionState()
    data object Starting : CompanionState()
    data object Active : CompanionState()
    data class Error(val message: String) : CompanionState()
    data object Stopped : CompanionState()
}

/** Observable UI state exposed by CompanionSessionViewModel. */
data class CompanionUiState(
    val state: CompanionState = CompanionState.Idle,
    val currentSpeedKmh: Double = 0.0,
    val currentSpeedMph: Double = 0.0,
    val lastNarrationText: String = "",
    val nextCurvePreview: String? = null,
    val nextCurveDistanceM: Double? = null,
    val isOffRoad: Boolean = false,
    val isGpsLost: Boolean = false,
    val narrationCount: Int = 0,
    val isMuted: Boolean = false,
    val verbosity: Int = 2
)

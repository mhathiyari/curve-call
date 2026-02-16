package com.curvecall.ui.preview

import androidx.lifecycle.ViewModel
import com.curvecall.data.preferences.UserPreferences
import com.curvecall.data.session.SessionDataHolder
import com.curvecall.engine.types.DrivingMode
import com.curvecall.engine.types.LatLon
import com.curvecall.engine.types.RouteSegment
import com.curvecall.engine.types.Severity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class RoutePreviewViewModel @Inject constructor(
    private val sessionDataHolder: SessionDataHolder,
    private val userPreferences: UserPreferences
) : ViewModel() {

    data class PreviewUiState(
        val routeName: String? = null,
        val routePoints: List<LatLon> = emptyList(),
        val routeSegments: List<RouteSegment> = emptyList(),
        val interpolatedPoints: List<LatLon> = emptyList(),
        val totalDistanceM: Double = 0.0,
        val estimatedTimeMs: Long = 0L,
        val curveCount: Int = 0,
        val severityCounts: Map<Severity, Int> = emptyMap(),
        val drivingMode: DrivingMode = DrivingMode.CAR,
        val hasData: Boolean = false
    )

    private val _uiState = MutableStateFlow(PreviewUiState())
    val uiState: StateFlow<PreviewUiState> = _uiState.asStateFlow()

    init {
        loadRouteData()
    }

    private fun loadRouteData() {
        if (!sessionDataHolder.hasData()) return

        val segments = sessionDataHolder.routeSegments ?: return
        val points = sessionDataHolder.routePoints ?: return
        val interpolated = sessionDataHolder.interpolatedPoints ?: emptyList()

        // Count curves by severity
        val severityCounts = mutableMapOf<Severity, Int>()
        var curveCount = 0
        var totalDistance = 0.0

        for (segment in segments) {
            when (segment) {
                is RouteSegment.Curve -> {
                    curveCount++
                    val sev = segment.data.severity
                    severityCounts[sev] = (severityCounts[sev] ?: 0) + 1
                    totalDistance += segment.data.arcLength
                }
                is RouteSegment.Straight -> {
                    totalDistance += segment.data.length
                }
            }
        }

        // Use routing engine time if available, otherwise estimate at 60 km/h
        val estimatedTimeMs = sessionDataHolder.timeMillis
            ?: (totalDistance / 16.67 * 1000).toLong()

        // Use routing engine distance if available
        val routeDistance = sessionDataHolder.distanceMeters ?: totalDistance

        _uiState.value = PreviewUiState(
            routeName = sessionDataHolder.routeName,
            routePoints = points,
            routeSegments = segments,
            interpolatedPoints = interpolated,
            totalDistanceM = routeDistance,
            estimatedTimeMs = estimatedTimeMs,
            curveCount = curveCount,
            severityCounts = severityCounts,
            hasData = true
        )
    }
}

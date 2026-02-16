package com.curvecall.data.routing

import com.curvecall.data.preferences.UserPreferences
import com.curvecall.data.regions.RegionRepository
import com.curvecall.data.session.SessionDataHolder
import com.curvecall.data.tiles.TileDownloader
import com.curvecall.engine.RouteAnalyzer
import com.curvecall.engine.types.AnalysisConfig
import com.curvecall.engine.types.DrivingMode
import com.curvecall.engine.types.LatLon
import com.curvecall.engine.types.RouteSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chains on-device routing (GraphHopper) with route analysis (RouteAnalyzer)
 * and session data preparation into a single pipeline.
 *
 * Usage:
 * 1. Call [computeRoute] with origin/destination coordinates
 * 2. Observe [state] for progress updates
 * 3. On [PipelineState.Ready], navigate to the session screen
 *
 * The pipeline is reusable — calling [computeRoute] again resets state and
 * starts a fresh computation.
 */
class RoutePipeline(
    private val graphHopperRouter: GraphHopperRouter,
    private val onlineRouter: OnlineRouter,
    private val routeAnalyzer: RouteAnalyzer,
    private val userPreferences: UserPreferences,
    private val sessionDataHolder: SessionDataHolder,
    private val tileDownloader: TileDownloader,
    private val regionRepository: RegionRepository
) {

    /**
     * Progress states emitted during the route computation pipeline.
     */
    sealed class PipelineState {
        object Idle : PipelineState()
        data class Routing(val message: String) : PipelineState()
        data class Analyzing(val message: String) : PipelineState()
        data class CachingTiles(val message: String) : PipelineState()
        data class Ready(val result: PipelineResult) : PipelineState()
        data class Error(val message: String) : PipelineState()
    }

    /**
     * Summary of the completed pipeline — route geometry plus analysis results.
     */
    data class PipelineResult(
        val routeResult: RouteResult,
        val segments: List<RouteSegment>,
        val interpolatedPoints: List<LatLon>,
        val curveCount: Int,
        val totalDistance: Double,
    )

    private val _state = MutableStateFlow<PipelineState>(PipelineState.Idle)
    val state: StateFlow<PipelineState> = _state.asStateFlow()

    /**
     * Run the full route pipeline: routing -> analysis -> session storage -> tile caching.
     *
     * This is a suspend function that should be called from a coroutine scope
     * (e.g. viewModelScope). It updates [state] at each stage so the UI can
     * show progress.
     *
     * @param from starting coordinate (typically current GPS location)
     * @param to destination coordinate
     * @param routeName optional display name for the route
     */
    suspend fun computeRoute(from: LatLon, to: LatLon, routeName: String?) {
        try {
            // -- Step 1: Route (offline preferred, online fallback) --

            _state.value = PipelineState.Routing("Preparing routing data...")

            val mode = userPreferences.drivingMode.first()
            val profile = when (mode) {
                DrivingMode.CAR -> "car"
                DrivingMode.MOTORCYCLE -> "motorcycle"
            }

            val routeResult: RouteResult

            // Try offline routing first
            val region = withContext(Dispatchers.IO) {
                regionRepository.findRegionForCoordinate(to.lat, to.lon)
            }

            if (region != null || graphHopperRouter.isGraphLoaded) {
                // Offline path: load graph if needed, then route locally
                if (!graphHopperRouter.isGraphLoaded && region != null) {
                    val graphDir = regionRepository.getGraphDir(region.id)
                    if (!graphDir.exists() || !graphDir.isDirectory) {
                        _state.value = PipelineState.Error(
                            "Region data for ${region.name} is corrupted. Try deleting and re-downloading."
                        )
                        return
                    }
                    _state.value = PipelineState.Routing("Loading ${region.name} routing data...")
                    try {
                        withContext(Dispatchers.IO) {
                            graphHopperRouter.loadGraph(graphDir)
                        }
                    } catch (e: Exception) {
                        _state.value = PipelineState.Error(
                            "Failed to load routing data: ${e.message}"
                        )
                        return
                    }
                }

                _state.value = PipelineState.Routing("Computing route...")

                routeResult = try {
                    withContext(Dispatchers.Default) {
                        graphHopperRouter.route(from, to, profile)
                    }
                } catch (e: RoutingException) {
                    _state.value = PipelineState.Error(
                        "Could not find a route. Try a different destination."
                    )
                    return
                }
            } else {
                // Online fallback: no offline region, use OSRM
                _state.value = PipelineState.Routing("Computing route online...")

                routeResult = try {
                    onlineRouter.route(from, to)
                } catch (e: RoutingException) {
                    _state.value = PipelineState.Error(
                        "Online routing failed. Check your internet connection."
                    )
                    return
                } catch (e: Exception) {
                    _state.value = PipelineState.Error(
                        "Online routing failed: ${e.message}"
                    )
                    return
                }
            }

            // -- Step 2: Analyze route geometry --

            _state.value = PipelineState.Analyzing(
                "Analyzing route geometry (${routeResult.points.size} points)..."
            )

            val lateralG = userPreferences.lateralG.first()
            val config = AnalysisConfig(
                lateralG = lateralG,
                isMotorcycleMode = mode == DrivingMode.MOTORCYCLE
            )

            val analysisResult = try {
                withContext(Dispatchers.Default) {
                    routeAnalyzer.analyzeRouteDetailed(routeResult.points, config)
                }
            } catch (e: IllegalArgumentException) {
                _state.value = PipelineState.Error(
                    "Route too short for curve analysis."
                )
                return
            }

            // -- Step 3: Store in SessionDataHolder --

            sessionDataHolder.setRouteData(
                segments = analysisResult.segments,
                points = routeResult.points,
                interpolated = analysisResult.interpolatedPoints,
                name = routeName,
                distance = routeResult.distanceMeters,
                time = routeResult.timeMillis
            )

            // -- Step 4: Trigger tile download (non-blocking) --

            _state.value = PipelineState.CachingTiles("Caching map tiles...")

            CoroutineScope(Dispatchers.IO).launch {
                tileDownloader.downloadForRoute(analysisResult.interpolatedPoints)
            }

            // -- Step 5: Done — don't wait for tile download --

            val result = PipelineResult(
                routeResult = routeResult,
                segments = analysisResult.segments,
                interpolatedPoints = analysisResult.interpolatedPoints,
                curveCount = analysisResult.curveCount,
                totalDistance = analysisResult.totalDistance,
            )

            _state.value = PipelineState.Ready(result)

        } catch (e: Exception) {
            _state.value = PipelineState.Error(
                e.message ?: "An unexpected error occurred during route computation."
            )
        }
    }

    /**
     * Reset pipeline state back to idle. Call when navigating away from the
     * route computation screen or before starting a new computation.
     */
    fun reset() {
        _state.value = PipelineState.Idle
        tileDownloader.reset()
    }
}

package com.curvecall.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.curvecall.engine.types.LatLon
import com.curvecall.engine.types.RouteSegment
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView

/**
 * Dark map tile source (CartoDB Dark Matter).
 * Matches the dark UI theme and makes colored route overlays more visible.
 */
private val DarkTileSource = object : OnlineTileSourceBase(
    "CartoDark",
    0, 19, 256, ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/dark_all/",
        "https://b.basemaps.cartocdn.com/dark_all/",
        "https://c.basemaps.cartocdn.com/dark_all/"
    )
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "$baseUrl$zoom/$x/$y$mImageFilenameEnding"
    }
}

/**
 * Interactive route preview map for the Home screen.
 * Shows the full route color-coded by curve severity, zoomed to fit.
 */
@Composable
fun RoutePreviewMap(
    routePoints: List<LatLon>,
    routeSegments: List<RouteSegment>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        MapView(context).apply {
            setTileSource(DarkTileSource)
            setMultiTouchControls(true)
            isHorizontalMapRepetitionEnabled = false
            isVerticalMapRepetitionEnabled = false
            minZoomLevel = 5.0
            maxZoomLevel = 19.0
        }
    }

    // Lifecycle management
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { mv ->
            // Clear existing overlays
            mv.overlays.clear()

            // Add route polylines (density-aware)
            val density = context.resources.displayMetrics.density
            val polylines = RouteOverlay.buildPolylines(routeSegments, routePoints, density)
            mv.overlays.addAll(polylines)

            // Zoom to fit route with padding
            if (routePoints.size >= 2) {
                val boundingBox = boundingBoxFromPoints(
                    routePoints.map { GeoPoint(it.lat, it.lon) }
                )
                mv.post {
                    mv.zoomToBoundingBox(boundingBox, false, 48)
                }
            }

            mv.invalidate()
        }
    )
}

/**
 * Full-screen session map with GPS tracking in heading-up mode.
 * Shows the route color-coded by severity, current position with directional arrow,
 * and accuracy circle. Map rotates to match current bearing.
 * Supports dynamic zoom based on speed and curve proximity.
 */
@Composable
fun SessionMap(
    routePoints: List<LatLon>,
    routeSegments: List<RouteSegment>,
    currentPosition: LatLon?,
    currentBearing: Float,
    currentAccuracy: Float,
    targetZoom: Double = 16.0,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val gpsMarkerOverlay = remember { GpsMarkerOverlay() }
    // Track the last zoom level we applied so we only animate when it changes meaningfully
    val lastAppliedZoom = remember { mutableDoubleStateOf(16.0) }
    val mapView = remember {
        MapView(context).apply {
            setTileSource(DarkTileSource)
            setMultiTouchControls(true)
            isHorizontalMapRepetitionEnabled = false
            isVerticalMapRepetitionEnabled = false
            minZoomLevel = 5.0
            maxZoomLevel = 19.0
            controller.setZoom(16.0)
        }
    }

    // Lifecycle management
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    // Add overlays once when route data is available
    DisposableEffect(routeSegments, routePoints) {
        mapView.overlays.clear()
        val density = context.resources.displayMetrics.density
        val polylines = RouteOverlay.buildPolylines(routeSegments, routePoints, density)
        mapView.overlays.addAll(polylines)
        mapView.overlays.add(gpsMarkerOverlay)

        // Initial zoom to route if no GPS position yet
        if (currentPosition == null && routePoints.size >= 2) {
            val boundingBox = boundingBoxFromPoints(
                routePoints.map { GeoPoint(it.lat, it.lon) }
            )
            mapView.post {
                mapView.zoomToBoundingBox(boundingBox, false, 48)
            }
        }

        onDispose { }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { mv ->
            // Update GPS marker
            if (currentPosition != null) {
                val geoPoint = GeoPoint(currentPosition.lat, currentPosition.lon)
                gpsMarkerOverlay.position = geoPoint
                gpsMarkerOverlay.bearing = currentBearing
                gpsMarkerOverlay.accuracy = currentAccuracy

                // Heading-up: rotate map opposite to bearing
                mv.mapOrientation = -currentBearing

                // Set position immediately (no animation) to avoid flicker
                mv.controller.setCenter(geoPoint)

                // Only animate zoom when it changes by more than 0.2 levels
                val zoomDelta = kotlin.math.abs(targetZoom - lastAppliedZoom.doubleValue)
                if (zoomDelta > 0.2) {
                    mv.controller.animateTo(geoPoint, targetZoom, 600L)
                    lastAppliedZoom.doubleValue = targetZoom
                }
            }

            mv.invalidate()
        }
    )
}

/**
 * Compute bounding box from a list of GeoPoints, handling edge cases.
 */
private fun boundingBoxFromPoints(points: List<GeoPoint>): BoundingBox {
    if (points.isEmpty()) return BoundingBox(0.0, 0.0, 0.0, 0.0)

    var minLat: Double = points[0].latitude
    var maxLat: Double = points[0].latitude
    var minLon: Double = points[0].longitude
    var maxLon: Double = points[0].longitude

    for (p in points) {
        if (p.latitude < minLat) minLat = p.latitude
        if (p.latitude > maxLat) maxLat = p.latitude
        if (p.longitude < minLon) minLon = p.longitude
        if (p.longitude > maxLon) maxLon = p.longitude
    }

    return BoundingBox(maxLat, maxLon, minLat, minLon)
}

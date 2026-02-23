package com.curvecall.ui.destination

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.curvecall.data.geocoding.GeocodingService
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.curvecall.ui.theme.CurveCuePrimary
import com.curvecall.ui.theme.DarkBackground
import com.curvecall.ui.theme.DarkSurfaceElevated
import com.curvecall.ui.theme.DarkSurfaceHighest
import com.curvecall.ui.theme.SeverityGentle
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

/**
 * Dark map tile source (CartoDB Dark Matter).
 * Same as used in OsmMapView.kt — matches the dark UI theme.
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
 * Route planning screen with FROM and TO fields.
 *
 * Lets the user choose origin and destination via:
 * - Text search (Nominatim geocoding)
 * - Long-press pin drop on the map
 * - Selecting from recent/favorite destinations
 *
 * FROM defaults to "Current Location" (GPS) but can be changed to any location.
 * The active field (FROM or TO) receives search results and map pin drops.
 *
 * @param onNavigateBack Called when the back button is pressed
 * @param onDestinationConfirmed Called with (lat, lon, name) when routing completes
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DestinationScreen(
    onNavigateBack: () -> Unit,
    onDestinationConfirmed: (lat: Double, lon: Double, name: String) -> Unit,
    viewModel: DestinationViewModel = hiltViewModel()
) {
    // Request location permission upfront — needed for "Route Here" GPS fix
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result observed but not gating UI — SecurityException catch in VM handles denial */ }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Show errors via snackbar
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Navigate to route preview when routing completes
    LaunchedEffect(uiState.isRouteReady) {
        if (uiState.isRouteReady) {
            val to = uiState.toSelection as? LocationSelection.SpecificLocation
            onDestinationConfirmed(
                to?.latLon?.lat ?: 0.0,
                to?.latLon?.lon ?: 0.0,
                to?.name ?: ""
            )
            viewModel.onRouteNavigated()
        }
    }

    // Derive display names
    val fromDisplayName = when (uiState.fromSelection) {
        is LocationSelection.CurrentLocation -> "Current Location"
        is LocationSelection.SpecificLocation ->
            (uiState.fromSelection as LocationSelection.SpecificLocation).name
    }
    val toDisplayName = (uiState.toSelection as? LocationSelection.SpecificLocation)?.name ?: ""
    val isFromCurrentLocation = uiState.fromSelection is LocationSelection.CurrentLocation

    // Extract coordinates for map markers
    val fromCoords = (uiState.fromSelection as? LocationSelection.SpecificLocation)?.latLon
    val toCoords = (uiState.toSelection as? LocationSelection.SpecificLocation)?.latLon

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Plan Route",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Map + FROM/TO bar + overlays in a single Box so Compose
            // elements draw on top of the native AndroidView (osmdroid).
            Box(modifier = Modifier.weight(1f)) {
                // -- Map view (drawn first, behind everything) --
                DestinationMapView(
                    fromLat = fromCoords?.lat,
                    fromLon = fromCoords?.lon,
                    toLat = toCoords?.lat,
                    toLon = toCoords?.lon,
                    onMapLongPress = viewModel::onMapLongPress,
                    modifier = Modifier.fillMaxSize()
                )

                // -- FROM / TO field bar overlaid on top of map --
                FromToFieldBar(
                    fromDisplayName = fromDisplayName,
                    toDisplayName = toDisplayName,
                    activeField = uiState.activeField,
                    searchQuery = uiState.searchQuery,
                    isSearching = uiState.isSearching,
                    isFromCurrentLocation = isFromCurrentLocation,
                    onActiveFieldChanged = viewModel::onActiveFieldChanged,
                    onSearchQueryChanged = viewModel::onSearchQueryChanged,
                    onSwap = viewModel::onSwapFields,
                    onClearFrom = {
                        viewModel.onActiveFieldChanged(ActiveField.FROM)
                        viewModel.onResetToCurrentLocation()
                    },
                    onClearTo = {
                        viewModel.onActiveFieldChanged(ActiveField.TO)
                        viewModel.clearActiveField()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .align(Alignment.TopStart)
                )

                // -- Search results overlay (below FROM/TO bar) --
                androidx.compose.animation.AnimatedVisibility(
                    visible = uiState.searchResults.isNotEmpty(),
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut() + slideOutVertically(),
                    modifier = Modifier
                        .padding(top = 136.dp)
                        .align(Alignment.TopStart)
                ) {
                    SearchResultsList(
                        results = uiState.searchResults,
                        onResultSelected = { result ->
                            viewModel.onSearchResultSelected(result)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    )
                }

                // -- "Route" button when TO is selected --
                androidx.compose.animation.AnimatedVisibility(
                    visible = uiState.toSelection != null,
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut() + slideOutVertically { it },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                ) {
                    if (uiState.isRouting) {
                        RoutingProgressCard(message = uiState.routingMessage)
                    } else if (uiState.toSelection != null) {
                        RouteButton(
                            fromName = fromDisplayName,
                            toName = toDisplayName,
                            onRoute = { viewModel.onDestinationConfirmed() }
                        )
                    }
                }
            }

            // -- Bottom section: Recents, Favorites --
            BottomSection(
                activeField = uiState.activeField,
                recentDestinations = uiState.recentDestinations,
                favoriteDestinations = uiState.favoriteDestinations,
                onDestinationSelected = viewModel::onSavedDestinationSelected,
                onToggleFavorite = viewModel::toggleFavorite,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            )
        }
    }
}

// ============================================================
// FROM / TO Field Bar
// ============================================================

private val FromIndicatorColor = SeverityGentle  // Green for origin
private val ToIndicatorColor = CurveCuePrimary   // Amber for destination

@Composable
private fun FromToFieldBar(
    fromDisplayName: String,
    toDisplayName: String,
    activeField: ActiveField,
    searchQuery: String,
    isSearching: Boolean,
    isFromCurrentLocation: Boolean,
    onActiveFieldChanged: (ActiveField) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onSwap: () -> Unit,
    onClearFrom: () -> Unit,
    onClearTo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(14.dp)
    val activeBorderColor = CurveCuePrimary.copy(alpha = 0.6f)

    Card(
        modifier = modifier
            .border(
                width = 1.5.dp,
                color = activeBorderColor,
                shape = shape
            ),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2E2822)),
        shape = shape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: route indicators (dots + dotted line)
            Column(
                modifier = Modifier
                    .padding(start = 14.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // FROM dot (green)
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(FromIndicatorColor, CircleShape)
                )
                // Dotted line connecting dots
                val dottedLineColor = Color.White.copy(alpha = 0.3f)
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(32.dp)
                        .drawBehind {
                            drawLine(
                                color = dottedLineColor,
                                start = Offset(size.width / 2, 0f),
                                end = Offset(size.width / 2, size.height),
                                strokeWidth = 2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(
                                    floatArrayOf(4.dp.toPx(), 4.dp.toPx())
                                )
                            )
                        }
                )
                // TO dot (amber)
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(ToIndicatorColor, CircleShape)
                )
            }

            // Center: field rows
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp)
            ) {
                // FROM row
                FieldRow(
                    label = "FROM",
                    displayName = fromDisplayName,
                    isActive = activeField == ActiveField.FROM,
                    searchQuery = if (activeField == ActiveField.FROM) searchQuery else "",
                    isSearching = if (activeField == ActiveField.FROM) isSearching else false,
                    placeholder = "Search start...",
                    showMyLocationIcon = isFromCurrentLocation && activeField != ActiveField.FROM,
                    onTap = { onActiveFieldChanged(ActiveField.FROM) },
                    onQueryChanged = onSearchQueryChanged,
                    onClear = onClearFrom
                )

                HorizontalDivider(
                    color = Color.White.copy(alpha = 0.1f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(end = 8.dp)
                )

                // TO row
                FieldRow(
                    label = "TO",
                    displayName = toDisplayName,
                    isActive = activeField == ActiveField.TO,
                    searchQuery = if (activeField == ActiveField.TO) searchQuery else "",
                    isSearching = if (activeField == ActiveField.TO) isSearching else false,
                    placeholder = "Search destination...",
                    showMyLocationIcon = false,
                    onTap = { onActiveFieldChanged(ActiveField.TO) },
                    onQueryChanged = onSearchQueryChanged,
                    onClear = onClearTo
                )
            }

            // Right: swap button
            IconButton(
                onClick = onSwap,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SwapVert,
                    contentDescription = "Swap from and to",
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun FieldRow(
    label: String,
    displayName: String,
    isActive: Boolean,
    searchQuery: String,
    isSearching: Boolean,
    placeholder: String,
    showMyLocationIcon: Boolean,
    onTap: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onClear: () -> Unit
) {
    if (isActive) {
        // Active: show editable TextField
        TextField(
            value = searchQuery,
            onValueChange = onQueryChanged,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            placeholder = {
                Text(
                    text = placeholder,
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            leadingIcon = {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.width(38.dp)
                )
            },
            trailingIcon = {
                if (isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = CurveCuePrimary,
                        strokeWidth = 2.dp
                    )
                } else if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onQueryChanged("") }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear search",
                            modifier = Modifier.size(16.dp),
                            tint = Color.White.copy(alpha = 0.5f)
                        )
                    }
                }
            },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF332D27),
                unfocusedContainerColor = Color(0xFF332D27),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = CurveCuePrimary,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            )
        )
    } else {
        // Inactive: show read-only display
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clickable { onTap() }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.width(38.dp)
            )
            if (showMyLocationIcon) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = FromIndicatorColor.copy(alpha = 0.8f)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = displayName.ifEmpty { placeholder },
                style = MaterialTheme.typography.bodyMedium,
                color = if (displayName.isNotEmpty()) Color.White.copy(alpha = 0.85f)
                else Color.White.copy(alpha = 0.4f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (displayName.isNotEmpty()) {
                IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear",
                        modifier = Modifier.size(14.dp),
                        tint = Color.White.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }
}

// ============================================================
// Search Results List (overlay)
// ============================================================

@Composable
private fun SearchResultsList(
    results: List<GeocodingService.SearchResult>,
    onResultSelected: (GeocodingService.SearchResult) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
        shape = RoundedCornerShape(12.dp)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            items(results) { result ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onResultSelected(result) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = CurveCuePrimary.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        // Show the first part (name) more prominently
                        val parts = result.displayName.split(",", limit = 2)
                        Text(
                            text = parts.first().trim(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (parts.size > 1) {
                            Text(
                                text = parts[1].trim(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// Destination Map View (with long-press pin drop, dual markers)
// ============================================================

@Composable
private fun DestinationMapView(
    fromLat: Double?,
    fromLon: Double?,
    toLat: Double?,
    toLon: Double?,
    onMapLongPress: (Double, Double) -> Unit,
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
            minZoomLevel = 3.0
            maxZoomLevel = 19.0
            controller.setZoom(5.0)
            // Center on continental US by default
            controller.setCenter(GeoPoint(39.0, -98.0))
        }
    }

    val fromMarker = remember {
        Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Start"
        }
    }

    val toMarker = remember {
        Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Destination"
        }
    }

    // Long-press handler
    val mapEventsOverlay = remember {
        MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (p != null) {
                    onMapLongPress(p.latitude, p.longitude)
                }
                return true
            }
        })
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
        factory = {
            mapView.apply {
                overlays.add(0, mapEventsOverlay)
            }
        },
        modifier = modifier,
        update = { mv ->
            // Update FROM marker
            mv.overlays.remove(fromMarker)
            if (fromLat != null && fromLon != null) {
                fromMarker.position = GeoPoint(fromLat, fromLon)
                mv.overlays.add(fromMarker)
            }

            // Update TO marker
            mv.overlays.remove(toMarker)
            if (toLat != null && toLon != null) {
                toMarker.position = GeoPoint(toLat, toLon)
                mv.overlays.add(toMarker)
            }

            // Zoom to fit both markers, or single marker, or do nothing
            if (fromLat != null && fromLon != null && toLat != null && toLon != null) {
                val north = maxOf(fromLat, toLat)
                val south = minOf(fromLat, toLat)
                val east = maxOf(fromLon, toLon)
                val west = minOf(fromLon, toLon)
                // Add padding so markers aren't at the very edge
                val latPad = (north - south) * 0.3 + 0.01
                val lonPad = (east - west) * 0.3 + 0.01
                val box = BoundingBox(
                    north + latPad, east + lonPad,
                    south - latPad, west - lonPad
                )
                mv.zoomToBoundingBox(box, true)
            } else if (toLat != null && toLon != null) {
                mv.controller.animateTo(GeoPoint(toLat, toLon), 14.0, 600L)
            } else if (fromLat != null && fromLon != null) {
                mv.controller.animateTo(GeoPoint(fromLat, fromLon), 14.0, 600L)
            }

            mv.invalidate()
        }
    )
}

// ============================================================
// Route Button
// ============================================================

@Composable
private fun RoutingProgressCard(message: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = CurveCuePrimary,
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = message.ifEmpty { "Computing route..." },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun RouteButton(
    fromName: String,
    toName: String,
    onRoute: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // FROM → TO summary
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(FromIndicatorColor, CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = fromName,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(ToIndicatorColor, CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = toName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Route button with gradient border (matching HomeScreen style)
            val shape = RoundedCornerShape(12.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .border(
                        width = 1.5.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                CurveCuePrimary.copy(alpha = 0.7f),
                                CurveCuePrimary.copy(alpha = 0.3f),
                                CurveCuePrimary.copy(alpha = 0.7f)
                            )
                        ),
                        shape = shape
                    )
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                CurveCuePrimary.copy(alpha = 0.15f),
                                CurveCuePrimary.copy(alpha = 0.05f)
                            )
                        ),
                        shape = shape
                    )
                    .clip(shape)
                    .clickable { onRoute() },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.NearMe,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = CurveCuePrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Route",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

// ============================================================
// Bottom Section: Recents + Favorites
// ============================================================

@Composable
private fun BottomSection(
    activeField: ActiveField,
    recentDestinations: List<DestinationViewModel.SavedDestination>,
    favoriteDestinations: List<DestinationViewModel.SavedDestination>,
    onDestinationSelected: (DestinationViewModel.SavedDestination) -> Unit,
    onToggleFavorite: (DestinationViewModel.SavedDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Active field indicator
            item {
                Text(
                    text = "Select for ${if (activeField == ActiveField.FROM) "FROM" else "TO"}",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                    color = if (activeField == ActiveField.FROM) FromIndicatorColor.copy(alpha = 0.7f)
                    else ToIndicatorColor.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }

            // Favorites section
            if (favoriteDestinations.isNotEmpty()) {
                item {
                    SectionHeader(title = "FAVORITES")
                }
                items(favoriteDestinations) { destination ->
                    SavedDestinationItem(
                        destination = destination,
                        onClick = { onDestinationSelected(destination) },
                        onToggleFavorite = { onToggleFavorite(destination) }
                    )
                }
            }

            // Recent destinations section
            if (recentDestinations.isNotEmpty()) {
                item {
                    SectionHeader(title = "RECENT")
                }
                items(recentDestinations) { destination ->
                    SavedDestinationItem(
                        destination = destination,
                        onClick = { onDestinationSelected(destination) },
                        onToggleFavorite = { onToggleFavorite(destination) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall.copy(
            letterSpacing = 2.sp
        ),
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun SavedDestinationItem(
    destination: DestinationViewModel.SavedDestination,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DarkSurfaceHighest)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Location icon
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    color = CurveCuePrimary.copy(alpha = 0.1f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (destination.isFavorite) Icons.Default.Star
                else Icons.Default.LocationOn,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (destination.isFavorite) CurveCuePrimary
                else CurveCuePrimary.copy(alpha = 0.7f)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // Name (truncated first part of display_name)
        val shortName = destination.name.split(",").first().trim()
        Text(
            text = shortName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        // Favorite toggle
        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = if (destination.isFavorite) Icons.Default.Favorite
                else Icons.Default.FavoriteBorder,
                contentDescription = if (destination.isFavorite) "Unfavorite" else "Favorite",
                modifier = Modifier.size(16.dp),
                tint = if (destination.isFavorite) CurveCuePrimary
                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
            )
        }
    }
}

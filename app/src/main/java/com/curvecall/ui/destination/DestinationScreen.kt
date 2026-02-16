package com.curvecall.ui.destination

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.curvecall.ui.theme.CurveCallPrimary
import com.curvecall.ui.theme.DarkBackground
import com.curvecall.ui.theme.DarkSurfaceElevated
import com.curvecall.ui.theme.DarkSurfaceHighest
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
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
 * Destination picker screen.
 *
 * Lets the user choose a destination via:
 * - Text search (Nominatim geocoding)
 * - Long-press pin drop on the map
 * - Selecting from recent/favorite destinations
 *
 * When a destination is confirmed, [onDestinationConfirmed] is called with
 * the selected lat/lon and name, allowing the navigation layer to proceed
 * to routing.
 *
 * @param onNavigateBack Called when the back button is pressed
 * @param onDestinationConfirmed Called with (lat, lon, name) when the user confirms a destination
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DestinationScreen(
    onNavigateBack: () -> Unit,
    onDestinationConfirmed: (lat: Double, lon: Double, name: String) -> Unit,
    viewModel: DestinationViewModel = hiltViewModel()
) {
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
            val dest = uiState.selectedDestination
            onDestinationConfirmed(
                dest?.latLon?.lat ?: 0.0,
                dest?.latLon?.lon ?: 0.0,
                dest?.name ?: ""
            )
            viewModel.onRouteNavigated()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Choose Destination",
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
            // -- Search bar --
            SearchBar(
                query = uiState.searchQuery,
                isSearching = uiState.isSearching,
                onQueryChanged = viewModel::onSearchQueryChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // -- Search results dropdown (overlays the map) --
            Box(modifier = Modifier.weight(1f)) {
                // -- Map view --
                DestinationMapView(
                    selectedLat = uiState.selectedDestination?.latLon?.lat,
                    selectedLon = uiState.selectedDestination?.latLon?.lon,
                    onMapLongPress = viewModel::onMapLongPress,
                    modifier = Modifier.fillMaxSize()
                )

                // -- Search results overlay --
                androidx.compose.animation.AnimatedVisibility(
                    visible = uiState.searchResults.isNotEmpty(),
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut() + slideOutVertically()
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

                // -- "Route Here" FAB when destination is selected --
                androidx.compose.animation.AnimatedVisibility(
                    visible = uiState.selectedDestination != null,
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut() + slideOutVertically { it },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                ) {
                    if (uiState.isRouting) {
                        // Show routing progress
                        RoutingProgressCard(message = uiState.routingMessage)
                    } else {
                        uiState.selectedDestination?.let { dest ->
                            RouteHereButton(
                                destinationName = dest.name,
                                onRouteHere = { viewModel.onDestinationConfirmed() },
                                onDismiss = viewModel::clearSelection
                            )
                        }
                    }
                }
            }

            // -- Bottom section: Recents, Favorites --
            BottomSection(
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
// Search Bar
// ============================================================

@Composable
private fun SearchBar(
    query: String,
    isSearching: Boolean,
    onQueryChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(14.dp)
    TextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = modifier
            .height(56.dp)
            .border(
                width = 1.5.dp,
                color = CurveCallPrimary.copy(alpha = 0.5f),
                shape = shape
            )
            .clip(shape),
        placeholder = {
            Text(
                text = "Search destination...",
                color = Color.White.copy(alpha = 0.6f)
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = CurveCallPrimary
            )
        },
        trailingIcon = {
            if (isSearching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = CurveCallPrimary,
                    strokeWidth = 2.dp
                )
            } else if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChanged("") }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear",
                        tint = Color.White.copy(alpha = 0.5f)
                    )
                }
            }
        },
        singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color(0xFF333333),
            unfocusedContainerColor = Color(0xFF2E2E2E),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = CurveCallPrimary,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        ),
        shape = shape
    )
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
                        tint = CurveCallPrimary.copy(alpha = 0.7f)
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
// Destination Map View (with long-press pin drop)
// ============================================================

@Composable
private fun DestinationMapView(
    selectedLat: Double?,
    selectedLon: Double?,
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

    val destinationMarker = remember {
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
            // Update destination marker
            mv.overlays.remove(destinationMarker)
            if (selectedLat != null && selectedLon != null) {
                val point = GeoPoint(selectedLat, selectedLon)
                destinationMarker.position = point
                mv.overlays.add(destinationMarker)
                // Animate to selected point
                mv.controller.animateTo(point, 14.0, 600L)
            }
            mv.invalidate()
        }
    )
}

// ============================================================
// "Route Here" Button
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
                color = CurveCallPrimary,
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
private fun RouteHereButton(
    destinationName: String,
    onRouteHere: () -> Unit,
    onDismiss: () -> Unit
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = CurveCallPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = destinationName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Route Here button with gradient border (matching HomeScreen style)
            val shape = RoundedCornerShape(12.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .border(
                        width = 1.5.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                CurveCallPrimary.copy(alpha = 0.7f),
                                CurveCallPrimary.copy(alpha = 0.3f),
                                CurveCallPrimary.copy(alpha = 0.7f)
                            )
                        ),
                        shape = shape
                    )
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                CurveCallPrimary.copy(alpha = 0.15f),
                                CurveCallPrimary.copy(alpha = 0.05f)
                            )
                        ),
                        shape = shape
                    )
                    .clip(shape)
                    .clickable { onRouteHere() },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.NearMe,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = CurveCallPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Route Here",
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
// Bottom Section: Recents + Favorites + Load GPX
// ============================================================

@Composable
private fun BottomSection(
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
                    color = CurveCallPrimary.copy(alpha = 0.1f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (destination.isFavorite) Icons.Default.Star
                else Icons.Default.LocationOn,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (destination.isFavorite) CurveCallPrimary
                else CurveCallPrimary.copy(alpha = 0.7f)
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
                tint = if (destination.isFavorite) CurveCallPrimary
                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
            )
        }
    }
}


package com.curvecall.ui.home

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.curvecall.engine.types.DrivingMode
import com.curvecall.ui.map.RoutePreviewMap
import com.curvecall.ui.theme.CurveCallPrimary
import com.curvecall.ui.theme.CurveCallPrimaryDim
import com.curvecall.ui.theme.CurveCallPrimaryVariant
import com.curvecall.ui.theme.DarkBackground
import com.curvecall.ui.theme.DarkSurfaceElevated
import com.curvecall.ui.theme.SeverityModerate
import kotlinx.coroutines.delay

/**
 * Home screen — premium "instrument panel" aesthetic.
 *
 * Features a custom S-curve logo with headlight glow, radial gradient backdrop,
 * staggered entrance animations, glow-bordered mode toggle, and refined route cards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToDestination: () -> Unit = {},
    onNavigateToRoutePreview: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // SAF file picker launcher
    val context = LocalContext.current
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) { /* best-effort */ }
            viewModel.loadGpxFile(it)
        }
    }

    // Navigate to route preview when route is analyzed and ready
    LaunchedEffect(uiState.isReadyForSession) {
        if (uiState.isReadyForSession) {
            onNavigateToRoutePreview()
            viewModel.resetSessionState()
        }
    }

    // Show errors via snackbar
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Static layout — no entrance animation (keeps composition stable)
    val logoAlpha = remember { Animatable(1f) }
    val logoOffset = remember { Animatable(0f) }
    val contentAlpha = remember { Animatable(1f) }
    val contentOffset = remember { Animatable(0f) }
    val bottomAlpha = remember { Animatable(1f) }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = DarkBackground
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBackground)
                .padding(paddingValues)
        ) {
            // -- Radial gradient backdrop behind logo (headlight on dark road) --
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    CurveCallPrimary.copy(alpha = 0.08f),
                                    CurveCallPrimaryDim.copy(alpha = 0.03f),
                                    Color.Transparent
                                ),
                                center = Offset(size.width / 2f, size.height * 0.18f),
                                radius = size.width * 0.9f
                            )
                        )
                    }
            )

            // -- Settings button (top-right) --
            IconButton(
                onClick = onNavigateToSettings,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }

            // -- Main content --
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(48.dp))

                // ===== HERO: Logo + Title =====
                Box(
                    modifier = Modifier
                        .alpha(logoAlpha.value)
                        .offset { IntOffset(0, logoOffset.value.dp.roundToPx()) },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CurveCallLogo(size = 100.dp)

                        Spacer(modifier = Modifier.height(20.dp))

                        Text(
                            text = "CurveCall",
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontSize = 32.sp,
                                letterSpacing = 2.sp
                            ),
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "YOUR DIGITAL CO-DRIVER",
                            style = MaterialTheme.typography.labelSmall.copy(
                                letterSpacing = 3.sp,
                                fontSize = 11.sp
                            ),
                            color = CurveCallPrimary.copy(alpha = 0.6f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(48.dp))

                // ===== CONTROLS =====
                Column(
                    modifier = Modifier
                        .alpha(contentAlpha.value)
                        .offset { IntOffset(0, contentOffset.value.dp.roundToPx()) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // -- Driving Mode Toggle --
                    DrivingModeToggle(
                        currentMode = uiState.drivingMode,
                        onToggle = { viewModel.toggleDrivingMode() }
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // -- No offline data warning --
                    AnimatedVisibility(
                        visible = !uiState.hasOfflineRegions,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(SeverityModerate.copy(alpha = 0.1f))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "No offline regions downloaded. Go to Settings to download one.",
                                style = MaterialTheme.typography.bodySmall,
                                color = SeverityModerate.copy(alpha = 0.8f),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // -- Pick Destination Button (primary, gradient border) --
                    PickDestinationButton(
                        onClick = onNavigateToDestination
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // -- Load GPX Button (secondary) --
                    LoadRouteButton(
                        isLoading = uiState.isLoading,
                        loadingMessage = uiState.loadingMessage,
                        onClick = {
                            filePickerLauncher.launch(arrayOf(
                                "application/gpx+xml",
                                "application/xml",
                                "text/xml",
                                "*/*"
                            ))
                        }
                    )

                    // Loading track point count
                    AnimatedVisibility(
                        visible = uiState.isLoading && uiState.routePointCount > 0,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Text(
                            text = "${uiState.routePointCount} track points",
                            style = MaterialTheme.typography.bodySmall,
                            color = CurveCallPrimary.copy(alpha = 0.5f),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    // Route preview map
                    AnimatedVisibility(
                        visible = uiState.routeSegments != null && uiState.interpolatedPoints != null,
                        enter = fadeIn() + slideInVertically { it / 4 },
                        exit = fadeOut()
                    ) {
                        RoutePreviewMap(
                            routePoints = uiState.interpolatedPoints ?: emptyList(),
                            routeSegments = uiState.routeSegments ?: emptyList(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .padding(top = 16.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .border(
                                    width = 1.dp,
                                    color = CurveCallPrimary.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(16.dp)
                                )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                // ===== RECENT ROUTES =====
                Column(modifier = Modifier.alpha(bottomAlpha.value)) {
                    if (uiState.recentRoutes.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "RECENT",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    letterSpacing = 2.sp
                                ),
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(uiState.recentRoutes) { _, routeUri ->
                                RecentRouteItem(
                                    routeUri = routeUri,
                                    onClick = {
                                        try {
                                            viewModel.loadGpxFile(Uri.parse(routeUri))
                                        } catch (_: Exception) { }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Premium driving mode toggle with glow-border on selected mode.
 */
@Composable
private fun DrivingModeToggle(
    currentMode: DrivingMode,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF131313))
            .clickable { onToggle() }
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ModeOption(
            icon = Icons.Default.DirectionsCar,
            label = "Car",
            isSelected = currentMode == DrivingMode.CAR,
            modifier = Modifier.weight(1f)
        )
        ModeOption(
            icon = Icons.Default.TwoWheeler,
            label = "Motorcycle",
            isSelected = currentMode == DrivingMode.MOTORCYCLE,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ModeOption(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .then(
                if (isSelected) {
                    Modifier
                        .border(
                            width = 1.dp,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    CurveCallPrimary.copy(alpha = 0.6f),
                                    CurveCallPrimary.copy(alpha = 0.2f)
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
                } else {
                    Modifier
                        .background(Color.Transparent, shape)
                }
            )
            .clip(shape)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (isSelected) CurveCallPrimary
                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) Color.White
                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f)
            )
        }
    }
}

/**
 * Primary action — navigate to the destination picker.
 */
@Composable
private fun PickDestinationButton(
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        CurveCallPrimary.copy(alpha = 0.7f),
                        CurveCallPrimaryVariant.copy(alpha = 0.4f),
                        CurveCallPrimary.copy(alpha = 0.7f)
                    )
                ),
                shape = shape
            )
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        CurveCallPrimary.copy(alpha = 0.12f),
                        CurveCallPrimary.copy(alpha = 0.03f)
                    )
                ),
                shape = shape
            )
            .clip(shape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.NearMe,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = CurveCallPrimary
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Pick Destination",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
    }
}

/**
 * Secondary action — load a GPX file.
 */
@Composable
private fun LoadRouteButton(
    isLoading: Boolean,
    loadingMessage: String,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = if (isLoading) listOf(
                        CurveCallPrimary.copy(alpha = 0.2f),
                        CurveCallPrimaryVariant.copy(alpha = 0.1f)
                    ) else listOf(
                        CurveCallPrimary.copy(alpha = 0.3f),
                        CurveCallPrimaryVariant.copy(alpha = 0.15f),
                        CurveCallPrimary.copy(alpha = 0.3f)
                    )
                ),
                shape = shape
            )
            .clip(shape)
            .clickable(enabled = !isLoading) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = CurveCallPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = loadingMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.NearMe,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = CurveCallPrimary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Load GPX Route",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        }
    }
}

/**
 * Refined recent route card with subtle left accent and chevron.
 */
@Composable
private fun RecentRouteItem(
    routeUri: String,
    onClick: () -> Unit
) {
    val displayName = Uri.parse(routeUri).lastPathSegment
        ?.replace(".gpx", "")
        ?.replace("-", " ")
        ?.replace("_", " ")
        ?: "Route"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = DarkSurfaceElevated
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    // Left accent bar
                    drawRect(
                        color = CurveCallPrimary.copy(alpha = 0.5f),
                        topLeft = Offset.Zero,
                        size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)
                    )
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Route icon with subtle glow circle
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        color = CurveCallPrimary.copy(alpha = 0.1f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.NearMe,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = CurveCallPrimary
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                modifier = Modifier.weight(1f)
            )

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f)
            )
        }
    }
}

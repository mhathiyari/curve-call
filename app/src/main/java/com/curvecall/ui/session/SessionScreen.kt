package com.curvecall.ui.session

import android.Manifest
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.curvecall.engine.types.LatLon
import com.curvecall.ui.map.SessionMap
import com.curvecall.ui.session.components.SessionBottomBar
import com.curvecall.ui.theme.CurveCallPrimary
import com.curvecall.ui.theme.SeverityGentle
import com.curvecall.ui.theme.SeverityModerate
import com.curvecall.ui.theme.SeveritySharp
import com.curvecall.ui.theme.NarrationBannerWarning

/**
 * Active driving session screen with full-screen map and overlay controls.
 *
 * Layout (Box layers):
 * 1. Full-screen SessionMap (heading-up, GPS tracking, dynamic zoom)
 * 2. Gradient scrim + top overlays (back button, verbosity chip, warnings)
 * 3. Bottom bar (two-tier: speed/curve/controls)
 *
 * Keeps screen on via FLAG_KEEP_SCREEN_ON during active session.
 */
@Composable
fun SessionScreen(
    onNavigateBack: () -> Unit,
    viewModel: SessionViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Request location permission
    var hasLocationPermission by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    // Keep screen on during active session (FLAG_KEEP_SCREEN_ON)
    val activity = context as? android.app.Activity
    DisposableEffect(uiState.sessionState) {
        if (uiState.sessionState == SessionViewModel.SessionState.PLAYING) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Auto-start session when permission is granted and session is idle
    LaunchedEffect(hasLocationPermission, uiState.sessionState) {
        if (hasLocationPermission && uiState.sessionState == SessionViewModel.SessionState.IDLE) {
            viewModel.startSession()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Layer 1: Full-screen map with dynamic zoom
        SessionMap(
            routePoints = viewModel.interpolatedPoints,
            routeSegments = viewModel.routeSegments,
            currentPosition = if (uiState.currentLatitude != 0.0 || uiState.currentLongitude != 0.0)
                LatLon(uiState.currentLatitude, uiState.currentLongitude) else null,
            currentBearing = uiState.currentBearing,
            currentAccuracy = uiState.currentAccuracy,
            targetZoom = uiState.targetZoom,
            modifier = Modifier.fillMaxSize()
        )

        // Layer 2: Gradient scrim for top area readability
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.6f),
                            Color.Black.copy(alpha = 0.3f),
                            Color.Transparent
                        )
                    )
                )
        )

        // Layer 3: Top overlays
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Top control bar: back button + GPS signal + verbosity
            TopControlBar(
                verbosity = uiState.verbosity,
                accuracy = uiState.currentAccuracy,
                onBack = {
                    viewModel.stopSession()
                    onNavigateBack()
                },
                onCycleVerbosity = { viewModel.cycleVerbosity() }
            )

            // Off-route warning banner (high urgency)
            AnimatedVisibility(
                visible = uiState.isOffRoute,
                enter = slideInVertically(
                    initialOffsetY = { -it },
                    animationSpec = spring(
                        dampingRatio = 0.7f,
                        stiffness = 300f
                    )
                ) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
            ) {
                OffRouteBanner()
            }

            // Sparse data warning
            AnimatedVisibility(
                visible = uiState.isSparseDataWarning && !uiState.isOffRoute,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(NarrationBannerWarning.copy(alpha = 0.85f))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Low data quality - Curve info may be incomplete",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }
        }

        // Layer 4: Bottom bar
        SessionBottomBar(
            uiState = uiState,
            onPlayPause = {
                when (uiState.sessionState) {
                    SessionViewModel.SessionState.PLAYING -> viewModel.pauseSession()
                    SessionViewModel.SessionState.PAUSED -> viewModel.resumeSession()
                    SessionViewModel.SessionState.STOPPED -> viewModel.startSession()
                    SessionViewModel.SessionState.IDLE -> viewModel.startSession()
                }
            },
            onMute = { viewModel.toggleMute() },
            onStop = {
                viewModel.stopSession()
                onNavigateBack()
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        )
    }
}

/**
 * Off-route warning banner with pulsing border for urgency.
 */
@Composable
private fun OffRouteBanner() {
    val infiniteTransition = rememberInfiniteTransition(label = "offroute_pulse")
    val borderAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "offroute_border"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .border(
                width = 2.dp,
                color = SeveritySharp.copy(alpha = borderAlpha),
                shape = RoundedCornerShape(12.dp)
            )
            .clip(RoundedCornerShape(12.dp))
            .background(SeveritySharp.copy(alpha = 0.85f))
            .padding(14.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "OFF ROUTE",
                fontWeight = FontWeight.Black,
                fontSize = 15.sp,
                color = Color.White,
                letterSpacing = 2.sp
            )
        }
    }
}

/**
 * Top control bar with back button, GPS signal indicator, and verbosity toggle.
 * Buttons have their own dark scrim backgrounds for map visibility.
 */
@Composable
private fun TopControlBar(
    verbosity: Int,
    accuracy: Float,
    onBack: () -> Unit,
    onCycleVerbosity: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back button (48dp touch target)
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f)),
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = Color.White
            )
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "End session",
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // GPS signal quality dot
        GpsSignalDot(accuracy = accuracy)

        Spacer(modifier = Modifier.weight(1f))

        // Verbosity chip
        VerbosityChip(
            verbosity = verbosity,
            onClick = onCycleVerbosity
        )
    }
}

/**
 * Small colored dot indicating GPS signal quality.
 */
@Composable
private fun GpsSignalDot(accuracy: Float) {
    val color = when {
        accuracy <= 0f -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        accuracy <= 5f -> SeverityGentle
        accuracy <= 15f -> CurveCallPrimary
        accuracy <= 30f -> SeverityModerate
        else -> SeveritySharp
    }

    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}

/**
 * Verbosity level chip with dark scrim background.
 */
@Composable
private fun VerbosityChip(
    verbosity: Int,
    onClick: () -> Unit
) {
    val label = when (verbosity) {
        1 -> "Minimal"
        2 -> "Standard"
        3 -> "Detailed"
        else -> "Standard"
    }

    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.height(34.dp),
        contentPadding = ButtonDefaults.TextButtonContentPadding,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = Color.Black.copy(alpha = 0.5f),
            contentColor = Color.White
        )
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Format remaining distance for display.
 */
private fun formatRemainingDistance(meters: Double, usesMph: Boolean): String {
    return if (usesMph) {
        val miles = meters / 1609.344
        when {
            miles >= 10 -> "${miles.toInt()} mi remaining"
            miles >= 1 -> String.format("%.1f mi remaining", miles)
            else -> "${(meters * 3.28084).toInt()} ft remaining"
        }
    } else {
        when {
            meters >= 10000 -> "${(meters / 1000).toInt()} km remaining"
            meters >= 1000 -> String.format("%.1f km remaining", meters / 1000)
            else -> "${meters.toInt()}m remaining"
        }
    }
}

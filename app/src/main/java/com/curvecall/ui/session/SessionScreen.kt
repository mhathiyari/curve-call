package com.curvecall.ui.session

import android.Manifest
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.curvecall.ui.session.components.LeanAngleIndicator
import com.curvecall.ui.session.components.NarrationBanner
import com.curvecall.ui.session.components.SpeedDisplay
import com.curvecall.ui.session.components.UpcomingCurvesList
import com.curvecall.ui.theme.CurveCallPrimary
import com.curvecall.ui.theme.NarrationBannerWarning
import com.curvecall.ui.theme.SeveritySharp

/**
 * Active driving session screen (PRD Section 8.1 - Active Session Screen).
 *
 * Displays:
 * - Large current speed display (from GPS)
 * - Narration text banner (last spoken, large font, high contrast)
 * - Upcoming curves list (next 5 curves)
 * - Speed advisory display (prominent when active)
 * - Lean angle display (motorcycle mode)
 * - Controls: Play/Pause, Mute, Stop
 * - Verbosity quick-toggle
 *
 * Keeps screen on via FLAG_KEEP_SCREEN_ON during active session.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Active Session",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.stopSession()
                        onNavigateBack()
                    }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    // Verbosity quick-toggle
                    VerbosityChip(
                        verbosity = uiState.verbosity,
                        onClick = { viewModel.cycleVerbosity() }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Off-route warning banner
            AnimatedVisibility(
                visible = uiState.isOffRoute,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SeveritySharp.copy(alpha = 0.15f))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "OFF ROUTE - Narration paused",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = SeveritySharp,
                        letterSpacing = 1.sp
                    )
                }
            }

            // Sparse data warning
            AnimatedVisibility(
                visible = uiState.isSparseDataWarning && !uiState.isOffRoute,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(NarrationBannerWarning.copy(alpha = 0.15f))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Low data quality - Curve info may be incomplete",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = NarrationBannerWarning
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Speed Display
            SpeedDisplay(
                currentSpeedKmh = uiState.currentSpeedKmh,
                currentSpeedMph = uiState.currentSpeedMph,
                advisorySpeedKmh = uiState.activeAdvisorySpeedKmh,
                advisorySpeedMph = uiState.activeAdvisorySpeedMph,
                usesMph = uiState.usesMph
            )

            // Lean Angle (motorcycle mode)
            if (uiState.isMotorcycleMode && uiState.activeLeanAngle != null) {
                LeanAngleIndicator(
                    leanAngleDeg = uiState.activeLeanAngle
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Narration Banner
            NarrationBanner(
                narrationText = uiState.lastNarrationText,
                isWarning = uiState.isOffRoute || uiState.isSparseDataWarning
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Route progress
            if (uiState.routeProgressPercent > 0f) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(
                        progress = uiState.routeProgressPercent / 100f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = CurveCallPrimary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = formatRemainingDistance(uiState.distanceRemainingM, uiState.usesMph),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Upcoming Curves List
            UpcomingCurvesList(
                upcomingCurves = uiState.upcomingCurves,
                usesMph = uiState.usesMph
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Controls: Play/Pause, Mute, Stop
            SessionControls(
                sessionState = uiState.sessionState,
                isMuted = uiState.isMuted,
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
                }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Session control buttons: Play/Pause, Mute, Stop.
 * All controls are single-tap for safety while driving.
 */
@Composable
private fun SessionControls(
    sessionState: SessionViewModel.SessionState,
    isMuted: Boolean,
    onPlayPause: () -> Unit,
    onMute: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Mute / Unmute
        FilledIconButton(
            onClick = onMute,
            modifier = Modifier.size(56.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (isMuted) SeveritySharp.copy(alpha = 0.2f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Icon(
                imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                contentDescription = if (isMuted) "Unmute" else "Mute",
                modifier = Modifier.size(28.dp),
                tint = if (isMuted) SeveritySharp else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Play / Pause (primary, larger)
        FilledIconButton(
            onClick = onPlayPause,
            modifier = Modifier.size(72.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = CurveCallPrimary
            )
        ) {
            val icon = when (sessionState) {
                SessionViewModel.SessionState.PLAYING -> Icons.Default.Pause
                else -> Icons.Default.PlayArrow
            }
            Icon(
                imageVector = icon,
                contentDescription = when (sessionState) {
                    SessionViewModel.SessionState.PLAYING -> "Pause"
                    else -> "Play"
                },
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }

        // Stop
        FilledIconButton(
            onClick = onStop,
            modifier = Modifier.size(56.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = SeveritySharp.copy(alpha = 0.2f)
            )
        ) {
            Icon(
                imageVector = Icons.Default.Stop,
                contentDescription = "Stop session",
                modifier = Modifier.size(28.dp),
                tint = SeveritySharp
            )
        }
    }
}

/**
 * Verbosity level chip displayed in the top bar for quick cycling.
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
        modifier = Modifier.height(32.dp),
        contentPadding = ButtonDefaults.TextButtonContentPadding
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

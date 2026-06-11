package com.curvecall.ui.companion

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.curvecall.companion.CompanionSessionViewModel
import com.curvecall.engine.types.DrivingMode
import com.curvecall.service.CompanionForegroundService
import com.curvecall.ui.theme.CurveCuePrimary
import com.curvecall.ui.theme.CurveCuePrimaryDim
import com.curvecall.ui.theme.CurveCuePrimaryVariant
import com.curvecall.ui.theme.DarkBackground
import com.curvecall.ui.theme.DarkSurfaceElevated
import com.curvecall.ui.theme.SeverityGentle
import com.curvecall.ui.theme.SeverityModerate

/**
 * Setup screen for companion mode.
 *
 * Shows permission checks, region coverage, driving mode selection,
 * and a "Start Companion Mode" button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionSetupScreen(
    onNavigateBack: () -> Unit,
    onStartCompanion: () -> Unit,
    viewModel: CompanionSetupViewModel = hiltViewModel(),
    sessionViewModel: CompanionSessionViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.refreshPermissions() }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.refreshPermissions() }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Companion Mode",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
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
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // Description
            Text(
                text = "Run CurveCue alongside Google Maps or any navigation app. " +
                        "CurveCue will detect curves ahead and narrate them while " +
                        "your nav app handles directions.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.6f),
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // -- Permissions Section --
            SectionHeader("Permissions")

            PermissionRow(
                icon = Icons.Default.LocationOn,
                label = "Location access",
                isGranted = state.hasLocationPermission,
                onRequest = {
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            )

            PermissionRow(
                icon = Icons.Default.Notifications,
                label = "Notifications",
                isGranted = state.hasNotificationPermission,
                onRequest = {
                    notificationPermissionLauncher.launch(
                        Manifest.permission.POST_NOTIFICATIONS
                    )
                }
            )

            PermissionRow(
                icon = Icons.Default.Layers,
                label = "Display over other apps",
                isGranted = state.hasOverlayPermission,
                onRequest = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                }
            )

            // -- Region Coverage --
            SectionHeader("Region Coverage")

            if (state.isCheckingRegion) {
                Text(
                    text = "Checking coverage...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.4f)
                )
            } else if (state.currentRegion != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SeverityGentle.copy(alpha = 0.08f))
                        .padding(12.dp)
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = SeverityGentle,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = state.currentRegion!!.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SeverityModerate.copy(alpha = 0.1f))
                        .padding(12.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        tint = SeverityModerate,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "No offline region for current location. Download one in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SeverityModerate.copy(alpha = 0.9f)
                    )
                }
            }

            // -- Driving Mode --
            SectionHeader("Driving Mode")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModeChip(
                    icon = Icons.Default.DirectionsCar,
                    label = "Car",
                    isSelected = state.drivingMode == DrivingMode.CAR,
                    onClick = { viewModel.setDrivingMode(DrivingMode.CAR) },
                    modifier = Modifier.weight(1f)
                )
                ModeChip(
                    icon = Icons.Default.TwoWheeler,
                    label = "Motorcycle",
                    isSelected = state.drivingMode == DrivingMode.MOTORCYCLE,
                    onClick = { viewModel.setDrivingMode(DrivingMode.MOTORCYCLE) },
                    modifier = Modifier.weight(1f)
                )
            }

            // -- Verbosity --
            SectionHeader("Verbosity")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VerbosityChip("Minimal", state.verbosity == 1, { viewModel.cycleVerbosity() }, Modifier.weight(1f))
                VerbosityChip("Standard", state.verbosity == 2, { viewModel.cycleVerbosity() }, Modifier.weight(1f))
                VerbosityChip("Detailed", state.verbosity == 3, { viewModel.cycleVerbosity() }, Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // -- Start Button --
            StartCompanionButton(
                isEnabled = state.isReady,
                onClick = {
                    // Wire service callbacks to session ViewModel
                    CompanionForegroundService.onStopRequested = { sessionViewModel.stop() }
                    CompanionForegroundService.onVerbosityCycleRequested = { sessionViewModel.cycleVerbosity() }

                    // Start the companion session
                    sessionViewModel.start()

                    // Bridge UI state to the service overlay
                    // (ViewModel updates are forwarded in its collect loop)

                    onStartCompanion()
                }
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            letterSpacing = 2.sp,
            fontSize = 11.sp
        ),
        color = CurveCuePrimary.copy(alpha = 0.6f),
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    label: String,
    isGranted: Boolean,
    onRequest: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(DarkSurfaceElevated)
            .clickable { if (!isGranted) onRequest() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (isGranted) SeverityGentle else Color.White.copy(alpha = 0.4f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier.weight(1f)
        )
        if (isGranted) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Granted",
                modifier = Modifier.size(18.dp),
                tint = SeverityGentle
            )
        } else {
            Text(
                text = "Grant",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = CurveCuePrimary
            )
        }
    }
}

@Composable
private fun ModeChip(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .then(
                if (isSelected) {
                    Modifier.border(
                        1.dp,
                        CurveCuePrimary.copy(alpha = 0.5f),
                        shape
                    ).background(CurveCuePrimary.copy(alpha = 0.1f), shape)
                } else {
                    Modifier.background(DarkSurfaceElevated, shape)
                }
            )
            .clip(shape)
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (isSelected) CurveCuePrimary else Color.White.copy(alpha = 0.4f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
private fun VerbosityChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .then(
                if (isSelected) {
                    Modifier.border(
                        1.dp,
                        CurveCuePrimary.copy(alpha = 0.4f),
                        shape
                    ).background(CurveCuePrimary.copy(alpha = 0.08f), shape)
                } else {
                    Modifier.background(DarkSurfaceElevated, shape)
                }
            )
            .clip(shape)
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isSelected) CurveCuePrimary else Color.White.copy(alpha = 0.4f)
        )
    }
}

@Composable
private fun StartCompanionButton(
    isEnabled: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    val alpha = if (isEnabled) 1f else 0.35f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .then(
                if (isEnabled) {
                    Modifier.border(
                        width = 1.5.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                CurveCuePrimary.copy(alpha = 0.7f),
                                CurveCuePrimaryVariant.copy(alpha = 0.4f),
                                CurveCuePrimary.copy(alpha = 0.7f)
                            )
                        ),
                        shape = shape
                    )
                } else {
                    Modifier.border(1.dp, Color.White.copy(alpha = 0.1f), shape)
                }
            )
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        CurveCuePrimary.copy(alpha = if (isEnabled) 0.15f else 0.04f),
                        CurveCuePrimary.copy(alpha = if (isEnabled) 0.05f else 0.01f)
                    )
                ),
                shape = shape
            )
            .clip(shape)
            .clickable(enabled = isEnabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.GraphicEq,
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .then(Modifier),
                tint = CurveCuePrimary.copy(alpha = alpha)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Start Companion Mode",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = alpha)
            )
        }
    }
}

package com.curvecall.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Animatable
import androidx.hilt.navigation.compose.hiltViewModel
import com.curvecall.engine.types.DrivingMode
import com.curvecall.ui.theme.CurveCallPrimary
import com.curvecall.ui.theme.CurveCallPrimaryDim
import com.curvecall.ui.theme.CurveCallPrimaryVariant
import com.curvecall.ui.theme.DarkBackground
import com.curvecall.ui.theme.SeverityModerate

/**
 * Home screen — premium "instrument panel" aesthetic.
 *
 * Features a custom S-curve logo with headlight glow, radial gradient backdrop,
 * glow-bordered mode toggle, and a primary "Pick Destination" button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToDestination: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    val logoAlpha = remember { Animatable(1f) }
    val logoOffset = remember { Animatable(0f) }
    val contentAlpha = remember { Animatable(1f) }
    val contentOffset = remember { Animatable(0f) }

    Scaffold(
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

package com.curvecall.ui.companion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curvecall.companion.CompanionUiState
import com.curvecall.ui.theme.CurveCuePrimary
import com.curvecall.ui.theme.CurveCuePrimaryDim
import com.curvecall.ui.theme.DarkSurface
import com.curvecall.ui.theme.DarkSurfaceElevated
import kotlinx.coroutines.delay

/**
 * Floating overlay bubble for companion mode.
 *
 * Two states:
 * - **Collapsed:** 56dp circle with CurveCue icon, pulses on narration fire.
 *   Tap to expand.
 * - **Expanded:** Card showing speed, next curve preview, stop button,
 *   and verbosity toggle. Auto-collapses after 5 seconds.
 */
@Composable
fun CompanionBubble(
    uiState: CompanionUiState,
    onStop: () -> Unit,
    onCycleVerbosity: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    var lastNarrationCount by remember { mutableStateOf(0) }

    // Auto-collapse after 5s
    LaunchedEffect(isExpanded) {
        if (isExpanded) {
            delay(5000)
            isExpanded = false
        }
    }

    // Track narration pulse
    val isPulsing = uiState.narrationCount > lastNarrationCount
    LaunchedEffect(uiState.narrationCount) {
        lastNarrationCount = uiState.narrationCount
    }

    Column(horizontalAlignment = Alignment.End) {
        // Expanded card
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
        ) {
            ExpandedCard(
                uiState = uiState,
                onStop = onStop,
                onCycleVerbosity = onCycleVerbosity,
                onCollapse = { isExpanded = false }
            )
        }

        if (!isExpanded) {
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Collapsed bubble (always visible)
        CollapsedBubble(
            isPulsing = isPulsing,
            isOffRoad = uiState.isOffRoad,
            isGpsLost = uiState.isGpsLost,
            onClick = { isExpanded = !isExpanded }
        )
    }
}

@Composable
private fun CollapsedBubble(
    isPulsing: Boolean,
    isOffRoad: Boolean,
    isGpsLost: Boolean,
    onClick: () -> Unit
) {
    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val borderColor = when {
        isGpsLost || isOffRoad -> Color.Red.copy(alpha = 0.6f)
        isPulsing -> CurveCuePrimary.copy(alpha = pulseAlpha)
        else -> CurveCuePrimary.copy(alpha = 0.4f)
    }

    Box(
        modifier = Modifier
            .size(56.dp)
            .border(
                width = 2.dp,
                color = borderColor,
                shape = CircleShape
            )
            .background(
                color = DarkSurface.copy(alpha = 0.95f),
                shape = CircleShape
            )
            .clip(CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.GraphicEq,
            contentDescription = "CurveCue Companion",
            modifier = Modifier.size(28.dp),
            tint = if (isPulsing) CurveCuePrimary else CurveCuePrimary.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun ExpandedCard(
    uiState: CompanionUiState,
    onStop: () -> Unit,
    onCycleVerbosity: () -> Unit,
    onCollapse: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)

    Column(
        modifier = Modifier
            .width(200.dp)
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        CurveCuePrimary.copy(alpha = 0.4f),
                        CurveCuePrimaryDim.copy(alpha = 0.2f)
                    )
                ),
                shape = shape
            )
            .background(
                color = DarkSurfaceElevated.copy(alpha = 0.97f),
                shape = shape
            )
            .clip(shape)
            .padding(12.dp)
    ) {
        // Speed
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${uiState.currentSpeedKmh.toInt()} km/h",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.weight(1f))
            // Close button
            IconButton(
                onClick = onCollapse,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Collapse",
                    modifier = Modifier.size(16.dp),
                    tint = Color.White.copy(alpha = 0.5f)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Next curve preview
        if (uiState.nextCurvePreview != null && uiState.nextCurveDistanceM != null) {
            Text(
                text = uiState.nextCurvePreview,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = CurveCuePrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "in ${formatDistance(uiState.nextCurveDistanceM)}",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f)
            )
        } else if (uiState.isOffRoad) {
            Text(
                text = "Off road",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.Red.copy(alpha = 0.8f)
            )
        } else if (uiState.isGpsLost) {
            Text(
                text = "GPS signal lost",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.Red.copy(alpha = 0.8f)
            )
        } else {
            Text(
                text = "Scanning road ahead...",
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.4f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Controls row
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Verbosity toggle
            val verbosityLabel = when (uiState.verbosity) {
                1 -> "Min"
                3 -> "Max"
                else -> "Std"
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(CurveCuePrimary.copy(alpha = 0.12f))
                    .clickable { onCycleVerbosity() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Verbosity",
                        modifier = Modifier.size(14.dp),
                        tint = CurveCuePrimary.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = verbosityLabel,
                        fontSize = 11.sp,
                        color = CurveCuePrimary.copy(alpha = 0.8f)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Stop button
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Red.copy(alpha = 0.15f))
                    .clickable { onStop() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Stop",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.Red.copy(alpha = 0.9f)
                )
            }
        }
    }
}

private fun formatDistance(meters: Double): String {
    return if (meters >= 1000) {
        String.format("%.1f km", meters / 1000)
    } else {
        "${meters.toInt()}m"
    }
}

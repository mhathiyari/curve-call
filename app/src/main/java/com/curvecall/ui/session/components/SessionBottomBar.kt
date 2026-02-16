package com.curvecall.ui.session.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curvecall.engine.types.Direction
import com.curvecall.ui.session.SessionViewModel
import com.curvecall.ui.theme.CurveCuePrimary
import com.curvecall.ui.theme.DarkSurfaceElevated
import com.curvecall.ui.theme.NarrationBannerText
import com.curvecall.ui.theme.NarrationBannerWarning
import com.curvecall.ui.theme.SeveritySharp
import com.curvecall.ui.theme.SpeedAdvisory
import com.curvecall.ui.theme.severityColor
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Two-tier bottom bar overlay for the session map screen.
 *
 * Primary tier (always visible): Speed + next curve + play/pause.
 * Expandable tier (auto-shows during narration): Narration text, controls.
 *
 * Route progress is drawn as a line along the top edge of the bar.
 */
@Composable
fun SessionBottomBar(
    uiState: SessionViewModel.SessionUiState,
    onPlayPause: () -> Unit,
    onMute: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }

    // Auto-expand when narration changes, auto-collapse after delay
    LaunchedEffect(uiState.lastNarrationText) {
        if (uiState.lastNarrationText.isNotEmpty()) {
            isExpanded = true
            delay(5000)
            isExpanded = false
        }
    }

    val progressColor = CurveCuePrimary
    val progressFraction = uiState.routeProgressPercent / 100f

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                // Route progress line along the top edge
                if (progressFraction > 0f) {
                    drawRoundRect(
                        color = progressColor,
                        topLeft = Offset.Zero,
                        size = Size(size.width * progressFraction, 3.dp.toPx()),
                        cornerRadius = CornerRadius(2.dp.toPx())
                    )
                }
            },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 12.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Drag handle
            Box(
                modifier = Modifier
                    .width(32.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
                    .clickable { isExpanded = !isExpanded }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Primary tier: Speed + Next Curve + Play/Pause
            PrimaryTier(
                uiState = uiState,
                onPlayPause = onPlayPause
            )

            // Expandable tier: Narration + Controls
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))

                    // Narration text
                    CompactNarrationBanner(
                        narrationText = uiState.lastNarrationText,
                        isWarning = uiState.isOffRoute || uiState.isSparseDataWarning
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Controls row
                    ControlsRow(
                        sessionState = uiState.sessionState,
                        isMuted = uiState.isMuted,
                        onPlayPause = onPlayPause,
                        onMute = onMute,
                        onStop = onStop
                    )
                }
            }
        }
    }
}

@Composable
private fun PrimaryTier(
    uiState: SessionViewModel.SessionUiState,
    onPlayPause: () -> Unit
) {
    val displaySpeed = if (uiState.usesMph) uiState.currentSpeedMph else uiState.currentSpeedKmh
    val displayAdvisory = if (uiState.usesMph) uiState.activeAdvisorySpeedMph else uiState.activeAdvisorySpeedKmh
    val units = if (uiState.usesMph) "mph" else "km/h"
    val nextCurve = uiState.upcomingCurves.firstOrNull()

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Speed display (monospaced)
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                AnimatedContent(
                    targetState = displaySpeed.roundToInt(),
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInVertically { -it } + fadeIn()) togetherWith
                                (slideOutVertically { it } + fadeOut())
                        } else {
                            (slideInVertically { it } + fadeIn()) togetherWith
                                (slideOutVertically { -it } + fadeOut())
                        }
                    },
                    label = "speed"
                ) { speed ->
                    Text(
                        text = "$speed",
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = units,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            // Advisory speed
            if (displayAdvisory != null) {
                val advisoryRounded = (displayAdvisory.roundToInt() / 5) * 5
                Text(
                    text = "SLOW TO $advisoryRounded $units",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = SpeedAdvisory,
                    letterSpacing = 0.5.sp
                )
            }
        }

        // Next curve card (center)
        if (nextCurve != null) {
            NextCurveCard(
                curve = nextCurve,
                usesMph = uiState.usesMph,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        // Play/Pause button
        Box(contentAlignment = Alignment.Center) {
            if (uiState.sessionState == SessionViewModel.SessionState.PLAYING) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .border(2.dp, CurveCuePrimary.copy(alpha = 0.3f), CircleShape)
                )
            }
            FilledIconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(52.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = CurveCuePrimary
                )
            ) {
                Icon(
                    imageVector = when (uiState.sessionState) {
                        SessionViewModel.SessionState.PLAYING -> Icons.Default.Pause
                        else -> Icons.Default.PlayArrow
                    },
                    contentDescription = when (uiState.sessionState) {
                        SessionViewModel.SessionState.PLAYING -> "Pause"
                        else -> "Play"
                    },
                    modifier = Modifier.size(26.dp),
                    tint = Color.Black
                )
            }
        }
    }
}

/**
 * Next curve card with severity color accent on the left edge.
 */
@Composable
private fun NextCurveCard(
    curve: SessionViewModel.UpcomingCurve,
    usesMph: Boolean,
    modifier: Modifier = Modifier
) {
    val isLowConfidence = curve.curveSegment.confidence < 0.5f
    val color = severityColor(curve.curveSegment.severity, lowConfidence = isLowConfidence)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.08f))
            .drawBehind {
                // Left accent bar
                drawRoundRect(
                    color = color,
                    topLeft = Offset.Zero,
                    size = Size(4.dp.toPx(), size.height),
                    cornerRadius = CornerRadius(2.dp.toPx())
                )
            }
            .padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Direction arrow
        Icon(
            imageVector = when (curve.curveSegment.direction) {
                Direction.LEFT -> Icons.Default.TurnLeft
                Direction.RIGHT -> Icons.Default.TurnRight
            },
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = color
        )

        Spacer(modifier = Modifier.width(6.dp))

        Column(modifier = Modifier.weight(1f)) {
            // Distance (monospaced)
            Text(
                text = formatDistanceCompact(curve.distanceToM, usesMph),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = color
            )
            // Description
            Text(
                text = curve.briefDescription,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CompactNarrationBanner(
    narrationText: String,
    isWarning: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DarkSurfaceElevated)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = narrationText,
            transitionSpec = {
                (slideInVertically { it } + fadeIn()) togetherWith
                    (slideOutVertically { -it } + fadeOut())
            },
            label = "narration_compact"
        ) { text ->
            Text(
                text = text.ifEmpty { "Waiting for narration..." },
                fontSize = 15.sp,
                fontWeight = if (text.isNotEmpty()) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isWarning) NarrationBannerWarning
                       else if (text.isNotEmpty()) NarrationBannerText
                       else NarrationBannerText.copy(alpha = 0.3f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Asymmetric controls: small mute, prominent play/pause (already in primary tier),
 * small stop with deliberate action.
 */
@Composable
private fun ControlsRow(
    sessionState: SessionViewModel.SessionState,
    isMuted: Boolean,
    onPlayPause: () -> Unit,
    onMute: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Mute - secondary
        FilledIconButton(
            onClick = onMute,
            modifier = Modifier.size(40.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (isMuted) SeveritySharp.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Icon(
                imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                contentDescription = if (isMuted) "Unmute" else "Mute",
                modifier = Modifier.size(20.dp),
                tint = if (isMuted) SeveritySharp else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(32.dp))

        // Stop - deliberate, red-tinted
        FilledIconButton(
            onClick = onStop,
            modifier = Modifier.size(40.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = SeveritySharp.copy(alpha = 0.15f)
            )
        ) {
            Icon(
                imageVector = Icons.Default.Stop,
                contentDescription = "Stop session",
                modifier = Modifier.size(20.dp),
                tint = SeveritySharp
            )
        }
    }
}

private fun formatDistanceCompact(meters: Double, usesMph: Boolean): String {
    return if (usesMph) {
        val feet = meters * 3.28084
        val miles = meters / 1609.344
        when {
            miles >= 1.0 -> String.format("%.1fmi", miles)
            feet >= 500 -> "${(feet / 100).roundToInt() * 100}ft"
            else -> "${(feet / 10).roundToInt() * 10}ft"
        }
    } else {
        when {
            meters >= 1000 -> String.format("%.1fkm", meters / 1000)
            meters >= 100 -> "${(meters / 10).roundToInt() * 10}m"
            else -> "${meters.roundToInt()}m"
        }
    }
}

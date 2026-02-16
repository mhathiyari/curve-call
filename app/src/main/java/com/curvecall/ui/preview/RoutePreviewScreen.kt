package com.curvecall.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.curvecall.engine.types.Severity
import com.curvecall.ui.map.RoutePreviewMap
import com.curvecall.ui.theme.CurveCuePrimary
import com.curvecall.ui.theme.DarkBackground
import com.curvecall.ui.theme.DarkSurfaceElevated
import com.curvecall.ui.theme.severityColor
import kotlin.math.roundToInt

@Composable
fun RoutePreviewScreen(
    onNavigateBack: () -> Unit,
    onStartSession: () -> Unit,
    viewModel: RoutePreviewViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    if (!uiState.hasData) {
        // Should not happen in normal flow — navigate back
        Box(
            modifier = Modifier.fillMaxSize().background(DarkBackground),
            contentAlignment = Alignment.Center
        ) {
            Text("No route data available.", color = Color.White)
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Full-screen map with severity-colored route
        RoutePreviewMap(
            routePoints = uiState.interpolatedPoints,
            routeSegments = uiState.routeSegments,
            modifier = Modifier.fillMaxSize()
        )

        // Top gradient scrim + back button + route name
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            DarkBackground.copy(alpha = 0.85f),
                            DarkBackground.copy(alpha = 0.5f),
                            Color.Transparent
                        )
                    )
                )
                .padding(top = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                uiState.routeName?.let { name ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Bottom panel with stats + start button
        BottomPanel(
            uiState = uiState,
            onStartSession = onStartSession,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        )
    }
}

@Composable
private fun BottomPanel(
    uiState: RoutePreviewViewModel.PreviewUiState,
    onStartSession: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 12.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // Route summary row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    icon = Icons.Default.Route,
                    value = formatDistance(uiState.totalDistanceM),
                    label = "Distance"
                )
                StatItem(
                    icon = Icons.Default.Schedule,
                    value = formatTime(uiState.estimatedTimeMs),
                    label = "Est. Time"
                )
                StatItem(
                    icon = Icons.Default.TurnRight,
                    value = "${uiState.curveCount}",
                    label = "Curves"
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Severity breakdown
            if (uiState.severityCounts.isNotEmpty()) {
                SeverityBreakdown(uiState.severityCounts)
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Start button
            Button(
                onClick = onStartSession,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CurveCuePrimary)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = Color.Black
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Start Session",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }
        }
    }
}

@Composable
private fun StatItem(
    icon: ImageVector,
    value: String,
    label: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = CurveCuePrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun SeverityBreakdown(severityCounts: Map<Severity, Int>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurfaceElevated)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // Show in order: Gentle → Hairpin
        val orderedSeverities = listOf(
            Severity.GENTLE, Severity.MODERATE, Severity.FIRM,
            Severity.SHARP, Severity.HAIRPIN
        )
        for (severity in orderedSeverities) {
            val count = severityCounts[severity] ?: 0
            if (count > 0) {
                SeverityChip(severity = severity, count = count)
            }
        }
    }
}

@Composable
private fun SeverityChip(severity: Severity, count: Int) {
    val color = severityColor(severity)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "$count",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = severity.name.lowercase().replaceFirstChar { it.uppercase() },
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

private fun formatDistance(meters: Double): String {
    return when {
        meters >= 1000 -> String.format("%.1f km", meters / 1000)
        else -> "${meters.roundToInt()} m"
    }
}

private fun formatTime(millis: Long): String {
    val totalMinutes = millis / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes} min"
    }
}

package com.curvecall.ui.session.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curvecall.engine.types.CurveSegment
import com.curvecall.engine.types.Direction
import com.curvecall.ui.session.SessionViewModel
import com.curvecall.ui.theme.SeverityLowConfidence
import com.curvecall.ui.theme.severityColor
import kotlin.math.roundToInt

/**
 * A single row in the upcoming curves list.
 *
 * Displays:
 * - Direction arrow icon (left/right) colored by severity
 * - Distance to curve
 * - Brief text description
 * - Low-confidence warning indicator when data quality is poor
 *
 * PRD Section 8.1: "Each shows: icon (arrow direction) + severity color +
 * distance + brief text. Low-confidence curves marked with a warning indicator."
 */
@Composable
fun CurveListItem(
    upcomingCurve: SessionViewModel.UpcomingCurve,
    usesMph: Boolean,
    modifier: Modifier = Modifier
) {
    val curve = upcomingCurve.curveSegment
    val isLowConfidence = curve.confidence < 0.5f
    val color = severityColor(curve.severity, lowConfidence = isLowConfidence)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Direction arrow icon with severity color
        DirectionIcon(
            direction = curve.direction,
            color = color
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Distance to curve
        Column(
            modifier = Modifier.width(64.dp),
            horizontalAlignment = Alignment.End
        ) {
            val distanceText = formatDistance(upcomingCurve.distanceToM, usesMph)
            Text(
                text = distanceText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Brief description
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = upcomingCurve.briefDescription,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 18.sp
            )
        }

        // Low confidence warning indicator
        if (isLowConfidence) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Low confidence",
                modifier = Modifier.size(18.dp),
                tint = SeverityLowConfidence
            )
        }
    }
}

/**
 * Circular direction arrow icon colored by severity.
 */
@Composable
private fun DirectionIcon(
    direction: Direction,
    color: Color
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = when (direction) {
                Direction.LEFT -> Icons.Default.TurnLeft
                Direction.RIGHT -> Icons.Default.TurnRight
            },
            contentDescription = direction.name.lowercase(),
            modifier = Modifier.size(22.dp),
            tint = color
        )
    }
}

/**
 * Format a distance in meters to a display string.
 * Under 1 km: shows meters (rounded to 10m). Over 1 km: shows km with 1 decimal.
 */
private fun formatDistance(meters: Double, usesMph: Boolean): String {
    return if (usesMph) {
        val feet = meters * 3.28084
        val miles = meters / 1609.344
        when {
            miles >= 1.0 -> String.format("%.1f mi", miles)
            feet >= 500 -> "${(feet / 100).roundToInt() * 100} ft"
            else -> "${(feet / 10).roundToInt() * 10} ft"
        }
    } else {
        when {
            meters >= 1000 -> String.format("%.1f km", meters / 1000)
            meters >= 100 -> "${(meters / 10).roundToInt() * 10}m"
            else -> "${meters.roundToInt()}m"
        }
    }
}

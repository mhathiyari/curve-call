package com.curvecall.ui.session.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curvecall.ui.theme.CurveCallPrimary
import com.curvecall.ui.theme.SeveritySharp
import kotlin.math.roundToInt

/**
 * Lean angle indicator for motorcycle mode.
 *
 * Shows a visual representation of the recommended lean angle
 * with a tilted line indicator and degree readout.
 *
 * The indicator tilts smoothly to the target angle with animation.
 * Angles above 35 degrees are shown in warning red.
 * Angles above 45 degrees display "Extreme lean" instead of a number.
 *
 * PRD Section 7.7: "Round to nearest 5 degrees. Cap display at 45 degrees."
 */
@Composable
fun LeanAngleIndicator(
    leanAngleDeg: Double?,
    modifier: Modifier = Modifier
) {
    if (leanAngleDeg == null) return

    val roundedAngle = ((leanAngleDeg / 5.0).roundToInt() * 5).coerceAtMost(45)
    val isExtreme = leanAngleDeg > 45.0
    val isWarning = leanAngleDeg > 35.0

    val animatedAngle by animateFloatAsState(
        targetValue = roundedAngle.toFloat(),
        animationSpec = tween(durationMillis = 500),
        label = "lean_angle"
    )

    val indicatorColor = when {
        isExtreme -> SeveritySharp
        isWarning -> SeveritySharp.copy(alpha = 0.8f)
        else -> CurveCallPrimary
    }

    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        // Visual lean indicator
        LeanVisual(
            angleDeg = animatedAngle,
            color = indicatorColor,
            modifier = Modifier.size(48.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Text readout
        Column(horizontalAlignment = Alignment.Start) {
            Text(
                text = "LEAN",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                letterSpacing = 2.sp
            )
            if (isExtreme) {
                Text(
                    text = "Extreme",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = SeveritySharp
                )
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "$roundedAngle",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = indicatorColor,
                        lineHeight = 28.sp
                    )
                    Text(
                        text = "\u00B0",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = indicatorColor,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * Canvas-based lean angle visual indicator.
 * Draws a line that tilts to represent the lean angle.
 */
@Composable
private fun LeanVisual(
    angleDeg: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val centerX = size.width / 2
        val centerY = size.height / 2
        val lineLength = size.height * 0.4f

        // Ground line
        drawLine(
            color = color.copy(alpha = 0.3f),
            start = Offset(0f, size.height * 0.85f),
            end = Offset(size.width, size.height * 0.85f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )

        // Tilted motorcycle line
        rotate(degrees = angleDeg, pivot = Offset(centerX, size.height * 0.85f)) {
            drawLine(
                color = color,
                start = Offset(centerX, size.height * 0.85f),
                end = Offset(centerX, size.height * 0.85f - lineLength * 2),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
            // Wheel circle at bottom
            drawCircle(
                color = color,
                radius = 4.dp.toPx(),
                center = Offset(centerX, size.height * 0.85f)
            )
        }
    }
}

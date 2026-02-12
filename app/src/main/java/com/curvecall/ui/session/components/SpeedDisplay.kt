package com.curvecall.ui.session.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curvecall.ui.theme.SpeedAdvisory
import com.curvecall.ui.theme.SpeedNormal
import kotlin.math.roundToInt

/**
 * Large speed readout component for the session screen.
 *
 * Displays the current GPS speed prominently. When a speed advisory
 * is active, it also shows the recommended speed in red below.
 *
 * Designed for glanceability while driving -- audio is primary,
 * but this provides quick visual confirmation.
 */
@Composable
fun SpeedDisplay(
    currentSpeedKmh: Double,
    currentSpeedMph: Double,
    advisorySpeedKmh: Double?,
    advisorySpeedMph: Double?,
    usesMph: Boolean,
    modifier: Modifier = Modifier
) {
    val displaySpeed = if (usesMph) currentSpeedMph else currentSpeedKmh
    val displayAdvisory = if (usesMph) advisorySpeedMph else advisorySpeedKmh
    val units = if (usesMph) "mph" else "km/h"

    Column(
        modifier = modifier.padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Current speed - large and prominent
        Row(
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = "${displaySpeed.roundToInt()}",
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
                color = SpeedNormal,
                lineHeight = 72.sp
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = units,
                fontSize = 20.sp,
                fontWeight = FontWeight.Normal,
                color = SpeedNormal.copy(alpha = 0.6f),
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        // Speed advisory when active
        if (displayAdvisory != null) {
            val advisoryRounded = (displayAdvisory.roundToInt() / 5) * 5
            Row(
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = "SLOW TO ",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = SpeedAdvisory,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "$advisoryRounded",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = SpeedAdvisory,
                    lineHeight = 32.sp
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = units,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    color = SpeedAdvisory.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }
    }
}

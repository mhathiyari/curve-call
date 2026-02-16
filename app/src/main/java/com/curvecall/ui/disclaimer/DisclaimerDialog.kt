package com.curvecall.ui.disclaimer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curvecall.ui.theme.CurveCuePrimary

/**
 * First-launch disclaimer dialog (PRD Section 14.1).
 *
 * Displays the full safety disclaimer text and requires the user
 * to explicitly accept before using the app. This dialog cannot
 * be dismissed by tapping outside -- the user must tap "I Understand".
 *
 * The acceptance state is stored in UserPreferences so this dialog
 * is only shown once (unless the user clears app data).
 */
@Composable
fun DisclaimerDialog(
    onAccept: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* Cannot dismiss without accepting */ },
        title = {
            Text(
                text = "Safety Disclaimer",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                DisclaimerText()
            }
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CurveCuePrimary
                )
            ) {
                Text(
                    text = "I Understand and Accept",
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        dismissButton = null
    )
}

/**
 * The full disclaimer text as specified in PRD Section 14.1.
 */
@Composable
fun DisclaimerText() {
    val paragraphs = listOf(
        "CurveCall provides road geometry information derived from OpenStreetMap data. " +
            "Speed advisories and lean angle suggestions are calculated estimates based on " +
            "road geometry only. They do NOT account for road surface condition, weather, " +
            "tire condition, traffic, visibility, vehicle capability, or rider skill level.",
        "The driver/rider is solely responsible for their speed, lean angle, and all " +
            "driving decisions. CurveCall is a driving aid, not a safety system. It may " +
            "contain errors or use outdated map data.",
        "Never interact with the CurveCall UI while driving. Use audio narration only."
    )

    Column {
        paragraphs.forEachIndexed { index, text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 22.sp
            )
            if (index < paragraphs.lastIndex) {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

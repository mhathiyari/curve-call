package com.curvecall.ui.session.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.curvecall.ui.session.SessionViewModel

/**
 * Scrollable list of the next 5 upcoming curves along the route.
 *
 * Each curve is displayed as a CurveListItem with:
 * - Direction arrow icon
 * - Severity color coding
 * - Distance to curve
 * - Brief text description
 * - Low-confidence warning indicator
 *
 * PRD Section 8.1: "Upcoming curves list (next 5 curves)"
 */
@Composable
fun UpcomingCurvesList(
    upcomingCurves: List<SessionViewModel.UpcomingCurve>,
    usesMph: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Upcoming Curves",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
            if (upcomingCurves.isEmpty()) {
                Text(
                    text = "No curves ahead",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                )
            }
        }

        upcomingCurves.forEach { curve ->
            CurveListItem(
                upcomingCurve = curve,
                usesMph = usesMph
            )
        }
    }
}

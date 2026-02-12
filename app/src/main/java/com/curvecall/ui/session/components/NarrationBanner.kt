package com.curvecall.ui.session.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curvecall.ui.theme.NarrationBannerBackground
import com.curvecall.ui.theme.NarrationBannerText
import com.curvecall.ui.theme.NarrationBannerWarning

/**
 * High-contrast text banner showing the last spoken narration text.
 *
 * Uses a dark background with white text for maximum readability
 * in all lighting conditions. Text animates when it changes.
 *
 * PRD Section 8.1: "Current narration text banner (last spoken instruction,
 * large font, high contrast)"
 */
@Composable
fun NarrationBanner(
    narrationText: String,
    isWarning: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(NarrationBannerBackground)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = narrationText,
            transitionSpec = {
                (slideInVertically { it } + fadeIn()) togetherWith
                    (slideOutVertically { -it } + fadeOut())
            },
            label = "narration_text"
        ) { text ->
            if (text.isNotEmpty()) {
                Text(
                    text = text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isWarning) NarrationBannerWarning else NarrationBannerText,
                    textAlign = TextAlign.Center,
                    lineHeight = 28.sp
                )
            } else {
                Text(
                    text = "Waiting for narration...",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                    color = NarrationBannerText.copy(alpha = 0.4f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

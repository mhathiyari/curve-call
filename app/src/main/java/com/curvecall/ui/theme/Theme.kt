package com.curvecall.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.curvecall.engine.types.Severity

private val DarkColorScheme = darkColorScheme(
    primary = CurveCallPrimary,
    onPrimary = Color.White,
    primaryContainer = CurveCallPrimaryVariant,
    secondary = CurveCallSecondary,
    onSecondary = Color.White,
    secondaryContainer = CurveCallSecondaryVariant,
    background = DarkBackground,
    onBackground = OnDarkSurface,
    surface = DarkSurface,
    onSurface = OnDarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = OnDarkSurface,
    error = SeveritySharp,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = CurveCallPrimary,
    onPrimary = Color.White,
    primaryContainer = CurveCallPrimaryVariant,
    secondary = CurveCallSecondary,
    onSecondary = Color.White,
    secondaryContainer = CurveCallSecondaryVariant,
    background = LightBackground,
    onBackground = OnLightSurface,
    surface = LightSurface,
    onSurface = OnLightSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = OnLightSurface,
    error = SeveritySharp,
    onError = Color.White
)

/**
 * CurveCall Material 3 theme.
 *
 * Uses a dark color scheme optimized for driving (less glare).
 * The severity color system from PRD Section 8.2 is available
 * via the [severityColor] helper function.
 */
@Composable
fun CurveCallTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

/**
 * Returns the color corresponding to a curve severity level (PRD Section 8.2).
 *
 * @param severity The curve severity
 * @param lowConfidence If true, returns gray regardless of severity
 */
fun severityColor(severity: Severity, lowConfidence: Boolean = false): Color {
    if (lowConfidence) return SeverityLowConfidence
    return when (severity) {
        Severity.GENTLE -> SeverityGentle
        Severity.MODERATE -> SeverityModerate
        Severity.FIRM -> SeverityFirm
        Severity.SHARP -> SeveritySharp
        Severity.HAIRPIN -> SeverityHairpin
    }
}

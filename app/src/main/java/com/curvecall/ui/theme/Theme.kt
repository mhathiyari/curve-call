package com.curvecall.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.curvecall.engine.types.Severity

private val DarkColorScheme = darkColorScheme(
    primary = CurveCallPrimary,
    onPrimary = Color.Black,
    primaryContainer = CurveCallPrimaryDim,
    onPrimaryContainer = CurveCallPrimaryVariant,
    secondary = CurveCallSecondary,
    onSecondary = Color.White,
    secondaryContainer = CurveCallSecondaryVariant,
    background = DarkBackground,
    onBackground = OnDarkSurface,
    surface = DarkSurface,
    onSurface = OnDarkSurface,
    surfaceVariant = DarkSurfaceHighest,
    onSurfaceVariant = OnDarkSurface,
    surfaceContainerHigh = DarkSurfaceElevated,
    error = SeveritySharp,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = CurveCallPrimary,
    onPrimary = Color.Black,
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
 * Monospaced font family for speed/distance numerals.
 * Uses system monospace for tabular figures that don't jitter.
 */
val SpeedFontFamily: FontFamily = FontFamily.Monospace

/**
 * CurveCall custom typography.
 * Uses monospaced figures for numeric displays (speed, distance)
 * and clean sans-serif for all text content.
 */
private val CurveCallTypography = Typography(
    // Speed display: large monospaced
    displayLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 56.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-1).sp
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 40.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp
    ),
    // Narration text: clear, readable
    headlineMedium = TextStyle(
        fontSize = 18.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 26.sp
    ),
    // Section headers
    titleLarge = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp
    )
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
    darkTheme: Boolean = true, // Always dark — driving app, less glare
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
        typography = CurveCallTypography,
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

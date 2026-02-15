package com.curvecall.ui.theme

import androidx.compose.ui.graphics.Color

// -- Severity colors — cohesive spectrum from safe to danger --
val SeverityGentle = Color(0xFF66BB6A)       // Soft green — clearly safe
val SeverityModerate = Color(0xFFFFCA28)     // Warm amber
val SeverityFirm = Color(0xFFFF8F00)         // Deep orange — approaching danger
val SeveritySharp = Color(0xFFEF5350)        // Bright red — clear danger
val SeverityHairpin = Color(0xFFD50000)      // Intense red — maximum urgency
val SeverityLowConfidence = Color(0xFF757575)

// -- Brand colors --
val CurveCallPrimary = Color(0xFF00C853)          // Vivid green — high visibility
val CurveCallPrimaryVariant = Color(0xFF69F0AE)   // Lighter variant for highlights
val CurveCallPrimaryDim = Color(0xFF00802F)        // Dimmed variant for backgrounds
val CurveCallSecondary = Color(0xFF0288D1)         // Blue accent
val CurveCallSecondaryVariant = Color(0xFF0277BD)

// -- Surface and background (layered elevation) --
val DarkBackground = Color(0xFF0D0D0D)        // Deepest black — true depth
val DarkSurface = Color(0xFF161616)           // Primary surface
val DarkSurfaceElevated = Color(0xFF1F1F1F)   // Cards, bottom sheets
val DarkSurfaceHighest = Color(0xFF2A2A2A)    // Chips, toggles, overlays
val DarkSurfaceVariant = Color(0xFF2A2A2A)    // Alias for compat

val LightBackground = Color(0xFFFAFAFA)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF0F0F0)

// -- Narration banner --
val NarrationBannerBackground = Color(0xFF1A1A1A) // Subtle, not pure black
val NarrationBannerText = Color(0xFFFFFFFF)
val NarrationBannerWarning = Color(0xFFFF8F00)

// -- Speed display --
val SpeedNormal = Color(0xFFFFFFFF)
val SpeedAdvisory = Color(0xFFEF5350)

// -- On-colors --
val OnDarkSurface = Color(0xFFE0E0E0)
val OnLightSurface = Color(0xFF212121)

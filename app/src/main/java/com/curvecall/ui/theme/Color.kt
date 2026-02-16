package com.curvecall.ui.theme

import androidx.compose.ui.graphics.Color

// -- Severity colors — warmer, refined shades (green→red spectrum) --
val SeverityGentle = Color(0xFF7CB342)       // Olive green — clearly safe
val SeverityModerate = Color(0xFFFFB300)     // Richer amber
val SeverityFirm = Color(0xFFF57C00)         // Burnt orange — approaching danger
val SeveritySharp = Color(0xFFE53935)        // Warm red — clear danger
val SeverityHairpin = Color(0xFFC62828)      // Deep warm red — maximum urgency
val SeverityLowConfidence = Color(0xFF6D6560) // Warm gray

// -- Brand colors (amber-gold — headlights & road markings) --
val CurveCuePrimary = Color(0xFFF5A623)          // Warm amber — buttons, icons, GPS arrow
val CurveCuePrimaryVariant = Color(0xFFFFD180)   // Soft gold — highlights, gradients
val CurveCuePrimaryDim = Color(0xFF8B6914)        // Deep golden brown — dark backdrop
val CurveCueSecondary = Color(0xFF8D6E63)         // Warm brown — subtle secondary
val CurveCueSecondaryVariant = Color(0xFF6D4C41)  // Darker brown

// -- Surface and background (warm charcoal — asphalt tones) --
val DarkBackground = Color(0xFF110F0D)        // Warm near-black
val DarkSurface = Color(0xFF1A1714)           // Warm charcoal
val DarkSurfaceElevated = Color(0xFF242018)   // Cards, bottom sheets
val DarkSurfaceHighest = Color(0xFF302A22)    // Chips, toggles, overlays
val DarkSurfaceVariant = Color(0xFF302A22)    // Alias for compat

val LightBackground = Color(0xFFFAF6F1)
val LightSurface = Color(0xFFFFFCF7)
val LightSurfaceVariant = Color(0xFFEDE8E0)

// -- Narration banner --
val NarrationBannerBackground = Color(0xFF1E1A15) // Warm dark
val NarrationBannerText = Color(0xFFFFF5E6)        // Warm white
val NarrationBannerWarning = Color(0xFFF57C00)     // Burnt orange (matches Firm)

// -- Speed display --
val SpeedNormal = Color(0xFFFFF5E6)           // Warm white
val SpeedAdvisory = Color(0xFFE53935)         // Warm red (matches Sharp)

// -- On-colors --
val OnDarkSurface = Color(0xFFD9D0C7)         // Warm cream text
val OnLightSurface = Color(0xFF1C1410)

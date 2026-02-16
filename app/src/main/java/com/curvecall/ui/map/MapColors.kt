package com.curvecall.ui.map

import com.curvecall.engine.types.Severity

/**
 * Maps severity levels to android.graphics.Color ARGB ints for osmdroid overlays.
 * Colors match the road-at-dusk severity spectrum in Color.kt.
 */
object MapColors {
    // Severity colors (matching Color.kt)
    private const val GENTLE = 0xFF7CB342.toInt()
    private const val MODERATE = 0xFFFFB300.toInt()
    private const val FIRM = 0xFFF57C00.toInt()
    private const val SHARP = 0xFFE53935.toInt()
    private const val HAIRPIN = 0xFFC62828.toInt()

    // Straight segments: semi-transparent warm gray
    const val STRAIGHT = 0xC89E9690.toInt()

    // GPS arrow: amber for visibility on dark tiles
    const val GPS_ARROW = 0xFFF5A623.toInt()
    const val GPS_ARROW_STROKE = 0xFFFFFFFF.toInt()

    // Accuracy circle: amber tint
    const val ACCURACY_FILL = 0x30F5A623.toInt()
    const val ACCURACY_STROKE = 0x80F5A623.toInt()

    fun forSeverity(severity: Severity): Int = when (severity) {
        Severity.GENTLE -> GENTLE
        Severity.MODERATE -> MODERATE
        Severity.FIRM -> FIRM
        Severity.SHARP -> SHARP
        Severity.HAIRPIN -> HAIRPIN
    }
}

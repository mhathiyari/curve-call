package com.curvecall.ui.map

import com.curvecall.engine.types.Severity

/**
 * Maps severity levels to android.graphics.Color ARGB ints for osmdroid overlays.
 * Colors match the updated cohesive severity spectrum in Color.kt.
 */
object MapColors {
    // Severity colors (matching Color.kt)
    private const val GENTLE = 0xFF66BB6A.toInt()
    private const val MODERATE = 0xFFFFCA28.toInt()
    private const val FIRM = 0xFFFF8F00.toInt()
    private const val SHARP = 0xFFEF5350.toInt()
    private const val HAIRPIN = 0xFFD50000.toInt()

    // Straight segments: semi-transparent gray
    const val STRAIGHT = 0xC89E9E9E.toInt()

    // GPS arrow: bright vivid green for visibility on dark tiles
    const val GPS_ARROW = 0xFF00C853.toInt()
    const val GPS_ARROW_STROKE = 0xFFFFFFFF.toInt()

    // Accuracy circle
    const val ACCURACY_FILL = 0x3000C853.toInt()
    const val ACCURACY_STROKE = 0x8000C853.toInt()

    fun forSeverity(severity: Severity): Int = when (severity) {
        Severity.GENTLE -> GENTLE
        Severity.MODERATE -> MODERATE
        Severity.FIRM -> FIRM
        Severity.SHARP -> SHARP
        Severity.HAIRPIN -> HAIRPIN
    }
}

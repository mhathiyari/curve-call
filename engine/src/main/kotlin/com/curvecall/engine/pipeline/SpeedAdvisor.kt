package com.curvecall.engine.pipeline

import com.curvecall.engine.types.AnalysisConfig
import com.curvecall.engine.types.CurveSegment
import com.curvecall.engine.types.Severity
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Computes physics-based speed advisories from curve radius and lateral g-force limits.
 *
 * Formula: advisory_speed_ms = sqrt(radius * lateralG * 9.81)
 * The speed is then converted to user units and rounded down to the nearest 5.
 */
object SpeedAdvisor {

    /** Standard gravity in m/s^2. */
    const val GRAVITY = 9.81

    /** Conversion factor from m/s to km/h. */
    const val MS_TO_KMH = 3.6

    /** Conversion factor from m/s to mph. */
    const val MS_TO_MPH = 2.23694

    /**
     * Computes the advisory speed in m/s for a given radius and lateral G limit.
     *
     * @param radiusM Curve minimum radius in meters.
     * @param lateralG Lateral acceleration limit in g-force units.
     * @return Speed in m/s.
     */
    fun calculateSpeedMs(radiusM: Double, lateralG: Double): Double {
        require(radiusM > 0.0) { "Radius must be positive, got $radiusM" }
        require(lateralG > 0.0) { "Lateral G must be positive, got $lateralG" }
        return sqrt(radiusM * lateralG * GRAVITY)
    }

    /**
     * Converts speed in m/s to km/h and rounds down to the nearest 5.
     */
    fun toKmhRounded(speedMs: Double): Double {
        val kmh = speedMs * MS_TO_KMH
        return floor(kmh / 5.0) * 5.0
    }

    /**
     * Converts speed in m/s to mph and rounds down to the nearest 5.
     */
    fun toMphRounded(speedMs: Double): Double {
        val mph = speedMs * MS_TO_MPH
        return floor(mph / 5.0) * 5.0
    }

    /**
     * Determines whether a curve needs a speed advisory based on its severity.
     *
     * Per PRD Section 7.5:
     * - GENTLE: no advisory
     * - MODERATE: context-dependent
     * - FIRM, SHARP, HAIRPIN: always
     */
    fun needsAdvisory(severity: Severity): Boolean {
        return when (severity) {
            Severity.GENTLE -> false
            Severity.MODERATE -> false  // Handled by needsAdvisory(severity, speedMs) overload
            Severity.FIRM -> true
            Severity.SHARP -> true
            Severity.HAIRPIN -> true
        }
    }

    /**
     * Context-dependent advisory check for MODERATE curves.
     *
     * Per PRD Section 7.5, MODERATE curves (100-200m radius) are "context-dependent."
     * We issue an advisory when the computed advisory speed falls below [MODERATE_SPEED_THRESHOLD_KMH]
     * (70 km/h), which covers the tighter end of the moderate range where drivers
     * may need advance warning.
     *
     * @param severity The curve severity.
     * @param advisorySpeedMs The computed advisory speed in m/s.
     * @return true if a speed advisory should be included.
     */
    fun needsAdvisory(severity: Severity, advisorySpeedMs: Double): Boolean {
        if (severity == Severity.MODERATE) {
            val kmh = advisorySpeedMs * MS_TO_KMH
            return kmh < MODERATE_SPEED_THRESHOLD_KMH
        }
        return needsAdvisory(severity)
    }

    /** Speed threshold in km/h below which MODERATE curves get an advisory. */
    const val MODERATE_SPEED_THRESHOLD_KMH = 70.0

    /**
     * Applies speed advisory to a classified curve segment.
     *
     * @param curve The classified curve (advisory fields may be null).
     * @param config The analysis configuration.
     * @return Updated curve with advisory speed set (or null if not needed).
     */
    fun applyAdvisory(curve: CurveSegment, config: AnalysisConfig): CurveSegment {
        val speedMs = calculateSpeedMs(curve.minRadius, config.lateralG)
        return if (needsAdvisory(curve.severity, speedMs)) {
            curve.copy(advisorySpeedMs = speedMs)
        } else {
            curve
        }
    }
}

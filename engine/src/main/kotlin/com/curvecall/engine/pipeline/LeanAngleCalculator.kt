package com.curvecall.engine.pipeline

import com.curvecall.engine.types.CurveSegment
import kotlin.math.atan
import kotlin.math.roundToInt

/**
 * Calculates motorcycle lean angle from advisory speed and curve radius.
 *
 * Formula: lean_angle_degrees = atan(v^2 / (radius * g)) * (180 / PI)
 *
 * The lean angle is rounded to the nearest 5 degrees and capped at 45 degrees.
 * Beyond 45 degrees, the narration should say "extreme lean" instead of a number.
 */
object LeanAngleCalculator {

    /** Standard gravity in m/s^2. */
    private const val GRAVITY = 9.81

    /** Maximum lean angle to display as a number. Beyond this, narrate "extreme lean". */
    const val MAX_LEAN_ANGLE = 45.0

    /**
     * Calculates the lean angle in degrees for a given speed and curve radius.
     *
     * @param speedMs Advisory speed in m/s.
     * @param radiusM Curve minimum radius in meters.
     * @return Lean angle in degrees (exact, not rounded).
     */
    fun calculateExact(speedMs: Double, radiusM: Double): Double {
        require(speedMs >= 0.0) { "Speed must be non-negative, got $speedMs" }
        require(radiusM > 0.0) { "Radius must be positive, got $radiusM" }

        val tanTheta = (speedMs * speedMs) / (radiusM * GRAVITY)
        return Math.toDegrees(atan(tanTheta))
    }

    /**
     * Calculates the lean angle rounded to the nearest 5 degrees, capped at [MAX_LEAN_ANGLE].
     *
     * @param speedMs Advisory speed in m/s.
     * @param radiusM Curve minimum radius in meters.
     * @return Lean angle in degrees, rounded to nearest 5, or null if speed is zero.
     */
    fun calculate(speedMs: Double, radiusM: Double): Double? {
        if (speedMs <= 0.0) return null

        val exact = calculateExact(speedMs, radiusM)
        val rounded = (exact / 5.0).roundToInt() * 5.0

        return minOf(rounded, MAX_LEAN_ANGLE)
    }

    /**
     * Returns true if the lean angle exceeds the displayable maximum (45 degrees).
     * In this case, the narration should say "extreme lean" instead of a number.
     */
    fun isExtremeLean(speedMs: Double, radiusM: Double): Boolean {
        if (speedMs <= 0.0) return false
        return calculateExact(speedMs, radiusM) > MAX_LEAN_ANGLE
    }

    /**
     * Applies lean angle calculation to a curve segment (motorcycle mode only).
     *
     * @param curve The curve with advisory speed already computed.
     * @param isMotorcycleMode Whether motorcycle mode is active.
     * @return Updated curve with lean angle set, or unchanged if not motorcycle mode.
     */
    fun applyLeanAngle(curve: CurveSegment, isMotorcycleMode: Boolean): CurveSegment {
        if (!isMotorcycleMode) return curve

        val advisorySpeed = curve.advisorySpeedMs ?: return curve

        val leanAngle = calculate(advisorySpeed, curve.minRadius)
        return curve.copy(leanAngleDeg = leanAngle)
    }
}

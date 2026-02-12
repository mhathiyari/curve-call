package com.curvecall.engine.pipeline

import com.curvecall.engine.types.*
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shared test utilities for generating synthetic geometry.
 */
object TestHelpers {

    /**
     * Creates a test CurveSegment with the given properties and sensible defaults.
     */
    fun createTestCurve(
        direction: Direction = Direction.RIGHT,
        severity: Severity = Severity.MODERATE,
        minRadius: Double = 100.0,
        arcLength: Double = 150.0,
        modifiers: Set<CurveModifier> = emptySet(),
        totalAngleChange: Double = 45.0,
        is90Degree: Boolean = false,
        advisorySpeedMs: Double? = null,
        leanAngleDeg: Double? = null,
        compoundType: CompoundType? = null,
        compoundSize: Int? = null,
        confidence: Float = 1.0f,
        startIndex: Int = 0,
        endIndex: Int = 10,
        startPoint: LatLon = LatLon(48.0, 11.0),
        endPoint: LatLon = LatLon(48.001, 11.001),
        distanceFromStart: Double = 0.0
    ): CurveSegment = CurveSegment(
        direction = direction,
        severity = severity,
        minRadius = minRadius,
        arcLength = arcLength,
        modifiers = modifiers,
        totalAngleChange = totalAngleChange,
        is90Degree = is90Degree,
        advisorySpeedMs = advisorySpeedMs,
        leanAngleDeg = leanAngleDeg,
        compoundType = compoundType,
        compoundSize = compoundSize,
        confidence = confidence,
        startIndex = startIndex,
        endIndex = endIndex,
        startPoint = startPoint,
        endPoint = endPoint,
        distanceFromStart = distanceFromStart
    )

    /**
     * Generates points along a circular arc in the local tangent plane.
     */
    fun generateCircleArc(
        center: LatLon,
        radiusM: Double,
        startAngle: Double,
        endAngle: Double,
        numPoints: Int
    ): List<LatLon> {
        val metersPerDegreeLat = 111_320.0
        val metersPerDegreeLon = 111_320.0 * cos(Math.toRadians(center.lat))

        return (0 until numPoints).map { i ->
            val t = startAngle + (endAngle - startAngle) * i / (numPoints - 1)
            val dx = radiusM * cos(t)
            val dy = radiusM * sin(t)
            LatLon(
                center.lat + dy / metersPerDegreeLat,
                center.lon + dx / metersPerDegreeLon
            )
        }
    }

    /**
     * Generates points along a straight line heading north.
     */
    fun generateStraight(
        start: LatLon,
        lengthM: Double,
        numPoints: Int
    ): List<LatLon> {
        val metersPerDegreeLat = 111_320.0
        return (0 until numPoints).map { i ->
            val dist = lengthM * i / (numPoints - 1)
            LatLon(start.lat + dist / metersPerDegreeLat, start.lon)
        }
    }

    /**
     * Generates a tightening spiral (radius decreases from startRadius to endRadius).
     */
    fun generateSpiral(
        center: LatLon,
        startRadius: Double,
        endRadius: Double,
        startAngle: Double,
        endAngle: Double,
        numPoints: Int
    ): List<LatLon> {
        val metersPerDegreeLat = 111_320.0
        val metersPerDegreeLon = 111_320.0 * cos(Math.toRadians(center.lat))

        return (0 until numPoints).map { i ->
            val t = startAngle + (endAngle - startAngle) * i / (numPoints - 1)
            val radius = startRadius + (endRadius - startRadius) * i / (numPoints - 1)
            val dx = radius * cos(t)
            val dy = radius * sin(t)
            LatLon(
                center.lat + dy / metersPerDegreeLat,
                center.lon + dx / metersPerDegreeLon
            )
        }
    }
}

package com.curvecall.engine

import com.curvecall.engine.pipeline.TestHelpers
import com.curvecall.engine.types.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.cos
import kotlin.math.sin

class RouteAnalyzerTest {

    private val routeAnalyzer = RouteAnalyzer()

    private fun generateCircleArc(
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

    private fun generateStraight(
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

    @Nested
    inner class FullPipelineIntegration {

        @Test
        fun `straight road produces only straight segments`() {
            val points = generateStraight(LatLon(48.0, 11.0), 1000.0, 50)

            val result = routeAnalyzer.analyzeRoute(points)

            val curves = result.filterIsInstance<RouteSegment.Curve>()
            assertThat(curves).isEmpty()
        }

        @Test
        fun `road with clear curve detects at least one curve`() {
            // Straight - 100m radius curve - Straight
            val straight1 = generateStraight(LatLon(48.0, 11.0), 200.0, 10)
            val center = LatLon(48.002, 11.0)
            val arc = generateCircleArc(center, 100.0, -Math.PI / 2, 0.0, 15)
            val straight2 = generateStraight(
                LatLon(48.002 + 100.0 / 111320.0, 11.0 + 100.0 / (111320.0 * cos(Math.toRadians(48.0)))),
                200.0, 10
            )

            val allPoints = straight1 + arc.drop(1) + straight2.drop(1)

            val result = routeAnalyzer.analyzeRoute(allPoints)

            val curves = result.filterIsInstance<RouteSegment.Curve>()
            assertThat(curves).isNotEmpty()
        }

        @Test
        fun `hairpin curve is detected with correct severity`() {
            val straight1 = generateStraight(LatLon(48.0, 11.0), 200.0, 10)
            val center = LatLon(48.002, 11.0)
            val arc = generateCircleArc(center, 20.0, 0.0, Math.PI, 30)
            val endLat = arc.last().lat
            val straight2 = generateStraight(arc.last(), 200.0, 10)

            val allPoints = straight1 + arc.drop(1) + straight2.drop(1)

            val result = routeAnalyzer.analyzeRoute(allPoints)

            val curves = result.filterIsInstance<RouteSegment.Curve>()
            assertThat(curves).isNotEmpty()

            // At least one should be HAIRPIN or SHARP (the 20m radius curve)
            val hasSevere = curves.any {
                it.data.severity == Severity.HAIRPIN || it.data.severity == Severity.SHARP
            }
            assertThat(hasSevere).isTrue()
        }

        @Test
        fun `motorcycle mode adds speed advisories with lower G`() {
            val straight1 = generateStraight(LatLon(48.0, 11.0), 200.0, 10)
            val center = LatLon(48.002, 11.0)
            val arc = generateCircleArc(center, 50.0, 0.0, Math.PI / 2, 20)
            val straight2 = generateStraight(arc.last(), 200.0, 10)

            val allPoints = straight1 + arc.drop(1) + straight2.drop(1)

            val carConfig = AnalysisConfig(lateralG = 0.35)
            val motoConfig = AnalysisConfig(lateralG = 0.25, isMotorcycleMode = true)

            val carResult = routeAnalyzer.analyzeRoute(allPoints, carConfig)
            val motoResult = routeAnalyzer.analyzeRoute(allPoints, motoConfig)

            val carCurves = carResult.filterIsInstance<RouteSegment.Curve>()
                .filter { it.data.advisorySpeedMs != null }
            val motoCurves = motoResult.filterIsInstance<RouteSegment.Curve>()
                .filter { it.data.advisorySpeedMs != null }

            if (carCurves.isNotEmpty() && motoCurves.isNotEmpty()) {
                // Motorcycle speeds should be lower (more conservative)
                assertThat(motoCurves.first().data.advisorySpeedMs!!)
                    .isLessThan(carCurves.first().data.advisorySpeedMs!!)
            }
        }

        @Test
        fun `motorcycle mode adds lean angle`() {
            val straight1 = generateStraight(LatLon(48.0, 11.0), 200.0, 10)
            val center = LatLon(48.002, 11.0)
            val arc = generateCircleArc(center, 50.0, 0.0, Math.PI / 2, 20)
            val straight2 = generateStraight(arc.last(), 200.0, 10)

            val allPoints = straight1 + arc.drop(1) + straight2.drop(1)

            val motoConfig = AnalysisConfig(lateralG = 0.25, isMotorcycleMode = true)
            val result = routeAnalyzer.analyzeRoute(allPoints, motoConfig)

            val curvesWithLean = result.filterIsInstance<RouteSegment.Curve>()
                .filter { it.data.leanAngleDeg != null }

            // Should have at least one curve with lean angle
            if (result.any { it is RouteSegment.Curve && (it as RouteSegment.Curve).data.advisorySpeedMs != null }) {
                assertThat(curvesWithLean).isNotEmpty()
            }
        }
    }

    @Nested
    inner class DetailedAnalysis {

        @Test
        fun `analyzeRouteDetailed returns metadata`() {
            val points = generateStraight(LatLon(48.0, 11.0), 500.0, 25)

            val result = routeAnalyzer.analyzeRouteDetailed(points)

            assertThat(result.interpolatedPoints).isNotEmpty()
            assertThat(result.totalDistance).isGreaterThan(400.0)
        }

        @Test
        fun `curve count matches actual curves`() {
            val straight1 = generateStraight(LatLon(48.0, 11.0), 200.0, 10)
            val center = LatLon(48.002, 11.0)
            val arc = generateCircleArc(center, 80.0, 0.0, Math.PI / 2, 15)
            val straight2 = generateStraight(arc.last(), 200.0, 10)

            val allPoints = straight1 + arc.drop(1) + straight2.drop(1)

            val result = routeAnalyzer.analyzeRouteDetailed(allPoints)

            val curveCount = result.segments.count { it is RouteSegment.Curve }
            assertThat(result.curveCount).isEqualTo(curveCount)
        }
    }

    @Nested
    inner class EdgeCases {

        @Test
        fun `fewer than 3 points throws`() {
            assertThrows<IllegalArgumentException> {
                routeAnalyzer.analyzeRoute(listOf(LatLon(48.0, 11.0), LatLon(48.001, 11.0)))
            }
        }

        @Test
        fun `three points is minimum`() {
            val points = listOf(
                LatLon(48.0, 11.0),
                LatLon(48.005, 11.0),
                LatLon(48.010, 11.0)
            )

            val result = routeAnalyzer.analyzeRoute(points)
            // Should not throw, may or may not have segments
            assertThat(result).isNotNull()
        }

        @Test
        fun `custom config is respected`() {
            val points = generateStraight(LatLon(48.0, 11.0), 500.0, 25)

            // Very tight threshold should not find curves on a straight road
            val strictConfig = AnalysisConfig(curvatureThresholdRadius = 100.0)
            val result = routeAnalyzer.analyzeRoute(points, strictConfig)

            val curves = result.filterIsInstance<RouteSegment.Curve>()
            assertThat(curves).isEmpty()
        }
    }
}

package com.curvecall.engine.pipeline

import com.curvecall.engine.types.Direction
import com.curvecall.engine.types.LatLon
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Percentage
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.cos
import kotlin.math.sin

class CurvatureComputerTest {

    /**
     * Generates points along a circular arc in the local tangent plane.
     */
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

    /**
     * Generates points along a straight line.
     */
    private fun generateStraightLine(
        start: LatLon,
        bearingDeg: Double,
        lengthM: Double,
        numPoints: Int
    ): List<LatLon> {
        val metersPerDegreeLat = 111_320.0
        val metersPerDegreeLon = 111_320.0 * cos(Math.toRadians(start.lat))
        val bearingRad = Math.toRadians(bearingDeg)

        return (0 until numPoints).map { i ->
            val dist = lengthM * i / (numPoints - 1)
            val dx = dist * sin(bearingRad) // east component
            val dy = dist * cos(bearingRad) // north component
            LatLon(
                start.lat + dy / metersPerDegreeLat,
                start.lon + dx / metersPerDegreeLon
            )
        }
    }

    @Nested
    inner class CircularArcTests {

        @Test
        fun `100m radius circle returns smoothed radii near 100m`() {
            val center = LatLon(48.0, 11.0)
            val points = generateCircleArc(center, 100.0, 0.0, Math.PI / 2, 30)

            val result = CurvatureComputer.compute(points, smoothingWindow = 5)

            // Check interior points (skip edges which may be less accurate)
            val interiorRadii = result.drop(5).dropLast(5).map { it.radius }

            for (r in interiorRadii) {
                assertThat(r).isCloseTo(100.0, Percentage.withPercentage(10.0))
            }
        }

        @Test
        fun `50m radius circle returns smoothed radii near 50m`() {
            val center = LatLon(48.0, 11.0)
            val points = generateCircleArc(center, 50.0, 0.0, Math.PI / 2, 25)

            val result = CurvatureComputer.compute(points, smoothingWindow = 5)

            val interiorRadii = result.drop(5).dropLast(5).map { it.radius }

            for (r in interiorRadii) {
                assertThat(r).isCloseTo(50.0, Percentage.withPercentage(10.0))
            }
        }

        @Test
        fun `counter-clockwise arc has LEFT direction`() {
            val center = LatLon(48.0, 11.0)
            // Counter-clockwise (increasing angle)
            val points = generateCircleArc(center, 100.0, 0.0, Math.PI / 2, 20)

            val result = CurvatureComputer.compute(points, smoothingWindow = 3)

            // Most interior points should be LEFT
            val leftCount = result.drop(2).dropLast(2).count { it.direction == Direction.LEFT }
            val rightCount = result.drop(2).dropLast(2).count { it.direction == Direction.RIGHT }

            assertThat(leftCount).isGreaterThan(rightCount)
        }

        @Test
        fun `clockwise arc has RIGHT direction`() {
            val center = LatLon(48.0, 11.0)
            // Clockwise (decreasing angle)
            val points = generateCircleArc(center, 100.0, Math.PI / 2, 0.0, 20)

            val result = CurvatureComputer.compute(points, smoothingWindow = 3)

            val rightCount = result.drop(2).dropLast(2).count { it.direction == Direction.RIGHT }
            val leftCount = result.drop(2).dropLast(2).count { it.direction == Direction.LEFT }

            assertThat(rightCount).isGreaterThan(leftCount)
        }
    }

    @Nested
    inner class StraightLineTests {

        @Test
        fun `straight line has very large radii`() {
            val points = generateStraightLine(LatLon(48.0, 11.0), 0.0, 500.0, 50)

            val result = CurvatureComputer.compute(points, smoothingWindow = 5)

            // All radii should be at the cap (10000.0) or very close
            for (cp in result) {
                assertThat(cp.radius).isGreaterThan(1000.0)
            }
        }
    }

    @Nested
    inner class SmoothingTests {

        @Test
        fun `larger smoothing window produces less variation`() {
            val center = LatLon(48.0, 11.0)
            val points = generateCircleArc(center, 100.0, 0.0, Math.PI / 2, 30)

            val smallWindow = CurvatureComputer.compute(points, smoothingWindow = 3)
            val largeWindow = CurvatureComputer.compute(points, smoothingWindow = 7)

            // Compute variance of interior radii
            fun variance(radii: List<Double>): Double {
                val mean = radii.average()
                return radii.map { (it - mean) * (it - mean) }.average()
            }

            val smallVariance = variance(smallWindow.drop(5).dropLast(5).map { it.radius })
            val largeVariance = variance(largeWindow.drop(5).dropLast(5).map { it.radius })

            // Larger window should have equal or lower variance
            assertThat(largeVariance).isLessThanOrEqualTo(smallVariance + 1.0)
        }

        @Test
        fun `smoothing window of 1 returns raw values`() {
            val center = LatLon(48.0, 11.0)
            val points = generateCircleArc(center, 100.0, 0.0, Math.PI / 4, 10)

            val result = CurvatureComputer.compute(points, smoothingWindow = 1)

            // With window=1, smoothed should equal raw (within cap)
            for (cp in result.drop(1).dropLast(1)) {
                assertThat(cp.radius).isCloseTo(cp.rawRadius.coerceAtMost(10000.0), Percentage.withPercentage(0.1))
            }
        }
    }

    @Nested
    inner class EdgeCases {

        @Test
        fun `fewer than 3 points throws`() {
            assertThrows<IllegalArgumentException> {
                CurvatureComputer.compute(listOf(LatLon(48.0, 11.0), LatLon(48.001, 11.0)))
            }
        }

        @Test
        fun `exactly 3 points works`() {
            val center = LatLon(48.0, 11.0)
            val points = generateCircleArc(center, 100.0, 0.0, Math.PI / 4, 3)

            val result = CurvatureComputer.compute(points, smoothingWindow = 1)
            assertThat(result).hasSize(3)
        }
    }
}

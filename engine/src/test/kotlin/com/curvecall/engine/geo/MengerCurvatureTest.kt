package com.curvecall.engine.geo

import com.curvecall.engine.types.Direction
import com.curvecall.engine.types.LatLon
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.assertj.core.data.Percentage
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.sin

class MengerCurvatureTest {

    /**
     * Generates points on a circle of given radius centered at a given lat/lon.
     * Points are generated in the local tangent plane approximation.
     *
     * @param center Center of the circle.
     * @param radiusM Radius in meters.
     * @param startAngle Starting angle in radians.
     * @param endAngle Ending angle in radians.
     * @param numPoints Number of points to generate.
     * @return List of LatLon points on the circle.
     */
    private fun generateCirclePoints(
        center: LatLon,
        radiusM: Double,
        startAngle: Double = 0.0,
        endAngle: Double = Math.PI / 2,
        numPoints: Int = 10
    ): List<LatLon> {
        val points = mutableListOf<LatLon>()
        val metersPerDegreeLat = 111_320.0
        val metersPerDegreeLon = 111_320.0 * cos(Math.toRadians(center.lat))

        for (i in 0 until numPoints) {
            val t = startAngle + (endAngle - startAngle) * i / (numPoints - 1)
            val dx = radiusM * cos(t)
            val dy = radiusM * sin(t)
            points.add(
                LatLon(
                    center.lat + dy / metersPerDegreeLat,
                    center.lon + dx / metersPerDegreeLon
                )
            )
        }

        return points
    }

    @Nested
    inner class RadiusTests {

        @Test
        fun `known 100m radius circle returns approximately 100m`() {
            val center = LatLon(48.0, 11.0)
            val points = generateCirclePoints(center, 100.0, 0.0, Math.PI / 4, 3)
            val radius = MengerCurvature.radius(points[0], points[1], points[2])

            assertThat(radius).isCloseTo(100.0, Percentage.withPercentage(2.0))
        }

        @Test
        fun `known 50m radius circle returns approximately 50m`() {
            val center = LatLon(48.0, 11.0)
            val points = generateCirclePoints(center, 50.0, 0.0, Math.PI / 4, 3)
            val radius = MengerCurvature.radius(points[0], points[1], points[2])

            assertThat(radius).isCloseTo(50.0, Percentage.withPercentage(2.0))
        }

        @Test
        fun `known 200m radius circle returns approximately 200m`() {
            val center = LatLon(48.0, 11.0)
            val points = generateCirclePoints(center, 200.0, 0.0, Math.PI / 6, 3)
            val radius = MengerCurvature.radius(points[0], points[1], points[2])

            assertThat(radius).isCloseTo(200.0, Percentage.withPercentage(2.0))
        }

        @Test
        fun `known 25m radius hairpin returns approximately 25m`() {
            val center = LatLon(48.0, 11.0)
            val points = generateCirclePoints(center, 25.0, 0.0, Math.PI / 4, 3)
            val radius = MengerCurvature.radius(points[0], points[1], points[2])

            assertThat(radius).isCloseTo(25.0, Percentage.withPercentage(3.0))
        }

        @Test
        fun `collinear points return MAX_VALUE`() {
            val p1 = LatLon(48.0, 11.0)
            val p2 = LatLon(48.0005, 11.0)
            val p3 = LatLon(48.001, 11.0) // all on the same meridian

            val radius = MengerCurvature.radius(p1, p2, p3)
            assertThat(radius).isEqualTo(Double.MAX_VALUE)
        }

        @Test
        fun `very large radius for nearly straight points`() {
            // Three points nearly collinear with tiny offset
            val p1 = LatLon(48.0, 11.0)
            val p2 = LatLon(48.001, 11.000001) // tiny lateral offset
            val p3 = LatLon(48.002, 11.0)

            val radius = MengerCurvature.radius(p1, p2, p3)
            assertThat(radius).isGreaterThan(10_000.0)
        }
    }

    @Nested
    inner class DirectionTests {

        @Test
        fun `left turn detected correctly`() {
            // Three points making a left turn (counter-clockwise in map view)
            // Going north, then turning northwest
            val p1 = LatLon(48.0, 11.0)
            val p2 = LatLon(48.001, 11.0)
            val p3 = LatLon(48.002, 10.999) // turning left (west)

            val dir = MengerCurvature.direction(p1, p2, p3)
            assertThat(dir).isEqualTo(Direction.LEFT)
        }

        @Test
        fun `right turn detected correctly`() {
            // Three points making a right turn
            // Going north, then turning northeast
            val p1 = LatLon(48.0, 11.0)
            val p2 = LatLon(48.001, 11.0)
            val p3 = LatLon(48.002, 11.001) // turning right (east)

            val dir = MengerCurvature.direction(p1, p2, p3)
            assertThat(dir).isEqualTo(Direction.RIGHT)
        }

        @Test
        fun `collinear points return null direction`() {
            val p1 = LatLon(48.0, 11.0)
            val p2 = LatLon(48.001, 11.0)
            val p3 = LatLon(48.002, 11.0)

            val dir = MengerCurvature.direction(p1, p2, p3)
            assertThat(dir).isNull()
        }

        @Test
        fun `circle points going counter-clockwise are LEFT`() {
            val center = LatLon(48.0, 11.0)
            // Counter-clockwise circle (increasing angle = left turn in map coordinates)
            val points = generateCirclePoints(center, 100.0, 0.0, Math.PI / 4, 3)

            val dir = MengerCurvature.direction(points[0], points[1], points[2])
            assertThat(dir).isEqualTo(Direction.LEFT)
        }

        @Test
        fun `circle points going clockwise are RIGHT`() {
            val center = LatLon(48.0, 11.0)
            // Clockwise circle (decreasing angle)
            val points = generateCirclePoints(center, 100.0, Math.PI / 4, 0.0, 3)

            val dir = MengerCurvature.direction(points[0], points[1], points[2])
            assertThat(dir).isEqualTo(Direction.RIGHT)
        }
    }
}

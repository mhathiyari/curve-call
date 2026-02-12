package com.curvecall.engine.geo

import com.curvecall.engine.types.LatLon
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GeoMathTest {

    @Nested
    inner class HaversineDistanceTests {

        @Test
        fun `same point returns zero distance`() {
            val p = LatLon(48.8566, 2.3522)
            assertThat(GeoMath.haversineDistance(p, p)).isCloseTo(0.0, Offset.offset(0.001))
        }

        @Test
        fun `known distance between London and Paris`() {
            // London: 51.5074 N, 0.1278 W -> Paris: 48.8566 N, 2.3522 E
            // Expected distance: ~343.5 km
            val london = LatLon(51.5074, -0.1278)
            val paris = LatLon(48.8566, 2.3522)
            val distance = GeoMath.haversineDistance(london, paris)
            assertThat(distance).isCloseTo(343_500.0, Offset.offset(5000.0))
        }

        @Test
        fun `short distance between nearby points`() {
            // Two points ~100m apart (approximately)
            val p1 = LatLon(48.0000, 11.0000)
            val p2 = LatLon(48.0009, 11.0000) // ~100m north
            val distance = GeoMath.haversineDistance(p1, p2)
            assertThat(distance).isCloseTo(100.0, Offset.offset(5.0))
        }

        @Test
        fun `antipodal points give half earth circumference`() {
            val p1 = LatLon(0.0, 0.0)
            val p2 = LatLon(0.0, 180.0)
            val distance = GeoMath.haversineDistance(p1, p2)
            val halfCircumference = Math.PI * GeoMath.EARTH_RADIUS_M
            assertThat(distance).isCloseTo(halfCircumference, Offset.offset(1.0))
        }

        @Test
        fun `distance is symmetric`() {
            val p1 = LatLon(40.7128, -74.0060) // NYC
            val p2 = LatLon(34.0522, -118.2437) // LA
            assertThat(GeoMath.haversineDistance(p1, p2))
                .isCloseTo(GeoMath.haversineDistance(p2, p1), Offset.offset(0.001))
        }
    }

    @Nested
    inner class BearingTests {

        @Test
        fun `bearing north is 0 degrees`() {
            val p1 = LatLon(48.0, 11.0)
            val p2 = LatLon(49.0, 11.0)
            assertThat(GeoMath.bearing(p1, p2)).isCloseTo(0.0, Offset.offset(0.1))
        }

        @Test
        fun `bearing east is 90 degrees`() {
            val p1 = LatLon(0.0, 0.0) // equator for clean east bearing
            val p2 = LatLon(0.0, 1.0)
            assertThat(GeoMath.bearing(p1, p2)).isCloseTo(90.0, Offset.offset(0.1))
        }

        @Test
        fun `bearing south is 180 degrees`() {
            val p1 = LatLon(49.0, 11.0)
            val p2 = LatLon(48.0, 11.0)
            assertThat(GeoMath.bearing(p1, p2)).isCloseTo(180.0, Offset.offset(0.1))
        }

        @Test
        fun `bearing west is 270 degrees`() {
            val p1 = LatLon(0.0, 1.0)
            val p2 = LatLon(0.0, 0.0)
            assertThat(GeoMath.bearing(p1, p2)).isCloseTo(270.0, Offset.offset(0.1))
        }

        @Test
        fun `bearing is always in range 0 to 360`() {
            val p1 = LatLon(48.0, 11.0)
            val p2 = LatLon(47.5, 10.5) // southwest
            val bearing = GeoMath.bearing(p1, p2)
            assertThat(bearing).isBetween(0.0, 360.0)
        }
    }

    @Nested
    inner class BearingDifferenceTests {

        @Test
        fun `same bearing gives zero difference`() {
            assertThat(GeoMath.bearingDifference(90.0, 90.0)).isCloseTo(0.0, Offset.offset(0.001))
        }

        @Test
        fun `90 degree right turn`() {
            assertThat(GeoMath.bearingDifference(0.0, 90.0)).isCloseTo(90.0, Offset.offset(0.001))
        }

        @Test
        fun `90 degree left turn`() {
            assertThat(GeoMath.bearingDifference(90.0, 0.0)).isCloseTo(-90.0, Offset.offset(0.001))
        }

        @Test
        fun `wrapping across 360-0 boundary`() {
            assertThat(GeoMath.bearingDifference(350.0, 10.0)).isCloseTo(20.0, Offset.offset(0.001))
        }

        @Test
        fun `reverse wrapping across 360-0 boundary`() {
            assertThat(GeoMath.bearingDifference(10.0, 350.0)).isCloseTo(-20.0, Offset.offset(0.001))
        }

        @Test
        fun `180 degree turn`() {
            val diff = GeoMath.bearingDifference(0.0, 180.0)
            assertThat(Math.abs(diff)).isCloseTo(180.0, Offset.offset(0.001))
        }
    }

    @Nested
    inner class InterpolationTests {

        @Test
        fun `fraction 0 returns first point`() {
            val p1 = LatLon(48.0, 11.0)
            val p2 = LatLon(49.0, 12.0)
            val result = GeoMath.interpolate(p1, p2, 0.0)
            assertThat(result.lat).isEqualTo(p1.lat)
            assertThat(result.lon).isEqualTo(p1.lon)
        }

        @Test
        fun `fraction 1 returns second point`() {
            val p1 = LatLon(48.0, 11.0)
            val p2 = LatLon(49.0, 12.0)
            val result = GeoMath.interpolate(p1, p2, 1.0)
            assertThat(result.lat).isEqualTo(p2.lat)
            assertThat(result.lon).isEqualTo(p2.lon)
        }

        @Test
        fun `midpoint is equidistant from both endpoints`() {
            val p1 = LatLon(48.0, 11.0)
            val p2 = LatLon(48.001, 11.001)
            val mid = GeoMath.interpolate(p1, p2, 0.5)

            val d1 = GeoMath.haversineDistance(p1, mid)
            val d2 = GeoMath.haversineDistance(mid, p2)
            assertThat(d1).isCloseTo(d2, Offset.offset(0.5))
        }

        @Test
        fun `interpolated point is on the line between endpoints`() {
            val p1 = LatLon(48.0, 11.0)
            val p2 = LatLon(48.01, 11.01)
            val quarter = GeoMath.interpolate(p1, p2, 0.25)

            val totalDist = GeoMath.haversineDistance(p1, p2)
            val toQuarter = GeoMath.haversineDistance(p1, quarter)
            assertThat(toQuarter).isCloseTo(totalDist * 0.25, Offset.offset(1.0))
        }

        @Test
        fun `invalid fraction throws`() {
            val p1 = LatLon(48.0, 11.0)
            val p2 = LatLon(49.0, 12.0)
            assertThrows<IllegalArgumentException> { GeoMath.interpolate(p1, p2, -0.1) }
            assertThrows<IllegalArgumentException> { GeoMath.interpolate(p1, p2, 1.1) }
        }
    }

    @Nested
    inner class ProjectOntoSegmentTests {

        @Test
        fun `point on the segment projects exactly`() {
            val segStart = LatLon(48.0, 11.0)
            val segEnd = LatLon(48.001, 11.0)
            val mid = GeoMath.interpolate(segStart, segEnd, 0.5)

            val (fraction, dist) = GeoMath.projectOntoSegment(mid, segStart, segEnd)
            assertThat(fraction).isCloseTo(0.5, Offset.offset(0.05))
            assertThat(dist).isCloseTo(0.0, Offset.offset(1.0))
        }

        @Test
        fun `point perpendicular to segment midpoint`() {
            // Segment going north; point offset to the east
            val segStart = LatLon(48.0, 11.0)
            val segEnd = LatLon(48.001, 11.0)
            val offToSide = LatLon(48.0005, 11.0005) // offset east of midpoint

            val (fraction, dist) = GeoMath.projectOntoSegment(offToSide, segStart, segEnd)
            assertThat(fraction).isCloseTo(0.5, Offset.offset(0.1))
            assertThat(dist).isGreaterThan(0.0)
        }

        @Test
        fun `point beyond segment end clamps to 1`() {
            val segStart = LatLon(48.0, 11.0)
            val segEnd = LatLon(48.001, 11.0)
            val beyond = LatLon(48.002, 11.0) // well past the end

            val (fraction, _) = GeoMath.projectOntoSegment(beyond, segStart, segEnd)
            assertThat(fraction).isCloseTo(1.0, Offset.offset(0.01))
        }

        @Test
        fun `point before segment start clamps to 0`() {
            val segStart = LatLon(48.001, 11.0)
            val segEnd = LatLon(48.002, 11.0)
            val before = LatLon(48.0, 11.0) // before the start

            val (fraction, _) = GeoMath.projectOntoSegment(before, segStart, segEnd)
            assertThat(fraction).isCloseTo(0.0, Offset.offset(0.01))
        }
    }
}

package com.curvecall.engine.pipeline

import com.curvecall.engine.geo.GeoMath
import com.curvecall.engine.types.LatLon
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class InterpolatorTest {

    @Nested
    inner class BasicResampling {

        @Test
        fun `two far-apart points produce evenly spaced output`() {
            // Two points ~1000m apart
            val p1 = LatLon(48.0, 11.0)
            val p2 = LatLon(48.009, 11.0) // ~1000m north

            val result = Interpolator.resample(listOf(p1, p2), 10.0)

            // Should produce approximately 100 points (1000m / 10m)
            assertThat(result.size).isBetween(95, 105)

            // Verify spacing is approximately 10m between consecutive points
            for (i in 0 until result.size - 1) {
                val dist = GeoMath.haversineDistance(result[i], result[i + 1])
                assertThat(dist).isCloseTo(10.0, Offset.offset(2.0))
            }
        }

        @Test
        fun `three points with different spacings produce uniform output`() {
            val p1 = LatLon(48.0, 11.0)
            val p2 = LatLon(48.002, 11.0)  // ~222m
            val p3 = LatLon(48.0065, 11.0) // ~500m from p2

            val result = Interpolator.resample(listOf(p1, p2, p3), 10.0)

            // Total distance ~722m, so about 72 points
            assertThat(result.size).isBetween(65, 78)

            // Check uniformity
            for (i in 1 until result.size - 1) {
                val dist = GeoMath.haversineDistance(result[i], result[i + 1])
                assertThat(dist).isCloseTo(10.0, Offset.offset(3.0))
            }
        }

        @Test
        fun `first point is always included`() {
            val p1 = LatLon(48.0, 11.0)
            val p2 = LatLon(48.009, 11.0)

            val result = Interpolator.resample(listOf(p1, p2), 10.0)

            assertThat(result.first().lat).isCloseTo(p1.lat, Offset.offset(1e-6))
            assertThat(result.first().lon).isCloseTo(p1.lon, Offset.offset(1e-6))
        }

        @Test
        fun `very close points produce few output points`() {
            // Two points only 5m apart - less than spacing
            val p1 = LatLon(48.0, 11.0)
            val p2 = LatLon(48.000045, 11.0) // ~5m north

            val result = Interpolator.resample(listOf(p1, p2), 10.0)

            // Should have just the first point (5m < 10m spacing, and 5m < 0.5*10=5m so no second point)
            assertThat(result.size).isBetween(1, 2)
        }

        @Test
        fun `custom spacing of 20m works`() {
            val p1 = LatLon(48.0, 11.0)
            val p2 = LatLon(48.009, 11.0) // ~1000m

            val result = Interpolator.resample(listOf(p1, p2), 20.0)

            // Should produce approximately 50 points
            assertThat(result.size).isBetween(48, 55)
        }
    }

    @Nested
    inner class EdgeCases {

        @Test
        fun `fewer than 2 points throws`() {
            assertThrows<IllegalArgumentException> {
                Interpolator.resample(listOf(LatLon(48.0, 11.0)), 10.0)
            }
        }

        @Test
        fun `zero spacing throws`() {
            assertThrows<IllegalArgumentException> {
                Interpolator.resample(listOf(LatLon(48.0, 11.0), LatLon(48.001, 11.0)), 0.0)
            }
        }

        @Test
        fun `negative spacing throws`() {
            assertThrows<IllegalArgumentException> {
                Interpolator.resample(listOf(LatLon(48.0, 11.0), LatLon(48.001, 11.0)), -5.0)
            }
        }

        @Test
        fun `already uniformly spaced points pass through`() {
            // Create points that are already ~10m apart
            val points = (0..10).map { i ->
                LatLon(48.0 + i * 0.00009, 11.0) // ~10m steps
            }

            val result = Interpolator.resample(points, 10.0)

            // Should produce approximately the same number of points
            assertThat(result.size).isBetween(points.size - 2, points.size + 2)
        }
    }

    @Nested
    inner class CurvedPathTests {

        @Test
        fun `curved path preserves total distance`() {
            // A gentle curve going northeast then east
            val points = listOf(
                LatLon(48.0, 11.0),
                LatLon(48.003, 11.002),
                LatLon(48.005, 11.005),
                LatLon(48.006, 11.009)
            )

            // Compute total distance of original
            var originalDist = 0.0
            for (i in 0 until points.size - 1) {
                originalDist += GeoMath.haversineDistance(points[i], points[i + 1])
            }

            val result = Interpolator.resample(points, 10.0)

            // Compute total distance of resampled
            var resampledDist = 0.0
            for (i in 0 until result.size - 1) {
                resampledDist += GeoMath.haversineDistance(result[i], result[i + 1])
            }

            // Total distances should match within 5%
            assertThat(resampledDist).isCloseTo(originalDist, Offset.offset(originalDist * 0.05))
        }
    }
}

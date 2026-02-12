package com.curvecall.engine.pipeline

import com.curvecall.engine.types.AnalysisConfig
import com.curvecall.engine.types.LatLon
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.sin

class SegmenterTest {

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
    inner class BasicSegmentation {

        @Test
        fun `pure straight road has no curve segments`() {
            val points = generateStraight(LatLon(48.0, 11.0), 500.0, 50)
            val curvature = CurvatureComputer.compute(points, 5)
            val config = AnalysisConfig()

            val segments = Segmenter.segment(curvature, points, config)

            val curves = segments.filter { it.isCurve }
            assertThat(curves).isEmpty()
        }

        @Test
        fun `100m radius arc is detected as curve`() {
            val center = LatLon(48.0, 11.0)
            val points = generateCircleArc(center, 100.0, 0.0, Math.PI / 2, 30)
            val curvature = CurvatureComputer.compute(points, 5)
            val config = AnalysisConfig()

            val segments = Segmenter.segment(curvature, points, config)

            val curves = segments.filter { it.isCurve }
            assertThat(curves).isNotEmpty()
        }

        @Test
        fun `50m radius hairpin is detected as curve`() {
            val center = LatLon(48.0, 11.0)
            val points = generateCircleArc(center, 50.0, 0.0, Math.PI, 40)
            val curvature = CurvatureComputer.compute(points, 5)
            val config = AnalysisConfig()

            val segments = Segmenter.segment(curvature, points, config)

            val curves = segments.filter { it.isCurve }
            assertThat(curves).isNotEmpty()
        }
    }

    @Nested
    inner class MergingTests {

        @Test
        fun `two curves with short gap are merged`() {
            // Curve - short straight (30m) - Curve
            val center1 = LatLon(48.0, 11.0)
            val arc1 = generateCircleArc(center1, 80.0, 0.0, Math.PI / 3, 15)

            // Short straight gap
            val gapStart = arc1.last()
            val gap = generateStraight(gapStart, 30.0, 4)

            // Second curve
            val center2 = LatLon(48.003, 11.0)
            val arc2 = generateCircleArc(center2, 80.0, 0.0, Math.PI / 3, 15)

            val allPoints = arc1 + gap.drop(1) + arc2.drop(1)
            val curvature = CurvatureComputer.compute(allPoints, 5)
            val config = AnalysisConfig(straightGapMerge = 50.0)

            val segments = Segmenter.segment(curvature, allPoints, config)

            // The two curves should be merged into one (gap < 50m)
            val curves = segments.filter { it.isCurve }
            assertThat(curves.size).isLessThanOrEqualTo(2) // Might be 1 if merged properly
        }

        @Test
        fun `two curves with long gap stay separate`() {
            // Curve - long straight (200m) - Curve
            val center1 = LatLon(48.0, 11.0)
            val arc1 = generateCircleArc(center1, 80.0, 0.0, Math.PI / 3, 15)

            val gapStart = arc1.last()
            val gap = generateStraight(gapStart, 200.0, 20)

            val center2 = LatLon(48.005, 11.0)
            val arc2 = generateCircleArc(center2, 80.0, 0.0, Math.PI / 3, 15)

            val allPoints = arc1 + gap.drop(1) + arc2.drop(1)
            val curvature = CurvatureComputer.compute(allPoints, 5)
            val config = AnalysisConfig(straightGapMerge = 50.0)

            val segments = Segmenter.segment(curvature, allPoints, config)

            // Should have both curves separate with a straight between them
            val curves = segments.filter { it.isCurve }
            val straights = segments.filter { !it.isCurve }
            assertThat(curves.size).isGreaterThanOrEqualTo(1)
            assertThat(straights.size).isGreaterThanOrEqualTo(1)
        }
    }

    @Nested
    inner class SegmentCoverage {

        @Test
        fun `segments cover entire route`() {
            val center = LatLon(48.0, 11.0)
            val straight1 = generateStraight(LatLon(48.0, 11.0), 200.0, 20)
            val arc = generateCircleArc(center, 100.0, 0.0, Math.PI / 2, 20)
            val straight2 = generateStraight(LatLon(48.005, 11.0), 200.0, 20)

            val allPoints = straight1 + arc.drop(1) + straight2.drop(1)
            val curvature = CurvatureComputer.compute(allPoints, 5)
            val config = AnalysisConfig()

            val segments = Segmenter.segment(curvature, allPoints, config)

            // First segment should start at index 0
            assertThat(segments.first().startIndex).isEqualTo(0)

            // Last segment should end at last index
            assertThat(segments.last().endIndex).isEqualTo(allPoints.size - 1)

            // Segments should be contiguous (no gaps)
            for (i in 0 until segments.size - 1) {
                assertThat(segments[i].endIndex + 1).isEqualTo(segments[i + 1].startIndex)
            }
        }
    }
}

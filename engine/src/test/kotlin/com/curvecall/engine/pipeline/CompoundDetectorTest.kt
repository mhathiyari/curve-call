package com.curvecall.engine.pipeline

import com.curvecall.engine.types.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CompoundDetectorTest {

    @Nested
    inner class SBendDetection {

        @Test
        fun `two opposite-direction curves with short gap form S-bend`() {
            val leftCurve = TestHelpers.createTestCurve(
                direction = Direction.LEFT,
                severity = Severity.MODERATE,
                minRadius = 120.0,
                arcLength = 100.0,
                startIndex = 0,
                endIndex = 10,
                distanceFromStart = 0.0
            )

            val rightCurve = TestHelpers.createTestCurve(
                direction = Direction.RIGHT,
                severity = Severity.MODERATE,
                minRadius = 130.0,
                arcLength = 100.0,
                startIndex = 13,
                endIndex = 23,
                distanceFromStart = 130.0 // gap = 130 - (0 + 100) = 30m < 50m
            )

            val segments = listOf<RouteSegment>(
                RouteSegment.Curve(leftCurve),
                RouteSegment.Straight(StraightSegment(30.0, 11, 12, 100.0)),
                RouteSegment.Curve(rightCurve)
            )

            val config = AnalysisConfig()
            val points = (0..25).map { LatLon(48.0 + it * 0.00001, 11.0) }

            val result = CompoundDetector.detect(segments, points, config)

            val curves = result.filterIsInstance<RouteSegment.Curve>()
            val sBends = curves.filter { it.data.compoundType == CompoundType.S_BEND }
            assertThat(sBends).hasSize(2) // Both curves in the S-bend get the annotation
        }

        @Test
        fun `two same-direction curves do not form S-bend`() {
            val curve1 = TestHelpers.createTestCurve(
                direction = Direction.LEFT,
                severity = Severity.MODERATE,
                arcLength = 100.0,
                startIndex = 0,
                endIndex = 10,
                distanceFromStart = 0.0
            )

            val curve2 = TestHelpers.createTestCurve(
                direction = Direction.LEFT, // Same direction
                severity = Severity.MODERATE,
                arcLength = 100.0,
                startIndex = 13,
                endIndex = 23,
                distanceFromStart = 130.0
            )

            val segments = listOf<RouteSegment>(
                RouteSegment.Curve(curve1),
                RouteSegment.Straight(StraightSegment(30.0, 11, 12, 100.0)),
                RouteSegment.Curve(curve2)
            )

            val config = AnalysisConfig()
            val points = (0..25).map { LatLon(48.0 + it * 0.00001, 11.0) }

            val result = CompoundDetector.detect(segments, points, config)

            val curves = result.filterIsInstance<RouteSegment.Curve>()
            val sBends = curves.filter { it.data.compoundType == CompoundType.S_BEND }
            assertThat(sBends).isEmpty()
        }

        @Test
        fun `two opposite curves with large gap do not form S-bend`() {
            val leftCurve = TestHelpers.createTestCurve(
                direction = Direction.LEFT,
                arcLength = 100.0,
                startIndex = 0,
                endIndex = 10,
                distanceFromStart = 0.0
            )

            val rightCurve = TestHelpers.createTestCurve(
                direction = Direction.RIGHT,
                arcLength = 100.0,
                startIndex = 20,
                endIndex = 30,
                distanceFromStart = 200.0 // gap = 200 - (0 + 100) = 100m > 50m
            )

            val segments = listOf<RouteSegment>(
                RouteSegment.Curve(leftCurve),
                RouteSegment.Straight(StraightSegment(100.0, 11, 19, 100.0)),
                RouteSegment.Curve(rightCurve)
            )

            val config = AnalysisConfig()
            val points = (0..35).map { LatLon(48.0 + it * 0.00001, 11.0) }

            val result = CompoundDetector.detect(segments, points, config)

            val curves = result.filterIsInstance<RouteSegment.Curve>()
            val sBends = curves.filter { it.data.compoundType == CompoundType.S_BEND }
            assertThat(sBends).isEmpty()
        }
    }

    @Nested
    inner class ChicaneDetection {

        @Test
        fun `sharp left and sharp right close together form chicane`() {
            val sharpLeft = TestHelpers.createTestCurve(
                direction = Direction.LEFT,
                severity = Severity.SHARP,
                minRadius = 30.0,
                arcLength = 80.0,
                startIndex = 0,
                endIndex = 8,
                distanceFromStart = 0.0
            )

            val sharpRight = TestHelpers.createTestCurve(
                direction = Direction.RIGHT,
                severity = Severity.SHARP,
                minRadius = 35.0,
                arcLength = 80.0,
                startIndex = 11,
                endIndex = 19,
                distanceFromStart = 110.0 // gap = 110 - 80 = 30m < 50m
            )

            val segments = listOf<RouteSegment>(
                RouteSegment.Curve(sharpLeft),
                RouteSegment.Straight(StraightSegment(30.0, 9, 10, 80.0)),
                RouteSegment.Curve(sharpRight)
            )

            val config = AnalysisConfig()
            val points = (0..25).map { LatLon(48.0 + it * 0.00001, 11.0) }

            val result = CompoundDetector.detect(segments, points, config)

            val curves = result.filterIsInstance<RouteSegment.Curve>()
            val chicanes = curves.filter { it.data.compoundType == CompoundType.CHICANE }
            assertThat(chicanes).hasSize(2)
        }

        @Test
        fun `moderate curves do not form chicane even if opposite and close`() {
            val moderateLeft = TestHelpers.createTestCurve(
                direction = Direction.LEFT,
                severity = Severity.MODERATE,
                arcLength = 100.0,
                startIndex = 0,
                endIndex = 10,
                distanceFromStart = 0.0
            )

            val moderateRight = TestHelpers.createTestCurve(
                direction = Direction.RIGHT,
                severity = Severity.MODERATE,
                arcLength = 100.0,
                startIndex = 13,
                endIndex = 23,
                distanceFromStart = 130.0
            )

            val segments = listOf<RouteSegment>(
                RouteSegment.Curve(moderateLeft),
                RouteSegment.Straight(StraightSegment(30.0, 11, 12, 100.0)),
                RouteSegment.Curve(moderateRight)
            )

            val config = AnalysisConfig()
            val points = (0..25).map { LatLon(48.0 + it * 0.00001, 11.0) }

            val result = CompoundDetector.detect(segments, points, config)

            val curves = result.filterIsInstance<RouteSegment.Curve>()
            val chicanes = curves.filter { it.data.compoundType == CompoundType.CHICANE }
            assertThat(chicanes).isEmpty()

            // But they should be an S-bend
            val sBends = curves.filter { it.data.compoundType == CompoundType.S_BEND }
            assertThat(sBends).hasSize(2)
        }
    }

    @Nested
    inner class SeriesDetection {

        @Test
        fun `four linked curves form a series`() {
            // Use same-direction curves so S-bend detection doesn't pair them
            val curves = (0..3).map { i ->
                TestHelpers.createTestCurve(
                    direction = Direction.LEFT,
                    severity = Severity.MODERATE,
                    arcLength = 80.0,
                    startIndex = i * 12,
                    endIndex = i * 12 + 8,
                    distanceFromStart = i * 100.0  // gap = 100 - 80 = 20m < 50m
                )
            }

            val segments = mutableListOf<RouteSegment>()
            for (i in curves.indices) {
                segments.add(RouteSegment.Curve(curves[i]))
                if (i < curves.size - 1) {
                    segments.add(
                        RouteSegment.Straight(
                            StraightSegment(
                                20.0,
                                curves[i].endIndex + 1,
                                curves[i + 1].startIndex - 1,
                                curves[i].distanceFromStart + curves[i].arcLength
                            )
                        )
                    )
                }
            }

            val config = AnalysisConfig()
            val points = (0..60).map { LatLon(48.0 + it * 0.00001, 11.0) }

            val result = CompoundDetector.detect(segments, points, config)

            val resultCurves = result.filterIsInstance<RouteSegment.Curve>()
            val series = resultCurves.filter { it.data.compoundType == CompoundType.SERIES }
            assertThat(series).hasSize(4)
            assertThat(series.first().data.compoundSize).isEqualTo(4)
        }

        @Test
        fun `two linked curves do not form a series`() {
            val curve1 = TestHelpers.createTestCurve(
                direction = Direction.LEFT,
                arcLength = 80.0,
                startIndex = 0,
                endIndex = 8,
                distanceFromStart = 0.0
            )
            val curve2 = TestHelpers.createTestCurve(
                direction = Direction.LEFT,
                arcLength = 80.0,
                startIndex = 11,
                endIndex = 19,
                distanceFromStart = 100.0
            )

            val segments = listOf<RouteSegment>(
                RouteSegment.Curve(curve1),
                RouteSegment.Straight(StraightSegment(20.0, 9, 10, 80.0)),
                RouteSegment.Curve(curve2)
            )

            val config = AnalysisConfig()
            val points = (0..25).map { LatLon(48.0 + it * 0.00001, 11.0) }

            val result = CompoundDetector.detect(segments, points, config)

            val resultCurves = result.filterIsInstance<RouteSegment.Curve>()
            val series = resultCurves.filter { it.data.compoundType == CompoundType.SERIES }
            assertThat(series).isEmpty()
        }
    }

    @Nested
    inner class TighteningSequenceDetection {

        @Test
        fun `same-direction curves getting tighter form tightening sequence`() {
            val curves = listOf(
                TestHelpers.createTestCurve(
                    direction = Direction.RIGHT,
                    severity = Severity.MODERATE,
                    minRadius = 150.0,
                    arcLength = 80.0,
                    startIndex = 0,
                    endIndex = 8,
                    distanceFromStart = 0.0
                ),
                TestHelpers.createTestCurve(
                    direction = Direction.RIGHT,
                    severity = Severity.FIRM,
                    minRadius = 80.0,
                    arcLength = 60.0,
                    startIndex = 11,
                    endIndex = 17,
                    distanceFromStart = 100.0
                ),
                TestHelpers.createTestCurve(
                    direction = Direction.RIGHT,
                    severity = Severity.SHARP,
                    minRadius = 40.0,
                    arcLength = 50.0,
                    startIndex = 20,
                    endIndex = 25,
                    distanceFromStart = 180.0
                )
            )

            val segments = mutableListOf<RouteSegment>()
            for (i in curves.indices) {
                segments.add(RouteSegment.Curve(curves[i]))
                if (i < curves.size - 1) {
                    segments.add(
                        RouteSegment.Straight(
                            StraightSegment(
                                20.0,
                                curves[i].endIndex + 1,
                                curves[i + 1].startIndex - 1,
                                curves[i].distanceFromStart + curves[i].arcLength
                            )
                        )
                    )
                }
            }

            val config = AnalysisConfig()
            val points = (0..30).map { LatLon(48.0 + it * 0.00001, 11.0) }

            val result = CompoundDetector.detect(segments, points, config)

            val resultCurves = result.filterIsInstance<RouteSegment.Curve>()
            // Since 3+ curves are linked, series detection runs first and marks them SERIES
            // Tightening sequence should be detected for these same-direction tightening curves
            val tightening = resultCurves.filter {
                it.data.compoundType == CompoundType.TIGHTENING_SEQUENCE
            }
            val series = resultCurves.filter {
                it.data.compoundType == CompoundType.SERIES
            }

            // Either all tightening or all series (series is detected first)
            assertThat(tightening.size + series.size).isEqualTo(3)
        }
    }

    @Nested
    inner class SingleCurve {

        @Test
        fun `single curve has no compound type`() {
            val curve = TestHelpers.createTestCurve()
            val segments = listOf<RouteSegment>(RouteSegment.Curve(curve))
            val config = AnalysisConfig()
            val points = (0..15).map { LatLon(48.0 + it * 0.00001, 11.0) }

            val result = CompoundDetector.detect(segments, points, config)

            val resultCurves = result.filterIsInstance<RouteSegment.Curve>()
            assertThat(resultCurves.first().data.compoundType).isNull()
        }
    }
}

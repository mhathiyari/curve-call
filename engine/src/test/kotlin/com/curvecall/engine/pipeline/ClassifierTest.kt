package com.curvecall.engine.pipeline

import com.curvecall.engine.types.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.sin

class ClassifierTest {

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

    @Nested
    inner class SeverityClassification {

        @Test
        fun `radius over 200m is GENTLE`() {
            assertThat(Classifier.classifySeverity(250.0, SeverityThresholds()))
                .isEqualTo(Severity.GENTLE)
        }

        @Test
        fun `radius 150m is MODERATE`() {
            assertThat(Classifier.classifySeverity(150.0, SeverityThresholds()))
                .isEqualTo(Severity.MODERATE)
        }

        @Test
        fun `radius 100m is MODERATE (boundary)`() {
            // At exactly 100m, it's > firm (50) but <= moderate boundary (100), so FIRM
            // Wait: > 200 = GENTLE, > 100 = MODERATE, > 50 = FIRM, > 25 = SHARP, else HAIRPIN
            // 100 is NOT > 100, so it falls through to FIRM
            assertThat(Classifier.classifySeverity(100.0, SeverityThresholds()))
                .isEqualTo(Severity.FIRM)
        }

        @Test
        fun `radius 101m is MODERATE`() {
            assertThat(Classifier.classifySeverity(101.0, SeverityThresholds()))
                .isEqualTo(Severity.MODERATE)
        }

        @Test
        fun `radius 75m is FIRM`() {
            assertThat(Classifier.classifySeverity(75.0, SeverityThresholds()))
                .isEqualTo(Severity.FIRM)
        }

        @Test
        fun `radius 35m is SHARP`() {
            assertThat(Classifier.classifySeverity(35.0, SeverityThresholds()))
                .isEqualTo(Severity.SHARP)
        }

        @Test
        fun `radius 20m is HAIRPIN`() {
            assertThat(Classifier.classifySeverity(20.0, SeverityThresholds()))
                .isEqualTo(Severity.HAIRPIN)
        }

        @Test
        fun `radius 25m is SHARP (boundary)`() {
            // 25 is NOT > 25, so it falls through to HAIRPIN
            assertThat(Classifier.classifySeverity(25.0, SeverityThresholds()))
                .isEqualTo(Severity.HAIRPIN)
        }

        @Test
        fun `radius 26m is SHARP`() {
            assertThat(Classifier.classifySeverity(26.0, SeverityThresholds()))
                .isEqualTo(Severity.SHARP)
        }
    }

    @Nested
    inner class FullClassification {

        @Test
        fun `hairpin curve is classified as HAIRPIN severity`() {
            val center = LatLon(48.0, 11.0)
            val points = generateCircleArc(center, 20.0, 0.0, Math.PI, 30)
            val curvature = CurvatureComputer.compute(points, 5)
            val config = AnalysisConfig()

            val rawSegment = Segmenter.RawSegment(0, points.size - 1, true)
            val curve = Classifier.classify(rawSegment, curvature, points, config, 0.0)

            assertThat(curve.severity).isEqualTo(Severity.HAIRPIN)
        }

        @Test
        fun `gentle curve is classified as GENTLE`() {
            val center = LatLon(48.0, 11.0)
            val points = generateCircleArc(center, 300.0, 0.0, Math.PI / 6, 30)
            val curvature = CurvatureComputer.compute(points, 5)
            val config = AnalysisConfig()

            val rawSegment = Segmenter.RawSegment(0, points.size - 1, true)
            val curve = Classifier.classify(rawSegment, curvature, points, config, 0.0)

            assertThat(curve.severity).isEqualTo(Severity.GENTLE)
        }

        @Test
        fun `constant radius curve has no TIGHTENING modifier`() {
            val center = LatLon(48.0, 11.0)
            val points = generateCircleArc(center, 100.0, 0.0, Math.PI / 2, 40)
            val curvature = CurvatureComputer.compute(points, 5)
            val config = AnalysisConfig()

            val rawSegment = Segmenter.RawSegment(0, points.size - 1, true)
            val curve = Classifier.classify(rawSegment, curvature, points, config, 0.0)

            assertThat(curve.modifiers).doesNotContain(CurveModifier.TIGHTENING)
        }

        @Test
        fun `tightening spiral has TIGHTENING modifier`() {
            // Spiral: radius decreases from 200m to 50m
            val center = LatLon(48.0, 11.0)
            val metersPerDegreeLat = 111_320.0
            val metersPerDegreeLon = 111_320.0 * cos(Math.toRadians(center.lat))

            val numPoints = 40
            val points = (0 until numPoints).map { i ->
                val t = Math.PI / 2 * i / (numPoints - 1)
                val radius = 200.0 - 150.0 * (i.toDouble() / (numPoints - 1)) // 200m -> 50m
                val dx = radius * cos(t)
                val dy = radius * sin(t)
                LatLon(
                    center.lat + dy / metersPerDegreeLat,
                    center.lon + dx / metersPerDegreeLon
                )
            }

            val curvature = CurvatureComputer.compute(points, 5)
            val config = AnalysisConfig()

            val rawSegment = Segmenter.RawSegment(0, points.size - 1, true)
            val curve = Classifier.classify(rawSegment, curvature, points, config, 0.0)

            assertThat(curve.modifiers).contains(CurveModifier.TIGHTENING)
        }

        @Test
        fun `long arc has LONG modifier`() {
            val center = LatLon(48.0, 11.0)
            // 300m radius, sweeping ~90 degrees = arc length ~471m
            val points = generateCircleArc(center, 300.0, 0.0, Math.PI / 2, 50)
            val curvature = CurvatureComputer.compute(points, 5)
            val config = AnalysisConfig()

            val rawSegment = Segmenter.RawSegment(0, points.size - 1, true)
            val curve = Classifier.classify(rawSegment, curvature, points, config, 0.0)

            assertThat(curve.arcLength).isGreaterThan(200.0)
            assertThat(curve.modifiers).contains(CurveModifier.LONG)
        }
    }

    @Nested
    inner class AngleChangeTests {

        @Test
        fun `90-degree right angle turn`() {
            // Create a sharp right-angle turn with small arc
            val metersPerDegreeLat = 111_320.0
            val center = LatLon(48.0, 11.0)
            val metersPerDegreeLon = metersPerDegreeLat * cos(Math.toRadians(center.lat))

            // 15m radius, 90 degrees = arc length ~23.6m (< 50m)
            val points = generateCircleArc(center, 15.0, 0.0, Math.PI / 2, 15)
            val curvature = CurvatureComputer.compute(points, 3)
            val config = AnalysisConfig()

            val rawSegment = Segmenter.RawSegment(0, points.size - 1, true)
            val curve = Classifier.classify(rawSegment, curvature, points, config, 0.0)

            // Angle change should be approximately 90 degrees
            assertThat(curve.totalAngleChange).isBetween(70.0, 110.0)
        }

        @Test
        fun `hairpin has large angle change`() {
            val center = LatLon(48.0, 11.0)
            // 20m radius, 180 degrees
            val points = generateCircleArc(center, 20.0, 0.0, Math.PI, 30)
            val curvature = CurvatureComputer.compute(points, 5)
            val config = AnalysisConfig()

            val rawSegment = Segmenter.RawSegment(0, points.size - 1, true)
            val curve = Classifier.classify(rawSegment, curvature, points, config, 0.0)

            assertThat(curve.totalAngleChange).isGreaterThan(140.0)
        }
    }

    @Nested
    inner class DirectionTests {

        @Test
        fun `counter-clockwise arc is LEFT`() {
            val center = LatLon(48.0, 11.0)
            val points = generateCircleArc(center, 100.0, 0.0, Math.PI / 2, 20)
            val curvature = CurvatureComputer.compute(points, 3)
            val config = AnalysisConfig()

            val rawSegment = Segmenter.RawSegment(0, points.size - 1, true)
            val curve = Classifier.classify(rawSegment, curvature, points, config, 0.0)

            assertThat(curve.direction).isEqualTo(Direction.LEFT)
        }

        @Test
        fun `clockwise arc is RIGHT`() {
            val center = LatLon(48.0, 11.0)
            val points = generateCircleArc(center, 100.0, Math.PI / 2, 0.0, 20)
            val curvature = CurvatureComputer.compute(points, 3)
            val config = AnalysisConfig()

            val rawSegment = Segmenter.RawSegment(0, points.size - 1, true)
            val curve = Classifier.classify(rawSegment, curvature, points, config, 0.0)

            assertThat(curve.direction).isEqualTo(Direction.RIGHT)
        }
    }
}

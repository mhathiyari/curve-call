package com.curvecall.engine.pipeline

import com.curvecall.engine.types.AnalysisConfig
import com.curvecall.engine.types.LatLon
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DataQualityCheckerTest {

    @Nested
    inner class SparseRegionDetection {

        @Test
        fun `dense points have no sparse regions`() {
            // Points ~10m apart
            val points = (0..100).map { i ->
                LatLon(48.0 + i * 0.00009, 11.0) // ~10m steps, going north
            }

            val config = AnalysisConfig(sparseNodeThreshold = 100.0)
            val sparseRegions = DataQualityChecker.detectSparseRegions(points, config)

            assertThat(sparseRegions).isEmpty()
        }

        @Test
        fun `widely spaced points with bearing change are flagged`() {
            // Create a path with a 200m gap that has bearing change
            val points = listOf(
                LatLon(48.0, 11.0),
                LatLon(48.001, 11.0),      // heading north
                LatLon(48.003, 11.002),     // 200m+ gap, changing direction
                LatLon(48.004, 11.003),     // continuing NE
                LatLon(48.005, 11.004)
            )

            val config = AnalysisConfig(sparseNodeThreshold = 100.0)
            val sparseRegions = DataQualityChecker.detectSparseRegions(points, config)

            assertThat(sparseRegions).isNotEmpty()
        }

        @Test
        fun `widely spaced straight points are not flagged`() {
            // Points 200m apart but all in a straight line (no bearing change)
            val points = (0..5).map { i ->
                LatLon(48.0 + i * 0.0018, 11.0) // ~200m steps, straight north
            }

            val config = AnalysisConfig(sparseNodeThreshold = 100.0)
            val sparseRegions = DataQualityChecker.detectSparseRegions(points, config)

            // Should be empty because bearing doesn't change on a straight road
            assertThat(sparseRegions).isEmpty()
        }
    }

    @Nested
    inner class ConfidenceScoring {

        @Test
        fun `curves outside sparse regions have full confidence`() {
            val curves = listOf(
                TestHelpers.createTestCurve(
                    distanceFromStart = 500.0,
                    arcLength = 100.0,
                    confidence = 1.0f
                )
            )

            val sparseRegions = listOf(
                DataQualityChecker.SparseRegion(
                    startDistance = 0.0,
                    endDistance = 200.0,
                    maxSpacing = 150.0,
                    bearingChange = 30.0
                )
            )

            val result = DataQualityChecker.applyConfidence(curves, sparseRegions)

            assertThat(result.first().confidence).isEqualTo(1.0f)
        }

        @Test
        fun `curves fully within sparse regions get low confidence`() {
            val curves = listOf(
                TestHelpers.createTestCurve(
                    distanceFromStart = 50.0,
                    arcLength = 80.0,
                    confidence = 1.0f
                )
            )

            val sparseRegions = listOf(
                DataQualityChecker.SparseRegion(
                    startDistance = 0.0,
                    endDistance = 200.0,
                    maxSpacing = 150.0,
                    bearingChange = 30.0
                )
            )

            val result = DataQualityChecker.applyConfidence(curves, sparseRegions)

            assertThat(result.first().confidence).isLessThan(0.5f)
        }

        @Test
        fun `curves partially overlapping sparse regions get medium confidence`() {
            val curves = listOf(
                TestHelpers.createTestCurve(
                    distanceFromStart = 150.0,
                    arcLength = 200.0, // extends from 150 to 350
                    confidence = 1.0f
                )
            )

            val sparseRegions = listOf(
                DataQualityChecker.SparseRegion(
                    startDistance = 0.0,
                    endDistance = 200.0, // overlaps from 150 to 200 = 50m out of 200m
                    maxSpacing = 150.0,
                    bearingChange = 30.0
                )
            )

            val result = DataQualityChecker.applyConfidence(curves, sparseRegions)

            assertThat(result.first().confidence).isLessThan(1.0f)
            assertThat(result.first().confidence).isGreaterThan(0.0f)
        }

        @Test
        fun `no sparse regions leaves all curves unchanged`() {
            val curves = listOf(
                TestHelpers.createTestCurve(confidence = 1.0f),
                TestHelpers.createTestCurve(confidence = 1.0f, distanceFromStart = 500.0)
            )

            val result = DataQualityChecker.applyConfidence(curves, emptyList())

            assertThat(result).hasSize(2)
            assertThat(result[0].confidence).isEqualTo(1.0f)
            assertThat(result[1].confidence).isEqualTo(1.0f)
        }
    }
}

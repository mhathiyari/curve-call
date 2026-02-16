package com.curvecall.engine.types

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RouteMetadataTest {

    // ================================================================
    // Road Class Lookups
    // ================================================================

    @Nested
    inner class RoadClassLookup {

        @Test
        fun `returns correct road class within span`() {
            val metadata = RouteMetadata(
                roadClassSpans = listOf(
                    MetadataSpan(0.0, 500.0, RoadClass.PRIMARY),
                    MetadataSpan(500.0, 1200.0, RoadClass.SECONDARY),
                    MetadataSpan(1200.0, 2000.0, RoadClass.RESIDENTIAL)
                )
            )

            assertThat(metadata.roadClassAt(0.0)).isEqualTo(RoadClass.PRIMARY)
            assertThat(metadata.roadClassAt(250.0)).isEqualTo(RoadClass.PRIMARY)
            assertThat(metadata.roadClassAt(499.9)).isEqualTo(RoadClass.PRIMARY)
            assertThat(metadata.roadClassAt(500.0)).isEqualTo(RoadClass.SECONDARY)
            assertThat(metadata.roadClassAt(800.0)).isEqualTo(RoadClass.SECONDARY)
            assertThat(metadata.roadClassAt(1500.0)).isEqualTo(RoadClass.RESIDENTIAL)
        }

        @Test
        fun `returns null for distance past all spans`() {
            val metadata = RouteMetadata(
                roadClassSpans = listOf(
                    MetadataSpan(0.0, 500.0, RoadClass.PRIMARY)
                )
            )

            assertThat(metadata.roadClassAt(500.0)).isNull()
            assertThat(metadata.roadClassAt(1000.0)).isNull()
        }

        @Test
        fun `returns null for negative distance`() {
            val metadata = RouteMetadata(
                roadClassSpans = listOf(
                    MetadataSpan(0.0, 500.0, RoadClass.PRIMARY)
                )
            )

            assertThat(metadata.roadClassAt(-10.0)).isNull()
        }

        @Test
        fun `returns null for empty spans`() {
            val metadata = RouteMetadata()
            assertThat(metadata.roadClassAt(100.0)).isNull()
        }
    }

    // ================================================================
    // Surface Lookups
    // ================================================================

    @Nested
    inner class SurfaceLookup {

        @Test
        fun `returns correct surface within span`() {
            val metadata = RouteMetadata(
                surfaceSpans = listOf(
                    MetadataSpan(0.0, 1000.0, Surface.ASPHALT),
                    MetadataSpan(1000.0, 1500.0, Surface.GRAVEL),
                    MetadataSpan(1500.0, 2000.0, Surface.ASPHALT)
                )
            )

            assertThat(metadata.surfaceAt(500.0)).isEqualTo(Surface.ASPHALT)
            assertThat(metadata.surfaceAt(1200.0)).isEqualTo(Surface.GRAVEL)
            assertThat(metadata.surfaceAt(1800.0)).isEqualTo(Surface.ASPHALT)
        }

        @Test
        fun `returns null for gap between spans`() {
            val metadata = RouteMetadata(
                surfaceSpans = listOf(
                    MetadataSpan(0.0, 500.0, Surface.ASPHALT),
                    MetadataSpan(600.0, 1000.0, Surface.GRAVEL)
                )
            )

            assertThat(metadata.surfaceAt(550.0)).isNull()
        }
    }

    // ================================================================
    // Max Speed Lookups
    // ================================================================

    @Nested
    inner class MaxSpeedLookup {

        @Test
        fun `returns correct speed limit`() {
            val metadata = RouteMetadata(
                maxSpeedSpans = listOf(
                    MetadataSpan(0.0, 500.0, 50.0),
                    MetadataSpan(500.0, 1500.0, 80.0),
                    MetadataSpan(1500.0, 3000.0, 100.0)
                )
            )

            assertThat(metadata.maxSpeedAt(200.0)).isEqualTo(50.0)
            assertThat(metadata.maxSpeedAt(1000.0)).isEqualTo(80.0)
            assertThat(metadata.maxSpeedAt(2500.0)).isEqualTo(100.0)
        }

        @Test
        fun `returns null for span with null value`() {
            val metadata = RouteMetadata(
                maxSpeedSpans = listOf(
                    MetadataSpan(0.0, 500.0, null),
                    MetadataSpan(500.0, 1000.0, 60.0)
                )
            )

            assertThat(metadata.maxSpeedAt(200.0)).isNull()
            assertThat(metadata.maxSpeedAt(700.0)).isEqualTo(60.0)
        }
    }

    // ================================================================
    // Intersection Detection
    // ================================================================

    @Nested
    inner class IntersectionDetection {

        @Test
        fun `detects near intersection within default tolerance`() {
            val metadata = RouteMetadata(
                intersections = listOf(
                    IntersectionPoint(500.0, TurnType.TURN_LEFT, "Main St"),
                    IntersectionPoint(1500.0, TurnType.TURN_RIGHT, "Oak Ave")
                )
            )

            assertThat(metadata.isNearIntersection(500.0)).isTrue()
            assertThat(metadata.isNearIntersection(520.0)).isTrue()  // within 30m
            assertThat(metadata.isNearIntersection(480.0)).isTrue()  // within 30m
            assertThat(metadata.isNearIntersection(470.0)).isTrue()  // exactly 30m
        }

        @Test
        fun `does not detect distant intersection`() {
            val metadata = RouteMetadata(
                intersections = listOf(
                    IntersectionPoint(500.0, TurnType.TURN_LEFT)
                )
            )

            assertThat(metadata.isNearIntersection(400.0)).isFalse()  // 100m away
            assertThat(metadata.isNearIntersection(600.0)).isFalse()  // 100m away
        }

        @Test
        fun `custom tolerance works`() {
            val metadata = RouteMetadata(
                intersections = listOf(
                    IntersectionPoint(500.0, TurnType.TURN_LEFT)
                )
            )

            assertThat(metadata.isNearIntersection(450.0, toleranceM = 50.0)).isTrue()
            assertThat(metadata.isNearIntersection(450.0, toleranceM = 10.0)).isFalse()
        }

        @Test
        fun `no intersections returns false`() {
            val metadata = RouteMetadata()
            assertThat(metadata.isNearIntersection(500.0)).isFalse()
        }
    }

    // ================================================================
    // Enum Parsing
    // ================================================================

    @Nested
    inner class EnumParsing {

        @Test
        fun `RoadClass fromString handles common values`() {
            assertThat(RoadClass.fromString("motorway")).isEqualTo(RoadClass.MOTORWAY)
            assertThat(RoadClass.fromString("motorway_link")).isEqualTo(RoadClass.MOTORWAY)
            assertThat(RoadClass.fromString("primary")).isEqualTo(RoadClass.PRIMARY)
            assertThat(RoadClass.fromString("primary_link")).isEqualTo(RoadClass.PRIMARY)
            assertThat(RoadClass.fromString("residential")).isEqualTo(RoadClass.RESIDENTIAL)
            assertThat(RoadClass.fromString("living_street")).isEqualTo(RoadClass.RESIDENTIAL)
            assertThat(RoadClass.fromString("track")).isEqualTo(RoadClass.TRACK)
            assertThat(RoadClass.fromString("unknown")).isEqualTo(RoadClass.OTHER)
        }

        @Test
        fun `RoadClass fromString is case insensitive`() {
            assertThat(RoadClass.fromString("MOTORWAY")).isEqualTo(RoadClass.MOTORWAY)
            assertThat(RoadClass.fromString("Primary")).isEqualTo(RoadClass.PRIMARY)
        }

        @Test
        fun `Surface fromString handles common values`() {
            assertThat(Surface.fromString("asphalt")).isEqualTo(Surface.ASPHALT)
            assertThat(Surface.fromString("concrete")).isEqualTo(Surface.CONCRETE)
            assertThat(Surface.fromString("gravel")).isEqualTo(Surface.GRAVEL)
            assertThat(Surface.fromString("fine_gravel")).isEqualTo(Surface.GRAVEL)
            assertThat(Surface.fromString("dirt")).isEqualTo(Surface.DIRT)
            assertThat(Surface.fromString("paving_stones")).isEqualTo(Surface.COBBLESTONE)
            assertThat(Surface.fromString("something_new")).isEqualTo(Surface.OTHER)
        }

        @Test
        fun `Surface isPaved correctly classifies surfaces`() {
            assertThat(Surface.PAVED.isPaved).isTrue()
            assertThat(Surface.ASPHALT.isPaved).isTrue()
            assertThat(Surface.CONCRETE.isPaved).isTrue()
            assertThat(Surface.GRAVEL.isPaved).isFalse()
            assertThat(Surface.DIRT.isPaved).isFalse()
            assertThat(Surface.OTHER.isPaved).isFalse()
        }

        @Test
        fun `TurnType isTurn identifies actual turns`() {
            assertThat(TurnType.TURN_LEFT.isTurn).isTrue()
            assertThat(TurnType.TURN_RIGHT.isTurn).isTrue()
            assertThat(TurnType.U_TURN.isTurn).isTrue()
            assertThat(TurnType.ROUNDABOUT.isTurn).isTrue()
            assertThat(TurnType.DEPART.isTurn).isFalse()
            assertThat(TurnType.ARRIVE.isTurn).isFalse()
            assertThat(TurnType.OTHER.isTurn).isFalse()
        }
    }

    // ================================================================
    // Edge Cases
    // ================================================================

    @Nested
    inner class EdgeCases {

        @Test
        fun `boundary at exact span start returns span value`() {
            val metadata = RouteMetadata(
                roadClassSpans = listOf(
                    MetadataSpan(100.0, 200.0, RoadClass.PRIMARY)
                )
            )
            assertThat(metadata.roadClassAt(100.0)).isEqualTo(RoadClass.PRIMARY)
        }

        @Test
        fun `boundary at exact span end returns null (exclusive)`() {
            val metadata = RouteMetadata(
                roadClassSpans = listOf(
                    MetadataSpan(100.0, 200.0, RoadClass.PRIMARY)
                )
            )
            assertThat(metadata.roadClassAt(200.0)).isNull()
        }

        @Test
        fun `single-point span works for startDistance`() {
            // A span that covers just the start distance (startDistanceM == distance)
            val metadata = RouteMetadata(
                surfaceSpans = listOf(
                    MetadataSpan(0.0, 0.1, Surface.GRAVEL)
                )
            )
            assertThat(metadata.surfaceAt(0.0)).isEqualTo(Surface.GRAVEL)
            assertThat(metadata.surfaceAt(0.05)).isEqualTo(Surface.GRAVEL)
        }

        @Test
        fun `empty metadata returns all nulls and false`() {
            val metadata = RouteMetadata()
            assertThat(metadata.roadClassAt(100.0)).isNull()
            assertThat(metadata.surfaceAt(100.0)).isNull()
            assertThat(metadata.maxSpeedAt(100.0)).isNull()
            assertThat(metadata.isNearIntersection(100.0)).isFalse()
        }
    }
}

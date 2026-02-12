package com.curvecall.engine

import com.curvecall.engine.types.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MapMatcherTest {

    /**
     * Creates a simple north-going route with evenly spaced points.
     */
    private fun createSimpleRoute(numPoints: Int = 100, spacingDeg: Double = 0.00009): List<LatLon> {
        return (0 until numPoints).map { i ->
            LatLon(48.0 + i * spacingDeg, 11.0) // ~10m north steps
        }
    }

    /**
     * Creates test segments for a route with one curve in the middle.
     */
    private fun createSegmentsWithCurve(curveStartDist: Double = 400.0): List<RouteSegment> {
        return listOf(
            RouteSegment.Straight(StraightSegment(400.0, 0, 39, 0.0)),
            RouteSegment.Curve(
                CurveSegment(
                    direction = Direction.RIGHT,
                    severity = Severity.MODERATE,
                    minRadius = 120.0,
                    arcLength = 100.0,
                    modifiers = emptySet(),
                    totalAngleChange = 45.0,
                    is90Degree = false,
                    advisorySpeedMs = 20.0,
                    leanAngleDeg = null,
                    compoundType = null,
                    compoundSize = null,
                    confidence = 1.0f,
                    startIndex = 40,
                    endIndex = 49,
                    startPoint = LatLon(48.0036, 11.0),
                    endPoint = LatLon(48.0045, 11.0),
                    distanceFromStart = curveStartDist
                )
            ),
            RouteSegment.Straight(StraightSegment(500.0, 50, 99, 500.0))
        )
    }

    @Nested
    inner class BasicMatching {

        @Test
        fun `GPS on route has zero distance from route`() {
            val routePoints = createSimpleRoute()
            val segments = listOf<RouteSegment>(
                RouteSegment.Straight(StraightSegment(990.0, 0, 99, 0.0))
            )

            val matcher = MapMatcher(routePoints, segments)

            // Match a point that's exactly on the route
            val gps = routePoints[50]
            val result = matcher.matchToRoute(gps)

            assertThat(result.distanceFromRoute).isCloseTo(0.0, Offset.offset(2.0))
            assertThat(result.isOffRoute).isFalse()
        }

        @Test
        fun `GPS near route start has low progress`() {
            val routePoints = createSimpleRoute()
            val segments = listOf<RouteSegment>(
                RouteSegment.Straight(StraightSegment(990.0, 0, 99, 0.0))
            )

            val matcher = MapMatcher(routePoints, segments)
            val result = matcher.matchToRoute(routePoints[5])

            assertThat(result.routeProgress).isCloseTo(50.0, Offset.offset(20.0))
            assertThat(result.progressFraction).isLessThan(0.15)
        }

        @Test
        fun `GPS near route end has high progress`() {
            val routePoints = createSimpleRoute()
            val segments = listOf<RouteSegment>(
                RouteSegment.Straight(StraightSegment(990.0, 0, 99, 0.0))
            )

            val matcher = MapMatcher(routePoints, segments)
            val result = matcher.matchToRoute(routePoints[95])

            assertThat(result.progressFraction).isGreaterThan(0.85)
        }

        @Test
        fun `GPS slightly off route snaps correctly`() {
            val routePoints = createSimpleRoute()
            val segments = listOf<RouteSegment>(
                RouteSegment.Straight(StraightSegment(990.0, 0, 99, 0.0))
            )

            val matcher = MapMatcher(routePoints, segments)

            // Point 30m east of route midpoint
            val offsetGps = LatLon(
                48.0 + 50 * 0.00009,
                11.0 + 0.0004 // ~30m east at lat 48
            )

            val result = matcher.matchToRoute(offsetGps)

            assertThat(result.distanceFromRoute).isBetween(20.0, 50.0)
            assertThat(result.isOffRoute).isFalse()
        }
    }

    @Nested
    inner class OffRouteDetection {

        @Test
        fun `GPS 150m from route is off-route`() {
            val routePoints = createSimpleRoute()
            val segments = listOf<RouteSegment>(
                RouteSegment.Straight(StraightSegment(990.0, 0, 99, 0.0))
            )

            val matcher = MapMatcher(routePoints, segments)

            // Point 150m east of route
            val farGps = LatLon(
                48.0 + 50 * 0.00009,
                11.0 + 0.002 // ~150m east at lat 48
            )

            val result = matcher.matchToRoute(farGps)

            assertThat(result.isOffRoute).isTrue()
            assertThat(result.distanceFromRoute).isGreaterThan(100.0)
        }

        @Test
        fun `GPS just within threshold is not off-route`() {
            val routePoints = createSimpleRoute()
            val segments = listOf<RouteSegment>(
                RouteSegment.Straight(StraightSegment(990.0, 0, 99, 0.0))
            )

            val matcher = MapMatcher(routePoints, segments)

            // Point ~80m east of route
            val nearGps = LatLon(
                48.0 + 50 * 0.00009,
                11.0 + 0.0011 // ~80m east at lat 48
            )

            val result = matcher.matchToRoute(nearGps)

            assertThat(result.isOffRoute).isFalse()
        }
    }

    @Nested
    inner class NextCurveDetection {

        @Test
        fun `distance to next curve is computed`() {
            val routePoints = createSimpleRoute()
            val segments = createSegmentsWithCurve(400.0)

            val matcher = MapMatcher(routePoints, segments)

            // Match at the start of the route
            val result = matcher.matchToRoute(routePoints[0])

            assertThat(result.distanceToNextCurve).isNotNull()
            assertThat(result.distanceToNextCurve!!).isCloseTo(400.0, Offset.offset(50.0))
            assertThat(result.nextCurve).isNotNull()
        }

        @Test
        fun `no next curve after passing all curves`() {
            val routePoints = createSimpleRoute()
            val segments = createSegmentsWithCurve(400.0)

            val matcher = MapMatcher(routePoints, segments)

            // Match near the end of the route (past the curve)
            val result = matcher.matchToRoute(routePoints[90])

            assertThat(result.distanceToNextCurve).isNull()
            assertThat(result.nextCurve).isNull()
        }
    }

    @Nested
    inner class UpcomingCurves {

        @Test
        fun `findUpcomingCurves returns curves within distance`() {
            val routePoints = createSimpleRoute()
            val segments = createSegmentsWithCurve(400.0)

            val matcher = MapMatcher(routePoints, segments)

            val upcoming = matcher.findUpcomingCurves(0.0, 500.0)
            assertThat(upcoming).hasSize(1)
            assertThat(upcoming.first().first).isCloseTo(400.0, Offset.offset(10.0))
        }

        @Test
        fun `findUpcomingCurves excludes curves beyond distance`() {
            val routePoints = createSimpleRoute()
            val segments = createSegmentsWithCurve(400.0)

            val matcher = MapMatcher(routePoints, segments)

            val upcoming = matcher.findUpcomingCurves(0.0, 100.0)
            assertThat(upcoming).isEmpty()
        }
    }

    @Nested
    inner class SlidingWindow {

        @Test
        fun `sequential GPS updates work efficiently`() {
            val routePoints = createSimpleRoute()
            val segments = listOf<RouteSegment>(
                RouteSegment.Straight(StraightSegment(990.0, 0, 99, 0.0))
            )

            val matcher = MapMatcher(routePoints, segments)

            // Simulate sequential GPS updates along the route
            var lastProgress = 0.0
            for (i in listOf(10, 20, 30, 40, 50, 60, 70, 80, 90)) {
                val result = matcher.matchToRoute(routePoints[i])
                assertThat(result.routeProgress).isGreaterThan(lastProgress)
                lastProgress = result.routeProgress
            }
        }

        @Test
        fun `reset allows rematching from start`() {
            val routePoints = createSimpleRoute()
            val segments = listOf<RouteSegment>(
                RouteSegment.Straight(StraightSegment(990.0, 0, 99, 0.0))
            )

            val matcher = MapMatcher(routePoints, segments)

            // Match near end
            matcher.matchToRoute(routePoints[90])

            // Reset
            matcher.reset()

            // Match near start again
            val result = matcher.matchToRoute(routePoints[5])
            assertThat(result.routeProgress).isLessThan(100.0)
        }
    }

    @Nested
    inner class EdgeCases {

        @Test
        fun `route with exactly 2 points works`() {
            val routePoints = listOf(LatLon(48.0, 11.0), LatLon(48.001, 11.0))
            val segments = listOf<RouteSegment>(
                RouteSegment.Straight(StraightSegment(111.0, 0, 1, 0.0))
            )

            val matcher = MapMatcher(routePoints, segments)
            val result = matcher.matchToRoute(LatLon(48.0005, 11.0))

            assertThat(result.distanceFromRoute).isCloseTo(0.0, Offset.offset(2.0))
        }

        @Test
        fun `fewer than 2 route points throws`() {
            assertThrows<IllegalArgumentException> {
                MapMatcher(listOf(LatLon(48.0, 11.0)), emptyList())
            }
        }

        @Test
        fun `totalDistance matches route length`() {
            val routePoints = createSimpleRoute(numPoints = 50)
            val matcher = MapMatcher(routePoints, emptyList())

            // 49 segments of ~10m each = ~490m
            assertThat(matcher.totalDistance).isBetween(400.0, 600.0)
        }
    }
}

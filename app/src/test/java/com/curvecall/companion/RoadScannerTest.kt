package com.curvecall.companion

import com.graphhopper.routing.util.EncodingManager
import com.graphhopper.routing.ev.EnumEncodedValue
import com.graphhopper.routing.ev.RoadClass
import com.graphhopper.routing.ev.VehicleAccess
import com.graphhopper.storage.BaseGraph
import com.graphhopper.storage.RAMDirectory
import com.graphhopper.storage.index.LocationIndexTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos

/**
 * Tests for [RoadScanner] edge-walk and fork-resolution logic against small
 * synthetic in-memory GraphHopper graphs.
 *
 * Coordinate convention: a local meter grid anchored at (52.0, 13.0), with
 * northM/eastM offsets converted to lat/lon. Roads are laid out west-to-east
 * unless stated otherwise; "heading 90" means driving east.
 */
class RoadScannerTest {

    companion object {
        private const val LAT0 = 52.0
        private const val LON0 = 13.0
        private const val M_PER_DEG_LAT = 111320.0

        private fun lat(northM: Double) = LAT0 + northM / M_PER_DEG_LAT
        private fun lon(eastM: Double) =
            LON0 + eastM / (M_PER_DEG_LAT * cos(Math.toRadians(LAT0)))
    }

    /** Synthetic graph fixture with car access + road class encoded values. */
    private class TestGraph {
        val accessEnc = VehicleAccess.create("car")
        val roadClassEnc = EnumEncodedValue(RoadClass.KEY, RoadClass::class.java)
        val em: EncodingManager = EncodingManager.start()
            .add(accessEnc)
            .add(roadClassEnc)
            .build()
        val graph: BaseGraph = BaseGraph.Builder(em).create()

        fun node(id: Int, northM: Double, eastM: Double) {
            graph.nodeAccess.setNode(id, lat(northM), lon(eastM))
        }

        /** Create an edge; returns its edge id. fwd/bwd control one-way access. */
        fun edge(
            a: Int,
            b: Int,
            fwd: Boolean = true,
            bwd: Boolean = true,
            roadClass: RoadClass = RoadClass.SECONDARY
        ): Int {
            val e = graph.edge(a, b).setDistance(100.0)
            e.set(accessEnc, fwd, bwd)
            e.set(roadClassEnc, roadClass)
            return e.edge
        }

        fun scan(
            northM: Double,
            eastM: Double,
            headingDeg: Double,
            speedMs: Double = 20.0
        ): RoadScanner.ScanResult? {
            val index = LocationIndexTree(graph, RAMDirectory()).apply { prepareIndex() }
            return RoadScanner().scan(
                graph, index, em, lat(northM), lon(eastM), headingDeg, speedMs, "car"
            )
        }
    }

    private fun assertEndsNear(
        result: RoadScanner.ScanResult,
        northM: Double,
        eastM: Double,
        toleranceM: Double = 30.0
    ) {
        val end = result.polyline.last()
        val dNorth = (end.lat - lat(northM)) * M_PER_DEG_LAT
        val dEast = (end.lon - lon(eastM)) * M_PER_DEG_LAT * cos(Math.toRadians(LAT0))
        val dist = Math.hypot(dNorth, dEast)
        assertTrue(
            "polyline ends ${dist.toInt()}m from expected point " +
                    "(${end.lat}, ${end.lon})",
            dist <= toleranceM
        )
    }

    // -- Basic walking --

    @Test
    fun `straight road is walked to its end`() {
        val g = TestGraph()
        // 5 nodes, 500m apart, west to east
        for (i in 0..4) g.node(i, 0.0, i * 500.0)
        val edges = (0..3).map { g.edge(it, it + 1) }

        val result = g.scan(northM = 10.0, eastM = 50.0, headingDeg = 90.0)

        assertNotNull(result)
        result!!
        assertEquals(edges, result.edgeIds)
        assertEndsNear(result, 0.0, 2000.0)
        assertTrue(result.distanceFromRoad in 5.0..20.0)
    }

    @Test
    fun `scan stops at dead end`() {
        val g = TestGraph()
        g.node(0, 0.0, 0.0)
        g.node(1, 0.0, 600.0)
        g.node(2, 0.0, 1200.0)
        g.edge(0, 1)
        g.edge(1, 2)

        val result = g.scan(northM = 5.0, eastM = 100.0, headingDeg = 90.0)

        assertNotNull(result)
        assertEndsNear(result!!, 0.0, 1200.0)
    }

    @Test
    fun `off-road position returns null`() {
        val g = TestGraph()
        g.node(0, 0.0, 0.0)
        g.node(1, 0.0, 1000.0)
        g.edge(0, 1)

        // 200m north of the road, beyond MAX_SNAP_DISTANCE_M
        assertNull(g.scan(northM = 200.0, eastM = 500.0, headingDeg = 90.0))
    }

    // -- Fork resolution --

    @Test
    fun `fork prefers heading continuation`() {
        val g = TestGraph()
        g.node(0, 0.0, 0.0)
        g.node(1, 0.0, 1000.0)
        g.node(2, 0.0, 2000.0)      // straight ahead (east)
        g.node(3, 700.0, 1700.0)    // branch ~45° to the north-east
        g.edge(0, 1)
        val straight = g.edge(1, 2)
        val branch = g.edge(1, 3)

        val result = g.scan(northM = 5.0, eastM = 100.0, headingDeg = 90.0)

        assertNotNull(result)
        assertTrue(result!!.edgeIds.contains(straight))
        assertFalse(result.edgeIds.contains(branch))
    }

    @Test
    fun `fork avoids one-way edge against travel direction`() {
        val g = TestGraph()
        g.node(0, 0.0, 0.0)
        g.node(1, 0.0, 1000.0)
        g.node(2, 0.0, 2000.0)      // straight ahead, but one-way towards us
        g.node(3, 700.0, 1700.0)    // 45° branch, two-way
        g.edge(0, 1)
        // Edge created 2 -> 1 with forward-only access: drivable only towards node 1
        val oneWay = g.edge(2, 1, fwd = true, bwd = false)
        val branch = g.edge(1, 3)

        val result = g.scan(northM = 5.0, eastM = 100.0, headingDeg = 90.0)

        assertNotNull(result)
        assertFalse(result!!.edgeIds.contains(oneWay))
        assertTrue(result.edgeIds.contains(branch))
    }

    @Test
    fun `snapped one-way edge is walked in its legal direction`() {
        val g = TestGraph()
        g.node(0, 0.0, 0.0)
        g.node(1, 0.0, 1000.0)
        g.node(2, 0.0, 2000.0)
        g.edge(0, 1, fwd = true, bwd = false) // one-way eastbound
        g.edge(1, 2, fwd = true, bwd = false)

        // Heading west (against the one-way): the walk must still go east
        val result = g.scan(northM = 5.0, eastM = 500.0, headingDeg = 270.0)

        assertNotNull(result)
        assertEndsNear(result!!, 0.0, 2000.0)
    }

    @Test
    fun `road class continuity beats a slightly straighter side road`() {
        val g = TestGraph()
        g.node(0, 0.0, 0.0)
        g.node(1, 0.0, 1000.0)
        g.node(2, 175.0, 990.0)     // service road, almost straight (~10° off)
        g.node(3, 450.0, 1780.0)    // primary continues, ~30° off
        g.edge(0, 1, roadClass = RoadClass.PRIMARY)
        val service = g.edge(1, 2, roadClass = RoadClass.SERVICE)
        val primary = g.edge(1, 3, roadClass = RoadClass.PRIMARY)

        val result = g.scan(northM = 5.0, eastM = 100.0, headingDeg = 90.0)

        assertNotNull(result)
        assertTrue(result!!.edgeIds.contains(primary))
        assertFalse(result.edgeIds.contains(service))
    }

    // -- Hairpins vs U-turns --

    @Test
    fun `hairpin continuation is followed when it is the only way on`() {
        val g = TestGraph()
        g.node(0, 0.0, 0.0)
        g.node(1, 0.0, 1000.0)      // hairpin apex
        // Continuation doubles back at ~160° from east heading
        g.node(2, -340.0, 60.0)
        g.edge(0, 1)
        val hairpin = g.edge(1, 2)

        val result = g.scan(northM = 5.0, eastM = 100.0, headingDeg = 90.0)

        assertNotNull(result)
        assertTrue(
            "hairpin edge should be followed, got edges ${result!!.edgeIds}",
            result.edgeIds.contains(hairpin)
        )
        assertEndsNear(result, -340.0, 60.0)
    }

    @Test
    fun `near-exact reversal is rejected as a U-turn`() {
        val g = TestGraph()
        g.node(0, 0.0, 0.0)
        g.node(1, 0.0, 600.0)
        g.node(2, 0.0, 1200.0)
        // Candidate at node 2 goes back almost exactly west (~178°)
        g.node(3, 20.0, 100.0)
        g.edge(0, 1)
        g.edge(1, 2)
        val uTurn = g.edge(2, 3)

        val result = g.scan(northM = 5.0, eastM = 100.0, headingDeg = 90.0)

        assertNotNull(result)
        assertFalse(result!!.edgeIds.contains(uTurn))
        assertEndsNear(result, 0.0, 1200.0)
    }

    // -- Window length --

    @Test
    fun `scan is capped near the maximum scan distance`() {
        val g = TestGraph()
        // 10 km of road in 500m segments
        for (i in 0..20) g.node(i, 0.0, i * 500.0)
        for (i in 0..19) g.edge(i, i + 1)

        val result = g.scan(northM = 5.0, eastM = 50.0, headingDeg = 90.0)

        assertNotNull(result)
        var total = 0.0
        val pts = result!!.polyline
        for (i in 1 until pts.size) {
            val dNorth = (pts[i].lat - pts[i - 1].lat) * M_PER_DEG_LAT
            val dEast = (pts[i].lon - pts[i - 1].lon) *
                    M_PER_DEG_LAT * cos(Math.toRadians(LAT0))
            total += Math.hypot(dNorth, dEast)
        }
        assertTrue(
            "scan length ${total.toInt()}m should be near ${RoadScanner.SCAN_DISTANCE_M}",
            abs(total - RoadScanner.SCAN_DISTANCE_M) < 600.0
        )
    }
}

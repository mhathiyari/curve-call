package com.curvecall.narration

import com.curvecall.engine.types.CurveSegment
import com.curvecall.engine.types.Direction
import com.curvecall.engine.types.LatLon
import com.curvecall.engine.types.RouteSegment
import com.curvecall.engine.types.Severity
import com.curvecall.narration.types.DrivingMode
import com.curvecall.narration.types.NarrationConfig
import com.curvecall.narration.types.NarrationEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for NarrationManager: cooldown-free voice chaining, interrupt logic,
 * urgent bypass, and a Mt. Hamilton switchback simulation.
 */
class NarrationManagerTest {

    private lateinit var manager: NarrationManager
    private lateinit var listener: RecordingListener

    private val config = NarrationConfig(
        mode = DrivingMode.CAR,
        verbosity = 2
    )

    @BeforeEach
    fun setUp() {
        manager = NarrationManager(config = config)
        listener = RecordingListener()
        manager.setListener(listener)
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /** Creates a test CurveSegment with sensible defaults. */
    private fun testCurve(
        direction: Direction = Direction.RIGHT,
        severity: Severity = Severity.MODERATE,
        minRadius: Double = 100.0,
        arcLength: Double = 50.0,
        advisorySpeedMs: Double? = null,
        distanceFromStart: Double = 0.0,
        startIndex: Int = 0,
        endIndex: Int = 10
    ) = CurveSegment(
        direction = direction,
        severity = severity,
        minRadius = minRadius,
        arcLength = arcLength,
        modifiers = emptySet(),
        totalAngleChange = 45.0,
        is90Degree = false,
        advisorySpeedMs = advisorySpeedMs,
        leanAngleDeg = null,
        compoundType = null,
        compoundSize = null,
        confidence = 1.0f,
        startIndex = startIndex,
        endIndex = endIndex,
        startPoint = LatLon(37.33, -121.77),
        endPoint = LatLon(37.331, -121.771),
        distanceFromStart = distanceFromStart
    )

    /** Builds RouteSegment list from curve specs. */
    private fun routeOf(vararg curves: CurveSegment): List<RouteSegment> =
        curves.map { RouteSegment.Curve(it) }

    /**
     * Drives the manager forward to the given position at the given speed.
     * This simulates a single GPS tick.
     */
    private fun driveTo(progressMeters: Double, speedMs: Double = 13.9) {
        manager.onLocationUpdate(progressMeters, speedMs)
    }

    /** Simulates TTS completion. */
    private fun ttsFinished() {
        manager.onNarrationComplete()
    }

    /**
     * Listener that records all narration callbacks for assertion.
     */
    class RecordingListener : NarrationManager.NarrationListener {
        val narrations = mutableListOf<NarrationEvent>()
        val interrupts = mutableListOf<NarrationEvent>()
        val urgentAlerts = mutableListOf<NarrationEvent>()
        var pauseCount = 0
        var resumeCount = 0

        override fun onNarration(event: NarrationEvent) { narrations.add(event) }
        override fun onInterrupt(event: NarrationEvent) { interrupts.add(event) }
        override fun onUrgentAlert(event: NarrationEvent) { urgentAlerts.add(event) }
        override fun onPaused(reason: String) { pauseCount++ }
        override fun onResumed() { resumeCount++ }

        /** All fired events (narrations + interrupts + urgent alerts). */
        val allFired: List<NarrationEvent> get() = narrations + interrupts + urgentAlerts
    }

    // ========================================================================
    // Consecutive Curves — No Cooldown Gap
    // ========================================================================

    @Nested
    @DisplayName("Consecutive Curves Fire Without Gap")
    inner class ConsecutiveCurves {

        @Test
        fun `two curves 150m apart both get narrated`() {
            // Curves far enough apart not to merge (>80m) but close enough
            // that old cooldown would have blocked the second one.
            val c1 = testCurve(
                severity = Severity.SHARP, minRadius = 40.0,
                advisorySpeedMs = 11.1, distanceFromStart = 500.0,
                arcLength = 50.0, startIndex = 0, endIndex = 10
            )
            val c2 = testCurve(
                severity = Severity.SHARP, minRadius = 40.0,
                advisorySpeedMs = 11.1, distanceFromStart = 700.0,
                direction = Direction.LEFT, arcLength = 50.0,
                startIndex = 20, endIndex = 30
            )

            manager.loadRoute(routeOf(c1, c2), emptyList())

            // Drive close to c1's trigger zone at 50 km/h (13.9 m/s)
            driveTo(400.0, 13.9)
            assertThat(listener.narrations).hasSize(1)
            assertThat(listener.narrations[0].curveDistanceFromStart).isEqualTo(500.0)

            // TTS finishes — drive closer to c2
            ttsFinished()
            driveTo(600.0, 13.9)
            assertThat(listener.narrations).hasSize(2)
            assertThat(listener.narrations[1].curveDistanceFromStart).isEqualTo(700.0)
        }

        @Test
        fun `immediate chaining fires curve B from onNarrationComplete`() {
            // Two curves where B is already in lead distance when A's TTS completes.
            // c1 ends at 250m, c2 starts at 340m → 90m gap (>80, not merged).
            val c1 = testCurve(
                severity = Severity.SHARP, minRadius = 40.0,
                advisorySpeedMs = 11.1, distanceFromStart = 200.0,
                arcLength = 50.0, startIndex = 0, endIndex = 10
            )
            val c2 = testCurve(
                severity = Severity.SHARP, minRadius = 40.0,
                advisorySpeedMs = 11.1, distanceFromStart = 340.0,
                direction = Direction.LEFT, arcLength = 50.0,
                startIndex = 20, endIndex = 30
            )

            manager.loadRoute(routeOf(c1, c2), emptyList())

            // Drive into c1's trigger zone (100m away → within 100m MIN_ANNOUNCEMENT_DISTANCE)
            driveTo(100.0, 13.9)
            assertThat(listener.narrations).hasSize(1)

            // Advance driver while TTS plays — c2 is now within lead distance
            // but can't fire because c1 is still playing.
            driveTo(250.0, 13.9)
            assertThat(listener.narrations).hasSize(1) // blocked by currently-playing

            // TTS finishes → evaluateAndFireNext() chains c2 immediately
            // c2 is 340-250 = 90m away → within 100m lead distance → FIRE
            ttsFinished()
            assertThat(listener.narrations).hasSize(2)
            assertThat(listener.narrations[1].curveDistanceFromStart).isEqualTo(340.0)
        }
    }

    // ========================================================================
    // Urgent Bypass
    // ========================================================================

    @Nested
    @DisplayName("Urgent Alerts")
    inner class UrgentAlerts {

        @Test
        fun `urgent alert fires when dangerously close to braking point`() {
            val c = testCurve(
                severity = Severity.HAIRPIN, minRadius = 25.0,
                advisorySpeedMs = 6.9, distanceFromStart = 300.0,
                arcLength = 30.0, startIndex = 0, endIndex = 10
            )

            manager.loadRoute(routeOf(c), emptyList())

            // Drive fast and close: braking distance ≈ (27.8² - 6.9²)/8 ≈ 90.6m
            // urgencyThreshold = 0.6 → urgent when <54.4m
            driveTo(260.0, 27.8)
            assertThat(listener.urgentAlerts).hasSize(1)
            assertThat(listener.urgentAlerts[0].text).startsWith("Brake,")
        }

        @Test
        fun `urgent bypasses currently playing narration`() {
            val gentle = testCurve(
                severity = Severity.MODERATE, minRadius = 100.0,
                distanceFromStart = 300.0, arcLength = 50.0,
                startIndex = 0, endIndex = 10
            )
            val hairpin = testCurve(
                severity = Severity.HAIRPIN, minRadius = 25.0,
                advisorySpeedMs = 6.9, distanceFromStart = 500.0,
                direction = Direction.LEFT, arcLength = 30.0,
                startIndex = 20, endIndex = 30
            )

            manager.loadRoute(routeOf(gentle, hairpin), emptyList())

            // Trigger gentle narration at low speed
            driveTo(200.0, 13.9)
            assertThat(listener.narrations).hasSize(1)

            // Now drive fast toward hairpin — enters urgent zone
            driveTo(460.0, 27.8)
            assertThat(listener.urgentAlerts).hasSize(1)
        }
    }

    // ========================================================================
    // Interrupt on Priority Escalation
    // ========================================================================

    @Nested
    @DisplayName("Interrupt Logic")
    inner class InterruptLogic {

        @Test
        fun `sharp interrupts gentle that is currently playing`() {
            // gentle ends at 350 (300+50), sharp starts at 500 → 150m gap (>80, not merged)
            val gentle = testCurve(
                severity = Severity.GENTLE, minRadius = 200.0,
                distanceFromStart = 300.0, arcLength = 50.0,
                startIndex = 0, endIndex = 10
            )
            val sharp = testCurve(
                severity = Severity.SHARP, minRadius = 40.0,
                advisorySpeedMs = 11.1, distanceFromStart = 500.0,
                direction = Direction.LEFT, arcLength = 50.0,
                startIndex = 20, endIndex = 30
            )

            // Use detailed verbosity so gentle curves are narrated
            val detailedConfig = config.copy(verbosity = 3)
            manager = NarrationManager(config = detailedConfig)
            manager.setListener(listener)
            manager.loadRoute(routeOf(gentle, sharp), emptyList())

            // Trigger gentle narration (100m away → within MIN_ANNOUNCEMENT_DISTANCE)
            driveTo(200.0, 13.9)
            assertThat(listener.narrations).hasSize(1)

            // Drive further — sharp enters trigger zone (500-400 = 100m) and should interrupt
            driveTo(400.0, 13.9)
            assertThat(listener.interrupts).hasSize(1)
            assertThat(listener.interrupts[0].priority).isEqualTo(NarrationEvent.PRIORITY_SHARP)
        }

        @Test
        fun `same priority does not interrupt`() {
            val sharp1 = testCurve(
                severity = Severity.SHARP, minRadius = 40.0,
                advisorySpeedMs = 11.1, distanceFromStart = 300.0,
                arcLength = 50.0, startIndex = 0, endIndex = 10
            )
            val sharp2 = testCurve(
                severity = Severity.SHARP, minRadius = 40.0,
                advisorySpeedMs = 11.1, distanceFromStart = 350.0,
                direction = Direction.LEFT, arcLength = 50.0,
                startIndex = 20, endIndex = 30
            )

            manager.loadRoute(routeOf(sharp1, sharp2), emptyList())

            driveTo(200.0, 13.9)
            assertThat(listener.narrations).hasSize(1)

            // Second sharp enters range — should NOT interrupt same priority
            driveTo(250.0, 13.9)
            assertThat(listener.interrupts).isEmpty()
        }
    }

    // ========================================================================
    // Events Past Driver Not Fired
    // ========================================================================

    @Nested
    @DisplayName("Events Past Driver")
    inner class PastDriver {

        @Test
        fun `curve behind driver position is never fired`() {
            val c = testCurve(
                severity = Severity.SHARP, minRadius = 40.0,
                advisorySpeedMs = 11.1, distanceFromStart = 200.0,
                arcLength = 50.0, startIndex = 0, endIndex = 10
            )

            manager.loadRoute(routeOf(c), emptyList())

            // Driver is already past the curve
            driveTo(300.0, 13.9)
            assertThat(listener.allFired).isEmpty()
        }
    }

    // ========================================================================
    // Merged Events
    // ========================================================================

    @Nested
    @DisplayName("Merged Events")
    inner class MergedEvents {

        @Test
        fun `curves within 80m merge into single narration`() {
            // Two curves 60m apart (< 80m threshold) → should merge
            val c1 = testCurve(
                severity = Severity.SHARP, minRadius = 40.0,
                advisorySpeedMs = 11.1, distanceFromStart = 300.0,
                arcLength = 50.0, startIndex = 0, endIndex = 10
            )
            val c2 = testCurve(
                severity = Severity.HAIRPIN, minRadius = 25.0,
                advisorySpeedMs = 6.9, distanceFromStart = 410.0,
                direction = Direction.LEFT, arcLength = 30.0,
                startIndex = 20, endIndex = 30
            )
            // gap = 410 - (300 + 50) = 60m < 80m → merged

            manager.loadRoute(routeOf(c1, c2), emptyList())

            // Only one event in the queue
            assertThat(manager.upcomingEvents()).hasSize(1)

            // Drive to trigger it
            driveTo(200.0, 13.9)
            assertThat(listener.narrations).hasSize(1)
            // Different severity (SHARP→HAIRPIN) and gap>30m → "followed by" connector
            assertThat(listener.narrations[0].text).contains("followed by")
        }
    }

    // ========================================================================
    // No Double-Fire
    // ========================================================================

    @Nested
    @DisplayName("No Double-Fire")
    inner class NoDoubleFire {

        @Test
        fun `same curve not fired again on subsequent ticks`() {
            val c = testCurve(
                severity = Severity.SHARP, minRadius = 40.0,
                advisorySpeedMs = 11.1, distanceFromStart = 300.0,
                arcLength = 50.0, startIndex = 0, endIndex = 10
            )

            manager.loadRoute(routeOf(c), emptyList())

            // First tick triggers it
            driveTo(200.0, 13.9)
            assertThat(listener.narrations).hasSize(1)

            // Subsequent ticks should not re-trigger
            driveTo(210.0, 13.9)
            driveTo(220.0, 13.9)
            driveTo(230.0, 13.9)
            assertThat(listener.narrations).hasSize(1)
        }

        @Test
        fun `GPS jitter does not re-trigger delivered narration`() {
            val c = testCurve(
                severity = Severity.SHARP, minRadius = 40.0,
                advisorySpeedMs = 11.1, distanceFromStart = 300.0,
                arcLength = 50.0, startIndex = 0, endIndex = 10
            )

            manager.loadRoute(routeOf(c), emptyList())

            driveTo(200.0, 13.9)
            assertThat(listener.narrations).hasSize(1)
            ttsFinished()

            // GPS jumps backwards then forward
            driveTo(195.0, 13.9)
            driveTo(205.0, 13.9)
            assertThat(listener.narrations).hasSize(1)
        }
    }

    // ========================================================================
    // Passed While Playing — switchback recovery
    // ========================================================================

    @Nested
    @DisplayName("Passed While Playing")
    inner class PassedWhilePlaying {

        @Test
        fun `curve B fires after driver passes it while curve A was playing`() {
            // Both SHARP (same priority) so B won't interrupt A — tests chaining recovery.
            // Curve A at 200m, curve B at 350m (100m gap, not merged).
            // At 27.8 m/s (100 km/h), driver covers ~83m during 3s TTS.
            val cA = testCurve(
                severity = Severity.SHARP, minRadius = 40.0,
                advisorySpeedMs = 11.1, distanceFromStart = 200.0,
                arcLength = 50.0, startIndex = 0, endIndex = 10
            )
            val cB = testCurve(
                severity = Severity.SHARP, minRadius = 40.0,
                advisorySpeedMs = 11.1, distanceFromStart = 350.0,
                direction = Direction.LEFT, arcLength = 50.0,
                startIndex = 20, endIndex = 30
            )

            manager.loadRoute(routeOf(cA, cB), emptyList())

            // Drive at high speed — A triggers
            driveTo(100.0, 27.8)
            assertThat(listener.narrations).hasSize(1)
            assertThat(listener.narrations[0].curveDistanceFromStart).isEqualTo(200.0)

            // GPS ticks while A is playing — B enters FIRE zone but can't fire
            // (same priority, no interrupt)
            driveTo(200.0, 27.8)
            driveTo(300.0, 27.8)
            assertThat(listener.narrations).hasSize(1) // still blocked

            // Driver passes B's entry point (350m) while A is still playing
            driveTo(380.0, 27.8)
            assertThat(listener.narrations).hasSize(1) // still blocked

            // A's TTS finishes — B should fire even though driver is past it
            // pastBuffer = 27.8 * 5 = 139m → B at 350 is 30m behind → within buffer
            ttsFinished()
            assertThat(listener.narrations).hasSize(2)
            assertThat(listener.narrations[1].curveDistanceFromStart).isEqualTo(350.0)
        }

        @Test
        fun `multiple curves passed while playing are all recovered`() {
            // Three SHARP curves (same priority, no interrupts). Driver passes
            // B while A is playing. After each TTS completion, the next fires.
            // Uses moderate speed (13.9 m/s) so C stays in normal FIRE range.
            val cA = testCurve(
                severity = Severity.SHARP, minRadius = 40.0,
                advisorySpeedMs = 11.1, distanceFromStart = 200.0,
                arcLength = 50.0, startIndex = 0, endIndex = 10
            )
            val cB = testCurve(
                severity = Severity.SHARP, minRadius = 40.0,
                advisorySpeedMs = 11.1, distanceFromStart = 350.0,
                direction = Direction.LEFT, arcLength = 50.0,
                startIndex = 20, endIndex = 30
            )
            val cC = testCurve(
                severity = Severity.SHARP, minRadius = 40.0,
                advisorySpeedMs = 11.1, distanceFromStart = 500.0,
                arcLength = 50.0, startIndex = 40, endIndex = 50
            )

            manager.loadRoute(routeOf(cA, cB, cC), emptyList())

            // A fires at 13.9 m/s
            driveTo(100.0, 13.9)
            assertThat(listener.narrations).hasSize(1)

            // Driver passes B while A plays. At 13.9 m/s, pastBuffer = 69.5m.
            // Position 410: B at 350 is 60m behind → within 69.5m buffer.
            // C at 500 is 90m ahead → within 100m lead → FIRE but blocked.
            driveTo(410.0, 13.9)
            assertThat(listener.narrations).hasSize(1) // blocked

            // A finishes → B fires (behind driver, within lookback buffer)
            ttsFinished()
            assertThat(listener.allFired).hasSize(2)
            assertThat(listener.allFired[1].curveDistanceFromStart).isEqualTo(350.0)

            // B finishes → C fires (90m ahead, normal FIRE)
            ttsFinished()
            assertThat(listener.allFired).hasSize(3)
            assertThat(listener.allFired[2].curveDistanceFromStart).isEqualTo(500.0)
        }

        @Test
        fun `events too far behind are not fired`() {
            // If the driver is WAY past a curve (beyond the lookback buffer),
            // it should NOT fire — the info is no longer useful.
            val c = testCurve(
                severity = Severity.SHARP, minRadius = 40.0,
                advisorySpeedMs = 11.1, distanceFromStart = 200.0,
                arcLength = 50.0, startIndex = 0, endIndex = 10
            )

            manager.loadRoute(routeOf(c), emptyList())

            // Drive past the curve without it ever firing (no TTS blocking, just
            // jumped past it — e.g., GPS had a gap)
            driveTo(600.0, 13.9)
            // At 13.9 m/s, pastBuffer = 13.9 * 5 = 69.5m
            // Curve at 200m, driver at 600m → 400m behind → outside buffer
            assertThat(listener.allFired).isEmpty()
        }
    }

    // ========================================================================
    // Pause / Resume
    // ========================================================================

    @Nested
    @DisplayName("Pause and Resume")
    inner class PauseResume {

        @Test
        fun `no narrations fire while paused`() {
            val c = testCurve(
                severity = Severity.SHARP, minRadius = 40.0,
                advisorySpeedMs = 11.1, distanceFromStart = 300.0,
                arcLength = 50.0, startIndex = 0, endIndex = 10
            )

            manager.loadRoute(routeOf(c), emptyList())
            manager.pause()

            driveTo(200.0, 13.9)
            assertThat(listener.allFired).isEmpty()
            assertThat(listener.pauseCount).isEqualTo(1)
        }

        @Test
        fun `narrations resume after unpause`() {
            val c = testCurve(
                severity = Severity.SHARP, minRadius = 40.0,
                advisorySpeedMs = 11.1, distanceFromStart = 300.0,
                arcLength = 50.0, startIndex = 0, endIndex = 10
            )

            manager.loadRoute(routeOf(c), emptyList())
            manager.pause()
            driveTo(200.0, 13.9)
            assertThat(listener.allFired).isEmpty()

            manager.resume()
            driveTo(200.0, 13.9)
            assertThat(listener.narrations).hasSize(1)
            assertThat(listener.resumeCount).isEqualTo(1)
        }

        @Test
        fun `onNarrationComplete does not chain while paused`() {
            val c1 = testCurve(
                severity = Severity.SHARP, minRadius = 40.0,
                advisorySpeedMs = 11.1, distanceFromStart = 200.0,
                arcLength = 50.0, startIndex = 0, endIndex = 10
            )
            val c2 = testCurve(
                severity = Severity.SHARP, minRadius = 40.0,
                advisorySpeedMs = 11.1, distanceFromStart = 350.0,
                direction = Direction.LEFT, arcLength = 50.0,
                startIndex = 20, endIndex = 30
            )

            manager.loadRoute(routeOf(c1, c2), emptyList())
            driveTo(100.0, 13.9)
            assertThat(listener.narrations).hasSize(1)

            // Pause while TTS is playing, then complete
            manager.pause()
            driveTo(142.0, 13.9)
            ttsFinished()

            // Should NOT chain c2 while paused
            assertThat(listener.narrations).hasSize(1)
        }
    }

    // ========================================================================
    // Mt. Hamilton Switchback Simulation
    // ========================================================================

    @Nested
    @DisplayName("Mt. Hamilton Simulation")
    inner class MtHamiltonSimulation {

        /**
         * Simulates driving the upper switchback section of Mt. Hamilton Road
         * (37.330609, -121.766173 → summit).
         *
         * 12 curves: alternating hairpins (r=25m), sharps (r=40m), and
         * moderates (r=80m), with gaps of 100-250m between them.
         *
         * At 50 km/h (13.9 m/s) with old 3s cooldown:
         * - 3s × 13.9 m/s = 41.7m of dead road per cooldown
         * - Curves 100m apart → ~7.2s gap at 13.9 m/s
         * - TTS ~2-3s per narration → only ~1-4s between TTS end and next curve
         * - Old cooldown (3s) would block ~4/12 curves
         *
         * With immediate chaining: all important curves are narrated.
         * With winding detection: 12 curves form a winding section.
         * - 4 HAIRPIN + 4 SHARP break through (severity >= SHARP)
         * - 4 MODERATE are suppressed (replaced by winding overview)
         * - 1 winding overview is added
         * Total: 9+ events (overview + 8 breakthrough + possible transitions)
         */
        @Test
        fun `switchback curves are narrated with winding overview`() {
            // Define the Mt. Hamilton upper switchback section
            data class CurveDef(
                val distance: Double,
                val dir: Direction,
                val severity: Severity,
                val radius: Double,
                val arcLength: Double,
                val advisorySpeedMs: Double?
            )

            val curveDefs = listOf(
                CurveDef(1000.0, Direction.RIGHT, Severity.HAIRPIN, 25.0, 40.0, 6.9),   // Hairpin R
                CurveDef(1200.0, Direction.LEFT, Severity.SHARP, 40.0, 50.0, 11.1),      // Sharp L
                CurveDef(1400.0, Direction.RIGHT, Severity.MODERATE, 80.0, 60.0, null),   // Moderate R (suppressed)
                CurveDef(1550.0, Direction.LEFT, Severity.HAIRPIN, 25.0, 40.0, 6.9),      // Hairpin L
                CurveDef(1750.0, Direction.RIGHT, Severity.SHARP, 40.0, 50.0, 11.1),      // Sharp R
                CurveDef(1900.0, Direction.LEFT, Severity.MODERATE, 80.0, 60.0, null),     // Moderate L (suppressed)
                CurveDef(2100.0, Direction.RIGHT, Severity.HAIRPIN, 25.0, 40.0, 6.9),     // Hairpin R
                CurveDef(2250.0, Direction.LEFT, Severity.SHARP, 40.0, 50.0, 11.1),       // Sharp L
                CurveDef(2450.0, Direction.RIGHT, Severity.MODERATE, 80.0, 60.0, null),   // Moderate R (suppressed)
                CurveDef(2600.0, Direction.LEFT, Severity.HAIRPIN, 25.0, 40.0, 6.9),      // Hairpin L
                CurveDef(2800.0, Direction.RIGHT, Severity.SHARP, 40.0, 50.0, 11.1),      // Sharp R
                CurveDef(3000.0, Direction.LEFT, Severity.MODERATE, 80.0, 60.0, null)      // Moderate L (suppressed)
            )

            val segments = curveDefs.mapIndexed { i, def ->
                RouteSegment.Curve(
                    testCurve(
                        direction = def.dir,
                        severity = def.severity,
                        minRadius = def.radius,
                        arcLength = def.arcLength,
                        advisorySpeedMs = def.advisorySpeedMs,
                        distanceFromStart = def.distance,
                        startIndex = i * 20,
                        endIndex = i * 20 + 10
                    )
                )
            }

            manager.loadRoute(segments, emptyList())

            // With winding detection: overview + breakthrough curves + possible transitions
            // Moderate curves may be suppressed within the winding section
            val upcoming = manager.upcomingEvents()
            // At least the 8 breakthrough curves should be present
            assertThat(upcoming.size)
                .describedAs("Expected >= 8 events but got ${upcoming.size}. Events: ${upcoming.map { "${it.curveDistanceFromStart}: ${it.text}" }}")
                .isGreaterThanOrEqualTo(8)

            // Simulate driving at 50 km/h (13.9 m/s), GPS tick every ~10m
            val speedMs = 13.9
            val tickDistanceM = 10.0
            var position = 0.0
            var ttsRemainingDistance = 0.0

            while (position < 3200.0) {
                if (ttsRemainingDistance > 0) {
                    ttsRemainingDistance -= tickDistanceM
                    if (ttsRemainingDistance <= 0) {
                        val beforeCount = listener.allFired.size
                        ttsFinished()
                        val afterCount = listener.allFired.size
                        if (afterCount > beforeCount) {
                            val latest = listener.allFired.last()
                            val words = latest.text.split("\\s+".toRegex()).count { it.isNotBlank() }
                            ttsRemainingDistance = speedMs * (words / 2.5 + 0.3)
                        }
                    }
                }

                val beforeCount = listener.allFired.size
                driveTo(position, speedMs)
                val afterCount = listener.allFired.size

                if (afterCount > beforeCount && ttsRemainingDistance <= 0) {
                    val latest = listener.allFired.last()
                    val words = latest.text.split("\\s+".toRegex()).count { it.isNotBlank() }
                    ttsRemainingDistance = speedMs * (words / 2.5 + 0.3)
                }

                position += tickDistanceM
            }

            if (ttsRemainingDistance > 0) {
                ttsFinished()
            }

            // All breakthrough curves (HAIRPIN + SHARP) plus overview should be narrated
            val totalFired = listener.allFired.size
            assertThat(totalFired)
                .describedAs("Expected at least 9 events (1 overview + 8 breakthrough), got $totalFired")
                .isGreaterThanOrEqualTo(9)

            // Verify all HAIRPIN and SHARP curves were individually narrated
            val firedTexts = listener.allFired.map { it.text }
            val breakthroughDefs = curveDefs.filter { it.severity >= Severity.SHARP }
            for (def in breakthroughDefs) {
                val dirStr = if (def.dir == Direction.LEFT) "left" else "right"
                assertThat(firedTexts.any { it.lowercase().contains(dirStr) })
                    .describedAs("Expected a narration containing '$dirStr' for ${def.severity} at ${def.distance}m")
                    .isTrue()
            }
        }
    }

    // ========================================================================
    // Fire-time Speed-Adaptive Re-generation
    // ========================================================================

    @Nested
    @DisplayName("Fire-time Speed-Adaptive Re-generation")
    inner class FireTimeRegeneration {

        @Test
        fun `narration text is re-generated at fire-time speed`() {
            // Load a route at default (no speed info = null -> user tier -> STANDARD)
            // Then drive to trigger at high speed -> should get TERSE text
            val c = testCurve(
                severity = Severity.SHARP, minRadius = 40.0,
                advisorySpeedMs = 12.5, // ~45 km/h
                distanceFromStart = 500.0,
                direction = Direction.LEFT,
                arcLength = 50.0, startIndex = 0, endIndex = 10
            )

            // Use detailed verbosity (=3 -> DESCRIPTIVE)
            val detailedConfig = config.copy(verbosity = 3)
            manager = NarrationManager(config = detailedConfig)
            manager.setListener(listener)
            manager.loadRoute(routeOf(c), emptyList())

            // Drive at high speed (30 m/s = 108 km/h) -> speed-adaptive downgrades to TERSE
            driveTo(400.0, 30.0)
            assertThat(listener.narrations).hasSize(1)
            // At TERSE, sharp left should be "Sharp left, 45" (not the full DESCRIPTIVE text)
            // With universal Caution off (no tightening), we just get the terse text.
            val text = listener.narrations[0].text
            // The text should NOT contain "ahead" (that's STANDARD/DESCRIPTIVE)
            assertThat(text).doesNotContain("ahead")
        }

        @Test
        fun `slow speed gets descriptive text when config allows`() {
            val c = testCurve(
                severity = Severity.MODERATE, minRadius = 100.0,
                distanceFromStart = 300.0,
                direction = Direction.LEFT,
                arcLength = 50.0, startIndex = 0, endIndex = 10
            )

            val detailedConfig = config.copy(verbosity = 3)
            manager = NarrationManager(config = detailedConfig)
            manager.setListener(listener)
            manager.loadRoute(routeOf(c), emptyList())

            // Drive at slow speed (8 m/s = ~29 km/h) -> DESCRIPTIVE
            driveTo(200.0, 8.0)
            assertThat(listener.narrations).hasSize(1)
            val text = listener.narrations[0].text
            // DESCRIPTIVE for moderate with no advisory: "Left curve ahead, moderate, steady speed"
            assertThat(text).contains("steady speed")
        }

        @Test
        fun `re-generated text is used for urgent alerts too`() {
            val c = testCurve(
                severity = Severity.HAIRPIN, minRadius = 25.0,
                advisorySpeedMs = 6.9, distanceFromStart = 300.0,
                arcLength = 30.0, startIndex = 0, endIndex = 10
            )

            val detailedConfig = config.copy(verbosity = 3)
            manager = NarrationManager(config = detailedConfig)
            manager.setListener(listener)
            manager.loadRoute(routeOf(c), emptyList())

            // Drive fast (27.8 m/s = 100 km/h) and close -> urgent, TERSE
            driveTo(260.0, 27.8)
            assertThat(listener.urgentAlerts).hasSize(1)
            val text = listener.urgentAlerts[0].text
            assertThat(text).startsWith("Brake,")
            // At TERSE, hairpin should be "Hairpin right, 25" (no "ahead")
            // So urgent text = "Brake, Hairpin right, 25"
            assertThat(text).doesNotContain("ahead")
        }
    }
}

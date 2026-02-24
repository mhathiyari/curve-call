package com.curvecall.narration

import com.curvecall.engine.types.Direction
import com.curvecall.engine.types.LatLon
import com.curvecall.engine.types.RouteSegment
import com.curvecall.engine.types.Severity
import com.curvecall.narration.types.NarrationConfig
import com.curvecall.narration.types.NarrationEvent
import com.curvecall.narration.types.TimingProfileConfig
import com.curvecall.narration.types.TriggerDecision
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Orchestrates narration delivery during an active driving session.
 *
 * The NarrationManager ties together the [TemplateEngine], [TimingCalculator],
 * and [NarrationQueue] to produce correctly timed narrations as the driver
 * progresses along a route.
 *
 * ## Adaptive Timing Model
 *
 * Instead of pre-computing fixed trigger distances, each GPS tick dynamically
 * evaluates pending events using the driver's **current speed** and **kinematic
 * braking model**. The timing works backwards from the action point:
 *
 * ```
 * |<-- TTS -->|<-- React -->|<-- Brake -->|
 * [trigger]    [speaking]    [processing]  [braking]  [curve entry]
 * ```
 *
 * - **TTS duration** is estimated from the narration text word count.
 * - **Reaction time** comes from the driver's [TimingProfile][com.curvecall.narration.types.TimingProfile].
 * - **Braking distance** uses the kinematic equation with mode-specific deceleration.
 * - An **urgent alert** fires when the driver is dangerously close to a braking point.
 * - When TTS finishes, the next ready event fires **immediately** (no cooldown gap).
 *
 * This class does not directly call TTS; instead, it exposes events via a
 * [NarrationListener] callback that the TTS layer (or tests) can implement.
 *
 * Usage:
 * ```
 * val manager = NarrationManager(templateEngine, timingCalculator, config)
 * manager.setListener(myTtsListener)
 * manager.loadRoute(routeSegments, routePoints)
 * // On each GPS update (after map-matching via engine's MapMatcher):
 * manager.onLocationUpdate(routeProgressMeters, speedMs)
 * ```
 */
class NarrationManager(
    private val templateEngine: TemplateEngine = TemplateEngine(),
    private val timingCalculator: TimingCalculator = TimingCalculator(),
    private var config: NarrationConfig = NarrationConfig(),
    private val windingDetector: WindingDetector = WindingDetector(),
    private val transitionDetector: TransitionDetector = TransitionDetector()
) {
    /**
     * Listener interface for narration events.
     * Implement this to connect to TTS or test infrastructure.
     */
    interface NarrationListener {
        /** Called when a narration should be spoken. */
        fun onNarration(event: NarrationEvent)

        /** Called when a higher-priority narration should interrupt the current one. */
        fun onInterrupt(event: NarrationEvent)

        /** Called when an urgent brake alert should fire — distinct audio treatment. */
        fun onUrgentAlert(event: NarrationEvent)

        /** Called when narration is paused (e.g., off-route). */
        fun onPaused(reason: String)

        /** Called when narration resumes after being paused. */
        fun onResumed()
    }

    private val lock = ReentrantLock()
    private val queue = NarrationQueue()

    private var listener: NarrationListener? = null
    private var routeSegments: List<RouteSegment> = emptyList()

    /** Current route progress in meters from start. */
    private var currentProgressMeters: Double = 0.0

    /** Current speed in m/s. */
    private var currentSpeedMs: Double = 0.0

    /** Whether the driver is currently off-route. */
    private var isOffRoute: Boolean = false

    /** Whether the session is paused by the user. */
    private var isPaused: Boolean = false

    /** Whether a route has been loaded and the manager is active. */
    private var isActive: Boolean = false

    /** Resolved timing profile config from the current NarrationConfig. */
    private var profileConfig: TimingProfileConfig =
        TimingProfileConfig.forProfile(config.timingProfile)

    /**
     * Set the listener for narration events.
     */
    fun setListener(listener: NarrationListener?) {
        lock.withLock {
            this.listener = listener
        }
    }

    /**
     * Update the narration configuration.
     * This re-generates all pending narration events with the new config.
     */
    fun updateConfig(newConfig: NarrationConfig) {
        lock.withLock {
            this.config = newConfig
            this.profileConfig = TimingProfileConfig.forProfile(newConfig.timingProfile)
            if (isActive) {
                regenerateEvents()
            }
        }
    }

    /**
     * Load a route for narration.
     *
     * This pre-generates all narration events from the analyzed route segments
     * and populates the queue. Trigger timing is evaluated dynamically at runtime.
     *
     * @param segments The analyzed route segments (curves and straights).
     * @param points The route polyline points for map matching.
     */
    fun loadRoute(segments: List<RouteSegment>, points: List<LatLon>) {
        lock.withLock {
            this.routeSegments = segments
            this.currentProgressMeters = 0.0
            this.isOffRoute = false
            this.isActive = true

            queue.clear()
            generateAndEnqueueEvents()
        }
    }

    /**
     * Process a location update with pre-computed route progress.
     *
     * This is the main tick function, called on every GPS position update.
     * For each pending event ahead of the driver, it evaluates the kinematic
     * trigger condition using [TimingCalculator.evaluate] and the current speed.
     *
     * Flow:
     * 1. Get all events whose curve is still ahead of the driver.
     * 2. Evaluate each with [TimingCalculator.evaluate].
     * 3. URGENT events fire immediately.
     * 4. Higher-priority FIRE events interrupt lower-priority in-progress narration.
     * 5. Normal FIRE events wait if something is playing, fire otherwise.
     *
     * @param routeProgressMeters Current distance along the route from start, in meters.
     * @param speedMs Current speed in meters per second.
     */
    fun onLocationUpdate(routeProgressMeters: Double, speedMs: Double) {
        lock.withLock {
            if (!isActive || isPaused) return

            this.currentSpeedMs = speedMs
            this.currentProgressMeters = routeProgressMeters

            evaluateAndFireNext()
        }
    }

    /**
     * Notify the manager that the current narration has finished playing.
     * This marks the event as delivered and immediately evaluates whether
     * the next event should fire (voice chaining with no cooldown gap).
     */
    fun onNarrationComplete() {
        lock.withLock {
            queue.markCurrentDelivered()

            // Immediately chain the next ready event using stored position/speed
            if (isActive && !isPaused) {
                evaluateAndFireNext()
            }
        }
    }

    /**
     * Pause narration delivery.
     */
    fun pause() {
        lock.withLock {
            isPaused = true
            listener?.onPaused("Paused")
        }
    }

    /**
     * Resume narration delivery.
     */
    fun resume() {
        lock.withLock {
            isPaused = false
            listener?.onResumed()
        }
    }

    /**
     * Stop the session and clear all state.
     */
    fun stop() {
        lock.withLock {
            isActive = false
            isPaused = false
            isOffRoute = false
            queue.clear()
            routeSegments = emptyList()
            currentProgressMeters = 0.0
            currentSpeedMs = 0.0
        }
    }

    /**
     * Get the current route progress in meters from start.
     */
    fun currentProgress(): Double {
        lock.withLock {
            return currentProgressMeters
        }
    }

    /**
     * Get whether the driver is currently off-route.
     */
    fun isOffRoute(): Boolean {
        lock.withLock {
            return isOffRoute
        }
    }

    /**
     * Get the current narration queue for display purposes.
     */
    fun upcomingEvents(): List<NarrationEvent> {
        lock.withLock {
            return queue.pendingEvents()
        }
    }

    // ========================================================================
    // Private: Evaluation + Firing
    // ========================================================================

    /**
     * Evaluate all pending events against the current position/speed and fire
     * the best ready event. Called from both [onLocationUpdate] (GPS tick) and
     * [onNarrationComplete] (TTS finished — immediate chaining).
     *
     * Uses a past buffer to include events the driver recently passed while a
     * previous narration was playing. Without this, tightly-spaced curves on
     * switchbacks would be silently dropped.
     *
     * Must be called while holding [lock].
     */
    private fun evaluateAndFireNext() {
        // Include events the driver passed while TTS was busy.
        // Buffer = distance traveled during one max-length TTS utterance.
        val pastBuffer = currentSpeedMs * MAX_TTS_LOOKBACK_SEC
        val candidates = queue.eventsAhead(currentProgressMeters, pastBuffer)
        if (candidates.isEmpty()) return

        val readyEvents = mutableListOf<NarrationEvent>()
        var urgentEvent: NarrationEvent? = null

        for (event in candidates) {
            val distanceToCurve = event.curveDistanceFromStart - currentProgressMeters

            if (distanceToCurve < 0) {
                // Driver already passed this curve's entry point while TTS was busy.
                // Fire it immediately — the driver is likely still in or near the curve.
                readyEvents.add(event)
                continue
            }

            val ttsDuration = timingCalculator.estimateTtsDuration(event.text)

            val decision = timingCalculator.evaluate(
                distanceToCurveEntry = distanceToCurve,
                currentSpeedMs = currentSpeedMs,
                advisorySpeedMs = event.advisorySpeedMs,
                ttsDurationSec = ttsDuration,
                profile = profileConfig,
                mode = config.mode
            )

            when (decision) {
                TriggerDecision.URGENT -> {
                    if (urgentEvent == null || event.priority > urgentEvent.priority) {
                        urgentEvent = event
                    }
                }
                TriggerDecision.FIRE -> readyEvents.add(event)
                TriggerDecision.WAIT -> { /* re-evaluate next tick */ }
            }
        }

        // Urgent alerts bypass everything — fire immediately
        if (urgentEvent != null) {
            val regenerated = regenerateTextAtCurrentSpeed(urgentEvent)
            val urgentNarration = regenerated.copy(
                text = "Brake, ${regenerated.text}",
                priority = NarrationEvent.PRIORITY_URGENT
            )
            queue.markCurrentDelivered()
            queue.markDelivered(urgentEvent)
            listener?.onUrgentAlert(urgentNarration)
            return
        }

        // Check for interrupts: if something is playing and a higher-priority ready event exists
        val interrupt = queue.checkForInterrupt(readyEvents)
        if (interrupt != null) {
            val regenerated = regenerateTextAtCurrentSpeed(interrupt)
            listener?.onInterrupt(regenerated)
            return
        }

        // Normal delivery: pick the highest-priority ready event
        if (readyEvents.isEmpty()) return
        if (queue.currentlyPlaying() != null) return // something is already playing

        val best = readyEvents.maxByOrNull { it.priority } ?: return
        val regenerated = regenerateTextAtCurrentSpeed(best)
        queue.markPlaying(best)
        listener?.onNarration(regenerated)
    }

    /**
     * Re-generate narration text for an event using the current speed for tier resolution.
     * This allows speed-adaptive verbosity: at high speed the text becomes terser,
     * ensuring the driver hears appropriately concise instructions.
     *
     * Called at fire-time (not at route-load time) so the text reflects the driver's
     * actual speed at the moment of delivery rather than a stale pre-generated value.
     *
     * @param event The narration event whose text should be re-generated.
     * @return A copy of the event with updated text, or the original event unchanged
     *   if it has no associated curve or if text generation returns null.
     */
    private fun regenerateTextAtCurrentSpeed(event: NarrationEvent): NarrationEvent {
        val curve = event.associatedCurve ?: return event
        // Skip re-generation for merged events (text produced from multiple curves).
        // Compare against what the single curve would generate at route-load time (no speed).
        val singleCurveText = templateEngine.generateNarration(curve, config) ?: return event
        if (event.text != singleCurveText) return event
        val newText = templateEngine.generateNarration(curve, config, currentSpeedMs) ?: return event
        return event.copy(text = newText)
    }

    // ========================================================================
    // Private: Event Generation
    // ========================================================================

    /**
     * Generate narration events from the loaded route segments and enqueue them.
     *
     * Events store the curve's position and advisory speed for runtime evaluation.
     * No trigger distance is pre-computed — that's done dynamically per tick.
     */
    private fun generateAndEnqueueEvents() {
        val events = mutableListOf<NarrationEvent>()

        for (segment in routeSegments) {
            when (segment) {
                is RouteSegment.Curve -> {
                    val curve = segment.data
                    val text = templateEngine.generateNarration(curve, config) ?: continue

                    events.add(
                        NarrationEvent(
                            text = text,
                            priority = templateEngine.priorityForCurve(curve),
                            curveDistanceFromStart = curve.distanceFromStart,
                            advisorySpeedMs = curve.advisorySpeedMs,
                            associatedCurve = curve
                        )
                    )
                }

                is RouteSegment.Straight -> {
                    val straight = segment.data
                    val text = templateEngine.generateStraightNarration(straight, config) ?: continue

                    events.add(
                        NarrationEvent(
                            text = text,
                            priority = NarrationEvent.PRIORITY_STRAIGHT,
                            curveDistanceFromStart = straight.distanceFromStart,
                            advisorySpeedMs = null,
                            associatedCurve = null
                        )
                    )
                }
            }
        }

        // Merge consecutive close curve events into combined narrations
        mergeCloseEvents(events)

        // Detect winding sections and insert overview events / suppress non-breakthrough curves
        val allCurves = routeSegments
            .filterIsInstance<RouteSegment.Curve>()
            .map { it.data }
        if (allCurves.size >= WindingDetector.MIN_CURVES_FOR_WINDING) {
            val estimatedSpeed = 13.9 // ~50 km/h default
            val windingSections = windingDetector.detectWindingSections(allCurves, estimatedSpeed)
            for (section in windingSections) {
                // Insert winding overview event at the section start
                val tier = templateEngine.resolveTier(config)
                val overviewText = templateEngine.generateWindingOverview(section, config, tier)
                events.add(
                    NarrationEvent(
                        text = overviewText,
                        priority = NarrationEvent.PRIORITY_WARNING,
                        curveDistanceFromStart = section.startDistance - 50.0, // announce 50m before
                        advisorySpeedMs = section.advisorySpeedMs,
                        associatedCurve = null
                    )
                )

                // Suppress non-breakthrough individual events within the section
                events.removeAll { event ->
                    val curve = event.associatedCurve ?: return@removeAll false
                    curve.distanceFromStart >= section.startDistance &&
                        curve.distanceFromStart <= section.endDistance &&
                        !windingDetector.shouldNarrateInWindingSection(curve, section)
                }
            }
        }

        // Detect transitions and insert transition events
        val transitions = transitionDetector.detectAll(allCurves)
        for (transition in transitions) {
            val tier = templateEngine.resolveTier(config)
            val text = templateEngine.generateTransitionNarration(transition, tier)
            events.add(
                NarrationEvent(
                    text = text,
                    priority = NarrationEvent.PRIORITY_MODERATE, // mid-priority info event
                    curveDistanceFromStart = transition.distanceFromStart - 100.0, // 100m ahead
                    advisorySpeedMs = null,
                    associatedCurve = null
                )
            )
        }

        // Re-sort by distance since we added winding/transition events
        events.sortBy { it.curveDistanceFromStart }

        queue.enqueueAll(events)
    }

    /**
     * Merge consecutive curve events that are close together into single combined
     * narrations. This prevents rapid-fire narrations when curves are tightly spaced.
     *
     * Example: "Hairpin right ahead, slow to 15" + "Sharp left ahead, slow to 25"
     * becomes: "Hairpin right ahead, slow to 15, then sharp left"
     *
     * Rules:
     * - Only merges standalone curve events (not compound-annotated or straights)
     * - Gap measured from end of one curve to start of the next
     * - Groups are built greedily: A+B+C if all within threshold
     * - Merged event uses the first curve's position, the max priority, and
     *   the most conservative (lowest) advisory speed
     */
    private fun mergeCloseEvents(events: MutableList<NarrationEvent>) {
        if (events.size < 2) return

        val merged = mutableListOf<NarrationEvent>()
        var i = 0

        while (i < events.size) {
            val current = events[i]

            // Only merge standalone curve events (skip straights and compounds)
            if (current.associatedCurve == null || current.associatedCurve.compoundType != null) {
                merged.add(current)
                i++
                continue
            }

            // Greedily collect consecutive close curve events
            val group = mutableListOf(current)
            var j = i + 1

            while (j < events.size) {
                val next = events[j]

                // Stop if next is not a standalone curve
                if (next.associatedCurve == null || next.associatedCurve.compoundType != null) break

                // Compute gap: end of last curve in group → start of next curve
                val prevCurve = group.last().associatedCurve!!
                val gap = next.curveDistanceFromStart -
                    (group.last().curveDistanceFromStart + prevCurve.arcLength)

                if (gap > MERGE_GAP_THRESHOLD) break

                group.add(next)
                j++
            }

            if (group.size == 1) {
                merged.add(current)
            } else {
                merged.add(createMergedEvent(group))
            }
            i = j
        }

        events.clear()
        events.addAll(merged)
    }

    /**
     * Create a single merged narration event from a group of close curve events.
     *
     * The first curve keeps its full narration text. Subsequent curves use
     * context-sensitive connectors:
     * - Gap <30m → "into" (immediate connection)
     * - Same severity → "then" (rhythmic continuation)
     * - Different severity → "followed by" (contrast signal)
     */
    private fun createMergedEvent(group: List<NarrationEvent>): NarrationEvent {
        val first = group.first()
        val parts = mutableListOf(first.text)

        for (k in 1 until group.size) {
            val prevCurve = group[k - 1].associatedCurve!!
            val curve = group[k].associatedCurve!!
            val dir = if (curve.direction == Direction.LEFT) "left" else "right"
            val brief = when (curve.severity) {
                Severity.HAIRPIN -> "hairpin $dir"
                Severity.SHARP -> "sharp $dir"
                Severity.FIRM -> "firm $dir"
                Severity.MODERATE -> "moderate $dir"
                Severity.GENTLE -> "gentle $dir"
            }

            // Context-sensitive connector word
            val gap = curve.distanceFromStart -
                (prevCurve.distanceFromStart + prevCurve.arcLength)
            val connector = when {
                gap < 30.0 -> "into"
                prevCurve.severity == curve.severity -> "then"
                else -> "followed by"
            }
            parts.add("$connector $brief")
        }

        return NarrationEvent(
            text = parts.joinToString(", "),
            priority = group.maxOf { it.priority },
            curveDistanceFromStart = first.curveDistanceFromStart,
            advisorySpeedMs = group.mapNotNull { it.advisorySpeedMs }.minOrNull(),
            associatedCurve = first.associatedCurve
        )
    }

    /**
     * Regenerate all events with the current config (used when config changes mid-session).
     */
    private fun regenerateEvents() {
        queue.clearPending()
        generateAndEnqueueEvents()
    }

    companion object {
        /**
         * Maximum gap in meters between the end of one curve and the start of the
         * next for them to be merged into a single combined narration.
         *
         * 80m merges truly adjacent curves (e.g., 80m at 60 km/h = 4.8s), while
         * avoiding over-merging at low speeds (80m at 40 km/h = 7.2s, reasonable).
         * Curves 80-200m apart are handled by immediate voice chaining — when curve A's
         * TTS finishes, curve B fires instantly with no cooldown gap.
         */
        const val MERGE_GAP_THRESHOLD = 80.0

        /**
         * Maximum TTS duration (seconds) used to compute the past lookback buffer.
         * Events whose entry point the driver passed within `speed * MAX_TTS_LOOKBACK_SEC`
         * meters are still eligible to fire. This prevents missing curves on switchbacks
         * when the previous narration was still playing as the driver passed the next
         * curve's entry point.
         */
        const val MAX_TTS_LOOKBACK_SEC = 5.0
    }
}

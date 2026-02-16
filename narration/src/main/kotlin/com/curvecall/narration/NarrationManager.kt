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
 * - A **minimum cooldown** (minGapSec) prevents cognitive overload from rapid prompts.
 * - An **urgent alert** fires when the driver is dangerously close to a braking point.
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
    private var config: NarrationConfig = NarrationConfig()
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

    /** Timestamp (ms) when the last narration finished playing. Used for cooldown. */
    private var lastNarrationEndTimeMs: Long = 0L

    /** Priority of the last delivered narration. Used for priority-aware cooldown. */
    private var lastNarrationPriority: Int = 0

    /** Resolved timing profile config from the current NarrationConfig. */
    private var profileConfig: TimingProfileConfig =
        TimingProfileConfig.forProfile(config.timingProfile)

    /**
     * Overridable time source for testing. Returns current time in milliseconds.
     */
    internal var timeSource: () -> Long = { System.currentTimeMillis() }

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
            this.lastNarrationEndTimeMs = 0L
            this.lastNarrationPriority = 0

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
     * 3. URGENT events fire immediately, bypassing cooldown.
     * 4. Higher-priority FIRE events interrupt lower-priority in-progress narration.
     * 5. Normal FIRE events respect cooldown and wait if something is playing.
     *
     * @param routeProgressMeters Current distance along the route from start, in meters.
     * @param speedMs Current speed in meters per second.
     */
    fun onLocationUpdate(routeProgressMeters: Double, speedMs: Double) {
        lock.withLock {
            if (!isActive || isPaused) return

            this.currentSpeedMs = speedMs
            this.currentProgressMeters = routeProgressMeters

            // Get all events whose curve is still ahead
            val ahead = queue.eventsAhead(routeProgressMeters)
            if (ahead.isEmpty()) return

            // Evaluate each pending event
            val readyEvents = mutableListOf<NarrationEvent>()
            var urgentEvent: NarrationEvent? = null

            for (event in ahead) {
                val distanceToCurve = event.curveDistanceFromStart - routeProgressMeters
                val ttsDuration = timingCalculator.estimateTtsDuration(event.text)

                val decision = timingCalculator.evaluate(
                    distanceToCurveEntry = distanceToCurve,
                    currentSpeedMs = speedMs,
                    advisorySpeedMs = event.advisorySpeedMs,
                    ttsDurationSec = ttsDuration,
                    profile = profileConfig,
                    mode = config.mode
                )

                when (decision) {
                    TriggerDecision.URGENT -> {
                        // Take the highest-priority urgent event
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
                val urgentNarration = urgentEvent.copy(
                    text = "Brake, ${urgentEvent.text}",
                    priority = NarrationEvent.PRIORITY_URGENT
                )
                // Close out any currently-playing narration so the TTS layer
                // can cleanly flush and speak the urgent alert without overlap.
                queue.markCurrentDelivered()
                queue.markDelivered(urgentEvent)
                lastNarrationEndTimeMs = 0L // reset cooldown so follow-ups aren't blocked
                lastNarrationPriority = NarrationEvent.PRIORITY_URGENT
                listener?.onUrgentAlert(urgentNarration)
                return
            }

            // Check for interrupts: if something is playing and a higher-priority ready event exists
            val interrupt = queue.checkForInterrupt(readyEvents)
            if (interrupt != null) {
                lastNarrationEndTimeMs = 0L
                lastNarrationPriority = interrupt.priority
                listener?.onInterrupt(interrupt)
                return
            }

            // Normal delivery: pick the highest-priority ready event, respecting cooldown
            if (readyEvents.isEmpty()) return
            if (queue.currentlyPlaying() != null) return // something is already playing

            val best = readyEvents.maxByOrNull { it.priority } ?: return

            // Priority-aware cooldown: high-severity curves use shorter cooldowns
            // to prevent missing consecutive hairpins on mountain switchbacks.
            val now = timeSource()
            val effectiveGapMs = (effectiveCooldown(
                lastPriority = lastNarrationPriority,
                nextPriority = best.priority,
                baseGapSec = profileConfig.minGapSec
            ) * 1000).toLong()
            if (lastNarrationEndTimeMs > 0 && (now - lastNarrationEndTimeMs) < effectiveGapMs) {
                return // too soon since last narration
            }
            queue.markPlaying(best)
            listener?.onNarration(best)
        }
    }

    /**
     * Notify the manager that the current narration has finished playing.
     * This marks the event as delivered, records the end time for cooldown,
     * and allows the next event to play.
     */
    fun onNarrationComplete() {
        lock.withLock {
            val current = queue.currentlyPlaying()
            lastNarrationEndTimeMs = timeSource()
            lastNarrationPriority = current?.priority ?: 0
            queue.markCurrentDelivered()
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
            lastNarrationEndTimeMs = 0L
            lastNarrationPriority = 0
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

        queue.enqueueAll(events)
    }

    /**
     * Merge consecutive curve events that are close together into single combined
     * narrations. This prevents the second curve from being missed due to cooldown
     * when curves are tightly spaced.
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
     * The first curve keeps its full narration text. Subsequent curves are
     * appended as brief descriptions: ", then [severity] [direction]".
     */
    private fun createMergedEvent(group: List<NarrationEvent>): NarrationEvent {
        val first = group.first()
        val parts = mutableListOf(first.text)

        for (k in 1 until group.size) {
            val curve = group[k].associatedCurve!!
            val dir = if (curve.direction == Direction.LEFT) "left" else "right"
            val brief = when (curve.severity) {
                Severity.HAIRPIN -> "hairpin $dir"
                Severity.SHARP -> "sharp $dir"
                Severity.FIRM -> "firm $dir"
                Severity.MODERATE -> "moderate $dir"
                Severity.GENTLE -> "gentle $dir"
            }
            parts.add("then $brief")
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

    /**
     * Compute the effective cooldown between narrations based on priority transition.
     *
     * Sharp/hairpin events always use a minimal 0.5s cooldown to prevent missing
     * consecutive tight curves on mountain switchbacks. Severity escalation
     * (e.g., gentle → sharp) uses half the base cooldown. Same-or-lower severity
     * transitions use the full base cooldown.
     *
     * @param lastPriority Priority of the narration that just finished.
     * @param nextPriority Priority of the narration about to fire.
     * @param baseGapSec Profile-configured minimum gap (e.g., 3.0s for NORMAL).
     * @return Effective cooldown in seconds.
     */
    internal fun effectiveCooldown(lastPriority: Int, nextPriority: Int, baseGapSec: Double): Double {
        // First narration ever — no cooldown
        if (lastPriority == 0) return 0.0
        // High-severity curves: minimal cooldown to avoid missing consecutive hairpins
        if (nextPriority >= NarrationEvent.PRIORITY_SHARP) return MIN_COOLDOWN_SEC
        // Severity escalation: reduced cooldown
        if (nextPriority > lastPriority) return (baseGapSec * 0.5).coerceAtLeast(MIN_COOLDOWN_SEC)
        // Same or lower severity: full cooldown
        return baseGapSec
    }

    companion object {
        /**
         * Maximum gap in meters between the end of one curve and the start of the
         * next for them to be merged into a single combined narration.
         *
         * Reduced from 150m to 80m: the priority-aware cooldown (Sprint 1) now
         * handles curves 80-150m apart dynamically at runtime. 80m still merges
         * truly adjacent curves (e.g., 80m at 60 km/h = 4.8s), while avoiding
         * over-merging at low speeds (80m at 40 km/h = 7.2s, reasonable).
         */
        const val MERGE_GAP_THRESHOLD = 80.0

        /** Minimum cooldown between narrations, even for high-severity curves. */
        const val MIN_COOLDOWN_SEC = 0.5
    }
}

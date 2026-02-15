package com.curvecall.narration

import com.curvecall.engine.types.LatLon
import com.curvecall.engine.types.RouteSegment
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
                queue.markDelivered(urgentEvent)
                lastNarrationEndTimeMs = 0L // reset cooldown so follow-ups aren't blocked
                listener?.onUrgentAlert(urgentNarration)
                return
            }

            // Check for interrupts: if something is playing and a higher-priority ready event exists
            val interrupt = queue.checkForInterrupt(readyEvents)
            if (interrupt != null) {
                lastNarrationEndTimeMs = 0L
                listener?.onInterrupt(interrupt)
                return
            }

            // Normal delivery: pick the highest-priority ready event, respecting cooldown
            if (readyEvents.isEmpty()) return
            if (queue.currentlyPlaying() != null) return // something is already playing

            // Cooldown enforcement
            val now = timeSource()
            val minGapMs = (profileConfig.minGapSec * 1000).toLong()
            if (lastNarrationEndTimeMs > 0 && (now - lastNarrationEndTimeMs) < minGapMs) {
                return // too soon since last narration
            }

            val best = readyEvents.maxByOrNull { it.priority } ?: return
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
            lastNarrationEndTimeMs = timeSource()
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

        queue.enqueueAll(events)
    }

    /**
     * Regenerate all events with the current config (used when config changes mid-session).
     */
    private fun regenerateEvents() {
        queue.clearPending()
        generateAndEnqueueEvents()
    }
}

package com.curvecall.narration

import com.curvecall.engine.types.CurveSegment
import com.curvecall.engine.types.LatLon
import com.curvecall.engine.types.RouteSegment
import com.curvecall.engine.types.StraightSegment
import com.curvecall.narration.types.NarrationConfig
import com.curvecall.narration.types.NarrationEvent
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Orchestrates narration delivery during an active driving session.
 *
 * The NarrationManager ties together the [TemplateEngine], [TimingCalculator],
 * and [NarrationQueue] to produce correctly timed narrations as the driver
 * progresses along a route.
 *
 * Responsibilities:
 * - Accept GPS position updates and current speed
 * - Determine route progress (distance from start)
 * - Generate narration events from analyzed route segments
 * - Trigger narrations when the driver reaches the announcement distance
 * - Handle off-route detection: pause narration and produce a warning
 * - Resume narration when back on route
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
            if (isActive) {
                regenerateEvents()
            }
        }
    }

    /**
     * Load a route for narration.
     *
     * This pre-generates all narration events from the analyzed route segments
     * and populates the queue.
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
     * The caller (SessionViewModel) is responsible for map-matching via the
     * engine's MapMatcher, and passes the resulting route progress here.
     * This avoids duplicate map-matching logic.
     *
     * @param routeProgressMeters Current distance along the route from start, in meters.
     *   Computed by the engine's MapMatcher.
     * @param speedMs Current speed in meters per second.
     */
    fun onLocationUpdate(routeProgressMeters: Double, speedMs: Double) {
        lock.withLock {
            if (!isActive || isPaused) return

            this.currentSpeedMs = speedMs

            // Update progress
            currentProgressMeters = routeProgressMeters

            // Check for interrupts (higher-priority event ready)
            val interrupt = queue.checkForInterrupt(currentProgressMeters)
            if (interrupt != null) {
                listener?.onInterrupt(interrupt)
                queue.markCurrentDelivered()
                return
            }

            // Check for next event
            val nextEvent = queue.nextEvent(currentProgressMeters)
            if (nextEvent != null) {
                listener?.onNarration(nextEvent)
            }
        }
    }

    /**
     * Notify the manager that the current narration has finished playing.
     * This marks the event as delivered and allows the next event to play.
     */
    fun onNarrationComplete() {
        lock.withLock {
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
     */
    private fun generateAndEnqueueEvents() {
        val events = mutableListOf<NarrationEvent>()

        for (segment in routeSegments) {
            when (segment) {
                is RouteSegment.Curve -> {
                    val curve = segment.data
                    val text = templateEngine.generateNarration(curve, config) ?: continue

                    val triggerDist = timingCalculator.triggerDistanceFromStart(
                        curveDistanceFromStart = curve.distanceFromStart,
                        currentSpeedMs = estimateApproachSpeed(curve),
                        config = config,
                        advisorySpeedMs = curve.advisorySpeedMs
                    )

                    events.add(
                        NarrationEvent(
                            text = text,
                            priority = templateEngine.priorityForCurve(curve),
                            triggerDistanceFromStart = triggerDist,
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
                            triggerDistanceFromStart = straight.distanceFromStart,
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

    /**
     * Estimate the approach speed for a curve, used for pre-computing trigger distances.
     * Uses current speed if driving, or a reasonable default if stationary.
     */
    private fun estimateApproachSpeed(curve: CurveSegment): Double {
        // If we have a current speed, use it. Otherwise use a reasonable default.
        return if (currentSpeedMs > 1.0) {
            currentSpeedMs
        } else {
            // Default approach speed: 80 km/h = ~22.2 m/s
            DEFAULT_APPROACH_SPEED_MS
        }
    }

    companion object {
        /** Default approach speed when stationary, ~80 km/h in m/s. */
        const val DEFAULT_APPROACH_SPEED_MS = 22.2
    }
}

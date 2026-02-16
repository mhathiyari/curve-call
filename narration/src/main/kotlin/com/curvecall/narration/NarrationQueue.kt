package com.curvecall.narration

import com.curvecall.narration.types.NarrationEvent
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thread-safe queue for narration events ordered by curve position.
 *
 * Events are ordered by [NarrationEvent.curveDistanceFromStart] (ascending),
 * with ties broken by priority (descending). The queue does **not** decide when
 * to fire events — that responsibility belongs to [NarrationManager] which calls
 * [TimingCalculator.evaluate] on each GPS tick.
 *
 * Key behaviors:
 * - Events ordered by curve position along the route, then by priority for ties.
 * - Higher-priority events can interrupt the currently playing narration.
 * - Events marked as delivered are never re-triggered.
 * - Thread-safe: all operations are synchronized.
 *
 * This class is pure Kotlin with no Android dependencies.
 */
class NarrationQueue {

    private val lock = ReentrantLock()

    /** Pending events ordered by curve distance from start, not yet delivered. */
    private val pendingEvents = mutableListOf<NarrationEvent>()

    /** The event currently being spoken (if any). */
    private var currentEvent: NarrationEvent? = null

    /** Set of delivered event identifiers to prevent re-triggering. */
    private val deliveredKeys = mutableSetOf<String>()

    /**
     * Add a narration event to the queue.
     *
     * The event is inserted in order of curve distance (ascending).
     * Events with the same curve distance are ordered by priority (descending).
     * If the event has already been delivered, it is silently ignored.
     *
     * @param event The narration event to enqueue.
     */
    fun enqueue(event: NarrationEvent) {
        lock.withLock {
            val key = eventKey(event)
            if (key in deliveredKeys) return
            if (event.delivered) {
                deliveredKeys.add(key)
                return
            }

            // Sorted by curveDistanceFromStart ascending, then priority descending
            val insertIndex = pendingEvents.indexOfFirst { existing ->
                event.curveDistanceFromStart < existing.curveDistanceFromStart ||
                    (event.curveDistanceFromStart == existing.curveDistanceFromStart &&
                        event.priority > existing.priority)
            }

            if (insertIndex == -1) {
                pendingEvents.add(event)
            } else {
                pendingEvents.add(insertIndex, event)
            }
        }
    }

    /**
     * Add multiple events to the queue.
     */
    fun enqueueAll(events: List<NarrationEvent>) {
        events.forEach { enqueue(it) }
    }

    /**
     * Get all pending events that are ahead of the current position.
     *
     * Returns events whose curve entry point is ahead of (or at) the given progress,
     * in queue order, excluding delivered events.
     *
     * @param currentProgressMeters Current distance along the route from start.
     * @return List of pending events ahead of current position.
     */
    fun eventsAhead(currentProgressMeters: Double): List<NarrationEvent> {
        lock.withLock {
            return pendingEvents.filter { event ->
                event.curveDistanceFromStart >= currentProgressMeters &&
                    eventKey(event) !in deliveredKeys
            }
        }
    }

    /**
     * Mark an event as "now playing" — sets it as the current event.
     *
     * @param event The event that is now being spoken.
     */
    fun markPlaying(event: NarrationEvent) {
        lock.withLock {
            pendingEvents.remove(event)
            currentEvent = event
        }
    }

    /**
     * Check if a higher-priority event in the pending queue should interrupt
     * the currently playing narration.
     *
     * This is called by the manager after it has determined (via [TimingCalculator.evaluate])
     * which pending events are ready to fire. Among those, if any has higher priority
     * than the current event, it should interrupt.
     *
     * @param readyEvents Events that the manager has determined should fire now.
     * @return The interrupting event if one exists with higher priority than current, null otherwise.
     */
    fun checkForInterrupt(readyEvents: List<NarrationEvent>): NarrationEvent? {
        lock.withLock {
            val current = currentEvent ?: return null

            val interrupter = readyEvents
                .filter { it.priority > current.priority && eventKey(it) !in deliveredKeys }
                .maxByOrNull { it.priority }

            if (interrupter != null) {
                pendingEvents.remove(interrupter)
                currentEvent = interrupter
            }
            return interrupter
        }
    }

    /**
     * Mark the current event as delivered. This prevents it from being re-triggered.
     */
    fun markCurrentDelivered() {
        lock.withLock {
            val current = currentEvent ?: return
            deliveredKeys.add(eventKey(current))
            currentEvent = null
        }
    }

    /**
     * Mark a specific event as delivered by its text and curve distance.
     */
    fun markDelivered(event: NarrationEvent) {
        lock.withLock {
            deliveredKeys.add(eventKey(event))
            pendingEvents.removeAll { eventKey(it) == eventKey(event) }
            if (currentEvent != null && eventKey(currentEvent!!) == eventKey(event)) {
                currentEvent = null
            }
        }
    }

    /**
     * Get the currently playing event.
     */
    fun currentlyPlaying(): NarrationEvent? {
        lock.withLock {
            return currentEvent
        }
    }

    /**
     * Check if an event has already been delivered.
     */
    fun isDelivered(event: NarrationEvent): Boolean {
        lock.withLock {
            return eventKey(event) in deliveredKeys
        }
    }

    /**
     * Peek at the next pending event without removing it.
     */
    fun peekNext(): NarrationEvent? {
        lock.withLock {
            return pendingEvents.firstOrNull { eventKey(it) !in deliveredKeys }
        }
    }

    /**
     * Get all pending (undelivered) events in order.
     */
    fun pendingEvents(): List<NarrationEvent> {
        lock.withLock {
            return pendingEvents.filter { eventKey(it) !in deliveredKeys }.toList()
        }
    }

    /**
     * Get the number of pending (undelivered) events.
     */
    fun pendingCount(): Int {
        lock.withLock {
            return pendingEvents.count { eventKey(it) !in deliveredKeys }
        }
    }

    /**
     * Clear all pending events and reset delivery tracking.
     */
    fun clear() {
        lock.withLock {
            pendingEvents.clear()
            currentEvent = null
            deliveredKeys.clear()
        }
    }

    /**
     * Clear pending events but keep the delivered set (for route re-evaluation
     * without re-triggering already-spoken narrations).
     */
    fun clearPending() {
        lock.withLock {
            pendingEvents.clear()
            currentEvent = null
        }
    }

    /**
     * Generate a stable key for an event using curve identity or position bucketing.
     *
     * For curve events: uses `startIndex` which is unique per curve and stable
     * across regeneration. The 10m bucket adds jitter tolerance — the same curve
     * with slight distance drift still matches.
     *
     * For non-curve events (straights, warnings): uses exact distance + text hash
     * so different straights at different positions are never confused.
     */
    private fun eventKey(event: NarrationEvent): String {
        val curve = event.associatedCurve
        return if (curve != null) {
            // Curve events: stable identity from startIndex, bucketed for jitter tolerance
            val bucket = (event.curveDistanceFromStart / 10.0).toInt()
            "c:$bucket:${curve.startIndex}"
        } else {
            // Non-curve events: use exact distance to avoid collisions between
            // different straights/warnings with the same text
            "s:${event.curveDistanceFromStart}:${event.text.hashCode()}"
        }
    }
}

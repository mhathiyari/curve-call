package com.curvecall.narration

import com.curvecall.narration.types.NarrationEvent
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thread-safe priority queue for narration events.
 *
 * Manages the ordering and delivery of narration events based on priority.
 * Higher severity curves interrupt lower severity in-progress narrations,
 * while same or lower severity events queue behind the current narration.
 *
 * Priority order (highest to lowest): hairpin > sharp > firm > moderate > gentle > straight
 *
 * Key behaviors:
 * - Events are ordered by trigger distance, then by priority (higher first) for ties.
 * - Higher-priority events can interrupt the currently playing narration.
 * - Events marked as delivered are never re-triggered.
 * - Thread-safe: all operations are synchronized.
 *
 * This class is pure Kotlin with no Android dependencies.
 */
class NarrationQueue {

    private val lock = ReentrantLock()

    /** Pending events ordered by trigger distance, not yet delivered. */
    private val pendingEvents = mutableListOf<NarrationEvent>()

    /** The event currently being spoken (if any). */
    private var currentEvent: NarrationEvent? = null

    /** Set of delivered event identifiers to prevent re-triggering. */
    private val deliveredKeys = mutableSetOf<String>()

    /**
     * Add a narration event to the queue.
     *
     * The event is inserted in order of trigger distance (ascending).
     * Events with the same trigger distance are ordered by priority (descending).
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

            // Find insertion point: sorted by triggerDistance ascending, then priority descending
            val insertIndex = pendingEvents.indexOfFirst { existing ->
                event.triggerDistanceFromStart < existing.triggerDistanceFromStart ||
                    (event.triggerDistanceFromStart == existing.triggerDistanceFromStart &&
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
     * Get the next event that should be spoken, given the current route progress.
     *
     * Returns the highest-priority event whose trigger distance has been reached
     * (i.e., trigger distance <= current progress along the route).
     *
     * The returned event is removed from the pending queue and marked as the current event.
     *
     * @param currentProgressMeters Current distance along the route from start, in meters.
     * @return The next event to speak, or null if no events are ready.
     */
    fun nextEvent(currentProgressMeters: Double): NarrationEvent? {
        lock.withLock {
            // Find all events whose trigger distance has been reached
            val readyEvents = pendingEvents.filter { event ->
                event.triggerDistanceFromStart <= currentProgressMeters &&
                    eventKey(event) !in deliveredKeys
            }

            if (readyEvents.isEmpty()) return null

            // Pick the highest priority among ready events
            val best = readyEvents.maxByOrNull { it.priority } ?: return null

            pendingEvents.remove(best)
            currentEvent = best
            return best
        }
    }

    /**
     * Check if a higher-priority event is ready and should interrupt the current narration.
     *
     * @param currentProgressMeters Current distance along the route from start.
     * @return The interrupting event if one exists with higher priority than current, null otherwise.
     */
    fun checkForInterrupt(currentProgressMeters: Double): NarrationEvent? {
        lock.withLock {
            val current = currentEvent ?: return null

            val readyEvents = pendingEvents.filter { event ->
                event.triggerDistanceFromStart <= currentProgressMeters &&
                    eventKey(event) !in deliveredKeys &&
                    event.priority > current.priority
            }

            if (readyEvents.isEmpty()) return null

            val best = readyEvents.maxByOrNull { it.priority } ?: return null
            pendingEvents.remove(best)
            currentEvent = best
            return best
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
     * Mark a specific event as delivered by its text and trigger distance.
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
     * Generate a unique key for an event based on its text and trigger distance.
     * This is used for deduplication and delivery tracking.
     */
    private fun eventKey(event: NarrationEvent): String {
        return "${event.triggerDistanceFromStart}:${event.text}"
    }
}

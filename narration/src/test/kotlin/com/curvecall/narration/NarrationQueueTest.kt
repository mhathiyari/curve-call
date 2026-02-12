package com.curvecall.narration

import com.curvecall.narration.types.NarrationEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tests for the NarrationQueue priority queue.
 *
 * Covers:
 * - Priority ordering (hairpin > sharp > firm > moderate > gentle > straight)
 * - Interrupt behavior (higher severity interrupts lower)
 * - Same/lower severity queues after current
 * - Delivery marking (no re-triggering)
 * - Thread safety
 */
class NarrationQueueTest {

    private lateinit var queue: NarrationQueue

    @BeforeEach
    fun setUp() {
        queue = NarrationQueue()
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun event(
        text: String = "test",
        priority: Int = NarrationEvent.PRIORITY_MODERATE,
        triggerDistance: Double = 100.0,
        delivered: Boolean = false
    ) = NarrationEvent(
        text = text,
        priority = priority,
        triggerDistanceFromStart = triggerDistance,
        associatedCurve = null,
        delivered = delivered
    )

    // ========================================================================
    // Basic enqueue and dequeue
    // ========================================================================

    @Nested
    @DisplayName("Basic Operations")
    inner class BasicOperations {

        @Test
        fun `empty queue returns null`() {
            assertThat(queue.nextEvent(0.0)).isNull()
        }

        @Test
        fun `enqueue and dequeue single event`() {
            queue.enqueue(event(text = "curve ahead", triggerDistance = 50.0))
            val next = queue.nextEvent(50.0)
            assertThat(next).isNotNull
            assertThat(next!!.text).isEqualTo("curve ahead")
        }

        @Test
        fun `event not ready before trigger distance`() {
            queue.enqueue(event(triggerDistance = 200.0))
            assertThat(queue.nextEvent(100.0)).isNull()
        }

        @Test
        fun `event ready at exact trigger distance`() {
            queue.enqueue(event(triggerDistance = 200.0))
            assertThat(queue.nextEvent(200.0)).isNotNull
        }

        @Test
        fun `event ready past trigger distance`() {
            queue.enqueue(event(triggerDistance = 200.0))
            assertThat(queue.nextEvent(250.0)).isNotNull
        }

        @Test
        fun `pending count tracks undelivered events`() {
            queue.enqueue(event(triggerDistance = 100.0))
            queue.enqueue(event(triggerDistance = 200.0))
            queue.enqueue(event(triggerDistance = 300.0))
            assertThat(queue.pendingCount()).isEqualTo(3)
        }

        @Test
        fun `dequeue reduces pending count`() {
            queue.enqueue(event(text = "a", triggerDistance = 100.0))
            queue.enqueue(event(text = "b", triggerDistance = 200.0))
            assertThat(queue.pendingCount()).isEqualTo(2)
            queue.nextEvent(100.0)
            assertThat(queue.pendingCount()).isEqualTo(1)
        }
    }

    // ========================================================================
    // Priority ordering
    // ========================================================================

    @Nested
    @DisplayName("Priority Ordering")
    inner class PriorityOrdering {

        @Test
        fun `higher priority served first among ready events`() {
            queue.enqueue(event(text = "gentle", priority = NarrationEvent.PRIORITY_GENTLE, triggerDistance = 100.0))
            queue.enqueue(event(text = "sharp", priority = NarrationEvent.PRIORITY_SHARP, triggerDistance = 100.0))
            queue.enqueue(event(text = "moderate", priority = NarrationEvent.PRIORITY_MODERATE, triggerDistance = 100.0))

            val first = queue.nextEvent(100.0)
            assertThat(first!!.text).isEqualTo("sharp")
        }

        @Test
        fun `hairpin beats all other priorities`() {
            queue.enqueue(event(text = "sharp", priority = NarrationEvent.PRIORITY_SHARP, triggerDistance = 100.0))
            queue.enqueue(event(text = "hairpin", priority = NarrationEvent.PRIORITY_HAIRPIN, triggerDistance = 100.0))
            queue.enqueue(event(text = "firm", priority = NarrationEvent.PRIORITY_FIRM, triggerDistance = 100.0))

            val first = queue.nextEvent(100.0)
            assertThat(first!!.text).isEqualTo("hairpin")
        }

        @Test
        fun `events at different trigger distances served in order when reached`() {
            queue.enqueue(event(text = "far", triggerDistance = 300.0))
            queue.enqueue(event(text = "near", triggerDistance = 100.0))
            queue.enqueue(event(text = "mid", triggerDistance = 200.0))

            val first = queue.nextEvent(100.0)
            assertThat(first!!.text).isEqualTo("near")

            queue.markCurrentDelivered()

            val second = queue.nextEvent(200.0)
            assertThat(second!!.text).isEqualTo("mid")
        }

        @Test
        fun `full priority chain ordering`() {
            val priorities = listOf(
                "straight" to NarrationEvent.PRIORITY_STRAIGHT,
                "gentle" to NarrationEvent.PRIORITY_GENTLE,
                "moderate" to NarrationEvent.PRIORITY_MODERATE,
                "firm" to NarrationEvent.PRIORITY_FIRM,
                "sharp" to NarrationEvent.PRIORITY_SHARP,
                "hairpin" to NarrationEvent.PRIORITY_HAIRPIN
            )

            // Enqueue all at the same trigger distance
            priorities.shuffled().forEach { (text, priority) ->
                queue.enqueue(event(text = text, priority = priority, triggerDistance = 100.0))
            }

            // Should come out in descending priority order
            val results = mutableListOf<String>()
            repeat(6) {
                val next = queue.nextEvent(100.0)
                if (next != null) {
                    results.add(next.text)
                    queue.markCurrentDelivered()
                }
            }

            assertThat(results).isEqualTo(listOf("hairpin", "sharp", "firm", "moderate", "gentle", "straight"))
        }
    }

    // ========================================================================
    // Interrupt behavior
    // ========================================================================

    @Nested
    @DisplayName("Interrupt Behavior")
    inner class InterruptBehavior {

        @Test
        fun `higher priority interrupts lower priority in-progress`() {
            // Start with a gentle narration
            queue.enqueue(event(text = "gentle", priority = NarrationEvent.PRIORITY_GENTLE, triggerDistance = 100.0))
            val gentle = queue.nextEvent(100.0)
            assertThat(gentle).isNotNull

            // Now a sharp event becomes ready
            queue.enqueue(event(text = "sharp", priority = NarrationEvent.PRIORITY_SHARP, triggerDistance = 110.0))

            val interrupt = queue.checkForInterrupt(110.0)
            assertThat(interrupt).isNotNull
            assertThat(interrupt!!.text).isEqualTo("sharp")
        }

        @Test
        fun `same priority does not interrupt`() {
            queue.enqueue(event(text = "moderate1", priority = NarrationEvent.PRIORITY_MODERATE, triggerDistance = 100.0))
            queue.nextEvent(100.0)

            queue.enqueue(event(text = "moderate2", priority = NarrationEvent.PRIORITY_MODERATE, triggerDistance = 110.0))

            val interrupt = queue.checkForInterrupt(110.0)
            assertThat(interrupt).isNull()
        }

        @Test
        fun `lower priority does not interrupt`() {
            queue.enqueue(event(text = "sharp", priority = NarrationEvent.PRIORITY_SHARP, triggerDistance = 100.0))
            queue.nextEvent(100.0)

            queue.enqueue(event(text = "gentle", priority = NarrationEvent.PRIORITY_GENTLE, triggerDistance = 110.0))

            val interrupt = queue.checkForInterrupt(110.0)
            assertThat(interrupt).isNull()
        }

        @Test
        fun `no interrupt when nothing is playing`() {
            val interrupt = queue.checkForInterrupt(100.0)
            assertThat(interrupt).isNull()
        }

        @Test
        fun `interrupt updates current event`() {
            queue.enqueue(event(text = "gentle", priority = NarrationEvent.PRIORITY_GENTLE, triggerDistance = 100.0))
            queue.nextEvent(100.0)

            queue.enqueue(event(text = "hairpin", priority = NarrationEvent.PRIORITY_HAIRPIN, triggerDistance = 110.0))
            queue.checkForInterrupt(110.0)

            assertThat(queue.currentlyPlaying()!!.text).isEqualTo("hairpin")
        }
    }

    // ========================================================================
    // Delivery marking
    // ========================================================================

    @Nested
    @DisplayName("Delivery Marking")
    inner class DeliveryMarking {

        @Test
        fun `delivered events are not re-triggered`() {
            queue.enqueue(event(text = "curve", triggerDistance = 100.0))
            val first = queue.nextEvent(100.0)
            assertThat(first).isNotNull
            queue.markCurrentDelivered()

            // Even though we pass the trigger distance again, should not get the event
            val again = queue.nextEvent(100.0)
            assertThat(again).isNull()
        }

        @Test
        fun `pre-delivered events are ignored on enqueue`() {
            queue.enqueue(event(text = "already done", triggerDistance = 100.0, delivered = true))
            assertThat(queue.nextEvent(100.0)).isNull()
            assertThat(queue.pendingCount()).isEqualTo(0)
        }

        @Test
        fun `isDelivered returns true after marking`() {
            val e = event(text = "test", triggerDistance = 100.0)
            queue.enqueue(e)
            queue.nextEvent(100.0)
            queue.markCurrentDelivered()
            assertThat(queue.isDelivered(e)).isTrue()
        }

        @Test
        fun `isDelivered returns false for undelivered events`() {
            val e = event(text = "test", triggerDistance = 100.0)
            queue.enqueue(e)
            assertThat(queue.isDelivered(e)).isFalse()
        }

        @Test
        fun `markDelivered by event removes it and prevents re-trigger`() {
            val e = event(text = "test", triggerDistance = 100.0)
            queue.enqueue(e)
            queue.markDelivered(e)
            assertThat(queue.nextEvent(100.0)).isNull()
            assertThat(queue.isDelivered(e)).isTrue()
        }

        @Test
        fun `duplicate event with same key is not enqueued after delivery`() {
            val e1 = event(text = "curve", triggerDistance = 100.0)
            queue.enqueue(e1)
            queue.nextEvent(100.0)
            queue.markCurrentDelivered()

            // Try to enqueue same event again
            val e2 = event(text = "curve", triggerDistance = 100.0)
            queue.enqueue(e2)
            assertThat(queue.pendingCount()).isEqualTo(0)
        }
    }

    // ========================================================================
    // Peek and pending
    // ========================================================================

    @Nested
    @DisplayName("Peek and Pending")
    inner class PeekAndPending {

        @Test
        fun `peek returns first pending event without removing`() {
            queue.enqueue(event(text = "first", triggerDistance = 100.0))
            queue.enqueue(event(text = "second", triggerDistance = 200.0))

            val peeked = queue.peekNext()
            assertThat(peeked!!.text).isEqualTo("first")
            assertThat(queue.pendingCount()).isEqualTo(2) // Still there
        }

        @Test
        fun `peek returns null on empty queue`() {
            assertThat(queue.peekNext()).isNull()
        }

        @Test
        fun `pendingEvents returns all undelivered in order`() {
            queue.enqueue(event(text = "a", triggerDistance = 100.0))
            queue.enqueue(event(text = "b", triggerDistance = 200.0))
            queue.enqueue(event(text = "c", triggerDistance = 300.0))

            val pending = queue.pendingEvents()
            assertThat(pending).hasSize(3)
            assertThat(pending.map { it.text }).containsExactly("a", "b", "c")
        }
    }

    // ========================================================================
    // Clear operations
    // ========================================================================

    @Nested
    @DisplayName("Clear Operations")
    inner class ClearOperations {

        @Test
        fun `clear removes everything`() {
            queue.enqueue(event(text = "a", triggerDistance = 100.0))
            queue.enqueue(event(text = "b", triggerDistance = 200.0))
            queue.nextEvent(100.0)
            queue.markCurrentDelivered()

            queue.clear()

            assertThat(queue.pendingCount()).isEqualTo(0)
            assertThat(queue.currentlyPlaying()).isNull()

            // After clear, previously delivered events can be re-enqueued
            queue.enqueue(event(text = "a", triggerDistance = 100.0))
            assertThat(queue.pendingCount()).isEqualTo(1)
        }

        @Test
        fun `clearPending keeps delivered set`() {
            val e = event(text = "delivered", triggerDistance = 100.0)
            queue.enqueue(e)
            queue.nextEvent(100.0)
            queue.markCurrentDelivered()

            queue.enqueue(event(text = "pending", triggerDistance = 200.0))

            queue.clearPending()

            assertThat(queue.pendingCount()).isEqualTo(0)
            assertThat(queue.isDelivered(e)).isTrue()

            // Can't re-add the delivered event
            queue.enqueue(event(text = "delivered", triggerDistance = 100.0))
            assertThat(queue.pendingCount()).isEqualTo(0)
        }
    }

    // ========================================================================
    // EnqueueAll
    // ========================================================================

    @Nested
    @DisplayName("Batch Enqueue")
    inner class BatchEnqueue {

        @Test
        fun `enqueueAll adds multiple events`() {
            val events = listOf(
                event(text = "a", triggerDistance = 100.0),
                event(text = "b", triggerDistance = 200.0),
                event(text = "c", triggerDistance = 300.0)
            )
            queue.enqueueAll(events)
            assertThat(queue.pendingCount()).isEqualTo(3)
        }

        @Test
        fun `enqueueAll maintains order`() {
            val events = listOf(
                event(text = "c", triggerDistance = 300.0),
                event(text = "a", triggerDistance = 100.0),
                event(text = "b", triggerDistance = 200.0)
            )
            queue.enqueueAll(events)
            val pending = queue.pendingEvents()
            assertThat(pending.map { it.text }).containsExactly("a", "b", "c")
        }
    }

    // ========================================================================
    // Thread safety
    // ========================================================================

    @Nested
    @DisplayName("Thread Safety")
    inner class ThreadSafety {

        @Test
        fun `concurrent enqueue does not lose events`() {
            val threadCount = 10
            val eventsPerThread = 100
            val executor = Executors.newFixedThreadPool(threadCount)
            val latch = CountDownLatch(threadCount)

            for (t in 0 until threadCount) {
                executor.submit {
                    try {
                        for (i in 0 until eventsPerThread) {
                            queue.enqueue(
                                event(
                                    text = "t${t}_e${i}",
                                    triggerDistance = (t * eventsPerThread + i).toDouble()
                                )
                            )
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }

            latch.await(10, TimeUnit.SECONDS)
            executor.shutdown()

            assertThat(queue.pendingCount()).isEqualTo(threadCount * eventsPerThread)
        }

        @Test
        fun `concurrent enqueue and dequeue is safe`() {
            val eventCount = 500
            val executor = Executors.newFixedThreadPool(4)
            val enqueueLatch = CountDownLatch(1)
            val dequeueLatch = CountDownLatch(1)

            // Enqueue thread
            executor.submit {
                try {
                    for (i in 0 until eventCount) {
                        queue.enqueue(
                            event(text = "e$i", triggerDistance = i.toDouble())
                        )
                    }
                } finally {
                    enqueueLatch.countDown()
                }
            }

            // Dequeue thread
            executor.submit {
                try {
                    enqueueLatch.await(5, TimeUnit.SECONDS)
                    var progress = 0.0
                    while (progress < eventCount) {
                        val next = queue.nextEvent(progress)
                        if (next != null) {
                            queue.markCurrentDelivered()
                        }
                        progress += 1.0
                    }
                } finally {
                    dequeueLatch.countDown()
                }
            }

            dequeueLatch.await(10, TimeUnit.SECONDS)
            executor.shutdown()

            // No exceptions should have been thrown
            // All events should be delivered or pending
        }
    }

    // ========================================================================
    // Realistic scenario
    // ========================================================================

    @Nested
    @DisplayName("Realistic Scenarios")
    inner class RealisticScenarios {

        @Test
        fun `driving through a series of curves`() {
            // Simulate a route with 3 curves at different distances
            queue.enqueue(event(text = "Left curve ahead, moderate", priority = NarrationEvent.PRIORITY_MODERATE, triggerDistance = 100.0))
            queue.enqueue(event(text = "Sharp right ahead, slow to 40", priority = NarrationEvent.PRIORITY_SHARP, triggerDistance = 300.0))
            queue.enqueue(event(text = "Hairpin left ahead, slow to 20", priority = NarrationEvent.PRIORITY_HAIRPIN, triggerDistance = 600.0))

            // Driver approaches first curve
            var next = queue.nextEvent(100.0)
            assertThat(next!!.text).isEqualTo("Left curve ahead, moderate")
            queue.markCurrentDelivered()

            // Driver approaches second curve
            next = queue.nextEvent(300.0)
            assertThat(next!!.text).isEqualTo("Sharp right ahead, slow to 40")
            queue.markCurrentDelivered()

            // Driver approaches third curve
            next = queue.nextEvent(600.0)
            assertThat(next!!.text).isEqualTo("Hairpin left ahead, slow to 20")
            queue.markCurrentDelivered()

            // All delivered, nothing more
            next = queue.nextEvent(1000.0)
            assertThat(next).isNull()
        }

        @Test
        fun `hairpin interrupts moderate narration in progress`() {
            queue.enqueue(event(text = "Left curve ahead, moderate", priority = NarrationEvent.PRIORITY_MODERATE, triggerDistance = 100.0))
            queue.enqueue(event(text = "Hairpin right ahead, slow to 20", priority = NarrationEvent.PRIORITY_HAIRPIN, triggerDistance = 110.0))

            // Start moderate narration
            val moderate = queue.nextEvent(100.0)
            assertThat(moderate!!.text).isEqualTo("Left curve ahead, moderate")

            // Hairpin becomes ready while moderate is still playing
            val interrupt = queue.checkForInterrupt(110.0)
            assertThat(interrupt).isNotNull
            assertThat(interrupt!!.text).isEqualTo("Hairpin right ahead, slow to 20")
        }

        @Test
        fun `GPS jitter does not re-trigger delivered narrations`() {
            queue.enqueue(event(text = "curve", triggerDistance = 100.0))

            // First pass
            queue.nextEvent(100.0)
            queue.markCurrentDelivered()

            // GPS jumps back then forward (jitter)
            assertThat(queue.nextEvent(95.0)).isNull()
            assertThat(queue.nextEvent(100.0)).isNull()
            assertThat(queue.nextEvent(105.0)).isNull()
        }
    }
}

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
 * Tests for the NarrationQueue.
 *
 * Covers:
 * - Ordering by curveDistanceFromStart
 * - Priority ordering for ties
 * - Interrupt behavior (higher severity interrupts lower)
 * - Delivery marking (no re-triggering)
 * - eventsAhead filtering
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
        curveDistance: Double = 100.0,
        advisorySpeedMs: Double? = null,
        delivered: Boolean = false
    ) = NarrationEvent(
        text = text,
        priority = priority,
        curveDistanceFromStart = curveDistance,
        advisorySpeedMs = advisorySpeedMs,
        associatedCurve = null,
        delivered = delivered
    )

    // ========================================================================
    // Basic enqueue and ordering
    // ========================================================================

    @Nested
    @DisplayName("Basic Operations")
    inner class BasicOperations {

        @Test
        fun `empty queue returns empty events ahead`() {
            assertThat(queue.eventsAhead(0.0)).isEmpty()
        }

        @Test
        fun `enqueue and retrieve single event`() {
            queue.enqueue(event(text = "curve ahead", curveDistance = 500.0))
            val ahead = queue.eventsAhead(0.0)
            assertThat(ahead).hasSize(1)
            assertThat(ahead[0].text).isEqualTo("curve ahead")
        }

        @Test
        fun `events behind current position are filtered out`() {
            queue.enqueue(event(text = "behind", curveDistance = 50.0))
            queue.enqueue(event(text = "ahead", curveDistance = 200.0))
            val ahead = queue.eventsAhead(100.0)
            assertThat(ahead).hasSize(1)
            assertThat(ahead[0].text).isEqualTo("ahead")
        }

        @Test
        fun `events at exact current position are included`() {
            queue.enqueue(event(curveDistance = 100.0))
            assertThat(queue.eventsAhead(100.0)).hasSize(1)
        }

        @Test
        fun `pending count tracks undelivered events`() {
            queue.enqueue(event(text = "a", curveDistance = 100.0))
            queue.enqueue(event(text = "b", curveDistance = 200.0))
            queue.enqueue(event(text = "c", curveDistance = 300.0))
            assertThat(queue.pendingCount()).isEqualTo(3)
        }
    }

    // ========================================================================
    // Priority ordering
    // ========================================================================

    @Nested
    @DisplayName("Priority Ordering")
    inner class PriorityOrdering {

        @Test
        fun `events at same distance ordered by priority descending`() {
            queue.enqueue(event(text = "gentle", priority = NarrationEvent.PRIORITY_GENTLE, curveDistance = 100.0))
            queue.enqueue(event(text = "sharp", priority = NarrationEvent.PRIORITY_SHARP, curveDistance = 100.0))
            queue.enqueue(event(text = "moderate", priority = NarrationEvent.PRIORITY_MODERATE, curveDistance = 100.0))

            val pending = queue.pendingEvents()
            assertThat(pending[0].text).isEqualTo("sharp")
        }

        @Test
        fun `events ordered by distance then priority`() {
            queue.enqueue(event(text = "far-high", priority = NarrationEvent.PRIORITY_SHARP, curveDistance = 300.0))
            queue.enqueue(event(text = "near-low", priority = NarrationEvent.PRIORITY_GENTLE, curveDistance = 100.0))
            queue.enqueue(event(text = "near-high", priority = NarrationEvent.PRIORITY_SHARP, curveDistance = 100.0))

            val pending = queue.pendingEvents()
            // Near events first, high priority before low at same distance
            assertThat(pending.map { it.text }).containsExactly("near-high", "near-low", "far-high")
        }

        @Test
        fun `full priority chain ordering at same distance`() {
            val priorities = listOf(
                "straight" to NarrationEvent.PRIORITY_STRAIGHT,
                "gentle" to NarrationEvent.PRIORITY_GENTLE,
                "moderate" to NarrationEvent.PRIORITY_MODERATE,
                "firm" to NarrationEvent.PRIORITY_FIRM,
                "sharp" to NarrationEvent.PRIORITY_SHARP,
                "hairpin" to NarrationEvent.PRIORITY_HAIRPIN
            )

            priorities.shuffled().forEach { (text, priority) ->
                queue.enqueue(event(text = text, priority = priority, curveDistance = 100.0))
            }

            val pending = queue.pendingEvents()
            assertThat(pending.map { it.text }).isEqualTo(
                listOf("hairpin", "sharp", "firm", "moderate", "gentle", "straight")
            )
        }
    }

    // ========================================================================
    // markPlaying and interrupt behavior
    // ========================================================================

    @Nested
    @DisplayName("Playing and Interrupt Behavior")
    inner class PlayingAndInterrupt {

        @Test
        fun `markPlaying removes event from pending`() {
            val e = event(text = "curve", curveDistance = 100.0)
            queue.enqueue(e)
            queue.markPlaying(e)
            assertThat(queue.pendingCount()).isEqualTo(0)
            assertThat(queue.currentlyPlaying()?.text).isEqualTo("curve")
        }

        @Test
        fun `higher priority interrupts lower priority in-progress`() {
            val gentle = event(text = "gentle", priority = NarrationEvent.PRIORITY_GENTLE, curveDistance = 100.0)
            val sharp = event(text = "sharp", priority = NarrationEvent.PRIORITY_SHARP, curveDistance = 200.0)

            queue.enqueue(gentle)
            queue.enqueue(sharp)
            queue.markPlaying(gentle)

            val interrupt = queue.checkForInterrupt(listOf(sharp))
            assertThat(interrupt).isNotNull
            assertThat(interrupt!!.text).isEqualTo("sharp")
            assertThat(queue.currentlyPlaying()?.text).isEqualTo("sharp")
        }

        @Test
        fun `same priority does not interrupt`() {
            val mod1 = event(text = "moderate1", priority = NarrationEvent.PRIORITY_MODERATE, curveDistance = 100.0)
            val mod2 = event(text = "moderate2", priority = NarrationEvent.PRIORITY_MODERATE, curveDistance = 200.0)

            queue.enqueue(mod1)
            queue.enqueue(mod2)
            queue.markPlaying(mod1)

            val interrupt = queue.checkForInterrupt(listOf(mod2))
            assertThat(interrupt).isNull()
        }

        @Test
        fun `lower priority does not interrupt`() {
            val sharp = event(text = "sharp", priority = NarrationEvent.PRIORITY_SHARP, curveDistance = 100.0)
            val gentle = event(text = "gentle", priority = NarrationEvent.PRIORITY_GENTLE, curveDistance = 200.0)

            queue.enqueue(sharp)
            queue.enqueue(gentle)
            queue.markPlaying(sharp)

            val interrupt = queue.checkForInterrupt(listOf(gentle))
            assertThat(interrupt).isNull()
        }

        @Test
        fun `no interrupt when nothing is playing`() {
            val sharp = event(text = "sharp", priority = NarrationEvent.PRIORITY_SHARP, curveDistance = 100.0)
            queue.enqueue(sharp)
            val interrupt = queue.checkForInterrupt(listOf(sharp))
            assertThat(interrupt).isNull()
        }
    }

    // ========================================================================
    // Delivery marking
    // ========================================================================

    @Nested
    @DisplayName("Delivery Marking")
    inner class DeliveryMarking {

        @Test
        fun `delivered events are not returned in eventsAhead`() {
            val e = event(text = "curve", curveDistance = 100.0)
            queue.enqueue(e)
            queue.markPlaying(e)
            queue.markCurrentDelivered()

            assertThat(queue.eventsAhead(0.0)).isEmpty()
        }

        @Test
        fun `pre-delivered events are ignored on enqueue`() {
            queue.enqueue(event(text = "already done", curveDistance = 100.0, delivered = true))
            assertThat(queue.pendingCount()).isEqualTo(0)
        }

        @Test
        fun `isDelivered returns true after marking`() {
            val e = event(text = "test", curveDistance = 100.0)
            queue.enqueue(e)
            queue.markPlaying(e)
            queue.markCurrentDelivered()
            assertThat(queue.isDelivered(e)).isTrue()
        }

        @Test
        fun `isDelivered returns false for undelivered events`() {
            val e = event(text = "test", curveDistance = 100.0)
            queue.enqueue(e)
            assertThat(queue.isDelivered(e)).isFalse()
        }

        @Test
        fun `markDelivered by event removes it and prevents re-trigger`() {
            val e = event(text = "test", curveDistance = 100.0)
            queue.enqueue(e)
            queue.markDelivered(e)
            assertThat(queue.eventsAhead(0.0)).isEmpty()
            assertThat(queue.isDelivered(e)).isTrue()
        }

        @Test
        fun `duplicate event with same key is not enqueued after delivery`() {
            val e1 = event(text = "curve", curveDistance = 100.0)
            queue.enqueue(e1)
            queue.markPlaying(e1)
            queue.markCurrentDelivered()

            val e2 = event(text = "curve", curveDistance = 100.0)
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
            queue.enqueue(event(text = "first", curveDistance = 100.0))
            queue.enqueue(event(text = "second", curveDistance = 200.0))

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
            queue.enqueue(event(text = "a", curveDistance = 100.0))
            queue.enqueue(event(text = "b", curveDistance = 200.0))
            queue.enqueue(event(text = "c", curveDistance = 300.0))

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
            queue.enqueue(event(text = "a", curveDistance = 100.0))
            queue.enqueue(event(text = "b", curveDistance = 200.0))
            val e = event(text = "a", curveDistance = 100.0)
            queue.enqueue(e)
            queue.markPlaying(e)
            queue.markCurrentDelivered()

            queue.clear()

            assertThat(queue.pendingCount()).isEqualTo(0)
            assertThat(queue.currentlyPlaying()).isNull()

            // After clear, previously delivered events can be re-enqueued
            queue.enqueue(event(text = "a", curveDistance = 100.0))
            assertThat(queue.pendingCount()).isEqualTo(1)
        }

        @Test
        fun `clearPending keeps delivered set`() {
            val e = event(text = "delivered", curveDistance = 100.0)
            queue.enqueue(e)
            queue.markPlaying(e)
            queue.markCurrentDelivered()

            queue.enqueue(event(text = "pending", curveDistance = 200.0))

            queue.clearPending()

            assertThat(queue.pendingCount()).isEqualTo(0)
            assertThat(queue.isDelivered(e)).isTrue()

            // Can't re-add the delivered event
            queue.enqueue(event(text = "delivered", curveDistance = 100.0))
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
                event(text = "a", curveDistance = 100.0),
                event(text = "b", curveDistance = 200.0),
                event(text = "c", curveDistance = 300.0)
            )
            queue.enqueueAll(events)
            assertThat(queue.pendingCount()).isEqualTo(3)
        }

        @Test
        fun `enqueueAll maintains order`() {
            val events = listOf(
                event(text = "c", curveDistance = 300.0),
                event(text = "a", curveDistance = 100.0),
                event(text = "b", curveDistance = 200.0)
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
                                    curveDistance = (t * eventsPerThread + i).toDouble()
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
    }

    // ========================================================================
    // Realistic scenario
    // ========================================================================

    @Nested
    @DisplayName("Realistic Scenarios")
    inner class RealisticScenarios {

        @Test
        fun `driving through a series of curves`() {
            val curve1 = event(text = "Left curve ahead, moderate", priority = NarrationEvent.PRIORITY_MODERATE, curveDistance = 500.0)
            val curve2 = event(text = "Sharp right ahead, slow to 40", priority = NarrationEvent.PRIORITY_SHARP, curveDistance = 1000.0)
            val curve3 = event(text = "Hairpin left ahead, slow to 20", priority = NarrationEvent.PRIORITY_HAIRPIN, curveDistance = 1500.0)

            queue.enqueue(curve1)
            queue.enqueue(curve2)
            queue.enqueue(curve3)

            // Driver approaching first curve — it's ahead
            var ahead = queue.eventsAhead(300.0)
            assertThat(ahead).hasSize(3)

            // Mark first as playing and delivered
            queue.markPlaying(curve1)
            queue.markCurrentDelivered()

            // Only 2 left
            ahead = queue.eventsAhead(600.0)
            assertThat(ahead).hasSize(2)
            assertThat(ahead[0].text).isEqualTo("Sharp right ahead, slow to 40")
        }

        @Test
        fun `GPS jitter does not re-trigger delivered narrations`() {
            val e = event(text = "curve", curveDistance = 500.0)
            queue.enqueue(e)

            // Play and deliver
            queue.markPlaying(e)
            queue.markCurrentDelivered()

            // GPS jumps back then forward (jitter)
            assertThat(queue.eventsAhead(450.0)).isEmpty()
            assertThat(queue.eventsAhead(500.0)).isEmpty()
            assertThat(queue.eventsAhead(550.0)).isEmpty()
        }
    }
}

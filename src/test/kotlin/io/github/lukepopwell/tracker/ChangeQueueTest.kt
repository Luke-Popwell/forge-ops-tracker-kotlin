package io.github.lukepopwell.tracker

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ChangeQueueTest {
    @AfterEach
    fun reset() {
        ForgeOpsTracker.resetForTesting()
    }

    @Test
    fun `drain delivers what is queued on the calling thread and waits for the worker`() {
        val deliveredOn = ConcurrentHashMap<Any, Thread>()
        val config = Configuration().apply { queueSize = 10 }
        // n=1 keeps the worker busy for the whole drain, so the drain itself has to deliver the rest.
        val queue = ChangeQueue(config, SlowClient(config, firstMillis = 500, restMillis = 0, deliveredOn))

        queue.push(mapOf("n" to 1))
        Thread.sleep(100) // the worker is now busy delivering n=1
        queue.push(mapOf("n" to 2))
        queue.push(mapOf("n" to 3))

        assertTrue(queue.drain(Duration.ofSeconds(5)))
        assertEquals(3, deliveredOn.size)
        assertNotEquals(Thread.currentThread(), deliveredOn[1])
        assertEquals(Thread.currentThread(), deliveredOn[2])
        assertEquals(Thread.currentThread(), deliveredOn[3])
    }

    @Test
    fun `drain gives up at the timeout rather than holding the exit open`() {
        val config = Configuration().apply { queueSize = 10 }
        val queue = ChangeQueue(config, SlowClient(config, firstMillis = 500, restMillis = 500, ConcurrentHashMap()))
        repeat(3) { queue.push(mapOf("n" to it)) }

        val started = System.nanoTime()
        assertFalse(queue.drain(Duration.ofMillis(200)))
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1))
    }

    @Test
    fun `drain waits for nothing when nothing was recorded`() {
        val config = Configuration()
        val queue = ChangeQueue(config, SlowClient(config, firstMillis = 500, restMillis = 500, ConcurrentHashMap()))

        val started = System.nanoTime()
        assertTrue(queue.drain())
        assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(100))
    }

    @Test
    fun `tracker flush sends queued changes too, within one budget shared with errors`() {
        ForgeOpsTracker.init(installUncaughtExceptionHandler = false) {
            it.dsn = "http://key@localhost:1/api/v1/events"
            it.environment = "production"
        }
        val config = ForgeOpsTracker.configuration()
        val deliveredOn = ConcurrentHashMap<Any, Thread>()
        val slow = SlowClient(config, firstMillis = 300, restMillis = 300, deliveredOn)
        setTrackerField("reporter", Reporter(config, deliveryQueue = DeliveryQueue(config, slow)))
        setTrackerField("changeQueue", ChangeQueue(config, slow))

        ForgeOpsTracker.captureException(RuntimeException("boom"))
        ForgeOpsTracker.recordChange("feature_flag", "checkout turned on")
        assertTrue(ForgeOpsTracker.flush(Duration.ofSeconds(5)))
        assertTrue(deliveredOn.containsKey("change"), "the change was not delivered by flush")

        // Three of each, 300ms apiece: the errors alone use up a 500ms flush, so the changes get
        // nothing left rather than a second 500ms of their own.
        repeat(3) {
            ForgeOpsTracker.captureException(RuntimeException("boom"))
            ForgeOpsTracker.recordChange("feature_flag", "checkout turned on")
        }
        val started = System.nanoTime()
        assertFalse(ForgeOpsTracker.flush(Duration.ofMillis(500)))
        assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(900))
    }

    private fun setTrackerField(name: String, value: Any) {
        ForgeOpsTracker::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(ForgeOpsTracker, value)
        }
    }

    private class SlowClient(
        configuration: Configuration,
        private val firstMillis: Long,
        private val restMillis: Long,
        private val deliveredOn: MutableMap<Any, Thread>,
    ) : Client(configuration) {
        override fun deliverChange(change: Map<String, Any?>): Boolean {
            Thread.sleep(if (change["n"] == 1) firstMillis else restMillis)
            // A real change (from recordChange) has no "n", so it is filed under "change".
            deliveredOn[change["n"] ?: "change"] = Thread.currentThread()
            return true
        }

        override fun deliver(payload: Map<String, Any?>): Boolean {
            Thread.sleep(restMillis)
            deliveredOn["error"] = Thread.currentThread()
            return true
        }
    }
}

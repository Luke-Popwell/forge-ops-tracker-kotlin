package io.github.lukepopwell.tracker

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DeliveryQueueTest {
    private var server: HttpServer? = null

    @AfterEach
    fun stopServer() {
        server?.stop(0)
    }

    @Test
    fun `push delivers in the background`() {
        val received = AtomicInteger(0)
        val latch = CountDownLatch(2)
        server = startTestHttpServer { exchange, _ ->
            received.incrementAndGet()
            latch.countDown()
            exchange.sendResponseHeaders(202, -1)
        }

        val config = Configuration()
        config.dsn = "http://key@localhost:${server!!.address.port}/api/v1/events"
        config.queueSize = 10
        val queue = DeliveryQueue(config)

        queue.push(mapOf("n" to 1))
        queue.push(mapOf("n" to 2))

        assertTrue(latch.await(2, TimeUnit.SECONDS), "server received ${received.get()} requests, want 2")
    }

    @Test
    fun `drops when full`() {
        // A handler that blocks until released, so the worker coroutine stays busy on the first
        // delivery long enough for the queue behind it to actually fill up.
        val release = CountDownLatch(1)
        server = startTestHttpServer { exchange, _ ->
            release.await(2, TimeUnit.SECONDS)
            exchange.sendResponseHeaders(202, -1)
        }

        val config = Configuration()
        config.dsn = "http://key@localhost:${server!!.address.port}/api/v1/events"
        config.queueSize = 1
        val queue = DeliveryQueue(config)

        assertTrue(queue.push(mapOf("n" to 1)), "first push should have succeeded")
        Thread.sleep(200) // let the worker pick it up and start blocking

        assertTrue(queue.push(mapOf("n" to 2)), "second push should have filled the size-1 queue")
        assertFalse(queue.push(mapOf("n" to 3)), "third push should have been dropped: queue was full")

        release.countDown()
    }

    @Test
    fun `drain delivers what is queued on the calling thread and waits for the worker`() {
        val deliveredOn = ConcurrentHashMap<Any, Thread>()
        val config = Configuration().apply { queueSize = 10 }
        // n=1 keeps the worker busy for the whole drain, so the drain itself has to deliver the rest.
        val queue = DeliveryQueue(config, SlowClient(config, firstMillis = 500, restMillis = 0, deliveredOn))

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
        val queue = DeliveryQueue(config, SlowClient(config, firstMillis = 500, restMillis = 500, ConcurrentHashMap()))
        repeat(3) { queue.push(mapOf("n" to it)) }

        val started = System.nanoTime()
        assertFalse(queue.drain(Duration.ofMillis(200)))
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1))
    }

    @Test
    fun `drain waits for nothing when nothing was reported`() {
        val config = Configuration()
        val queue = DeliveryQueue(config, SlowClient(config, firstMillis = 500, restMillis = 500, ConcurrentHashMap()))

        val started = System.nanoTime()
        assertTrue(queue.drain())
        assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(100))
    }

    @Test
    fun `a dropped payload is not waited for`() {
        val release = CountDownLatch(1)
        val config = Configuration().apply { queueSize = 1 }
        val queue = DeliveryQueue(config, object : Client(config) {
            override fun deliver(payload: Map<String, Any?>): Boolean {
                release.await(2, TimeUnit.SECONDS)
                return true
            }
        })
        queue.push(mapOf("n" to 1))
        Thread.sleep(200)
        queue.push(mapOf("n" to 2))
        assertFalse(queue.push(mapOf("n" to 3)))

        release.countDown()
        assertTrue(queue.drain(Duration.ofSeconds(2)))
    }

    private class SlowClient(
        configuration: Configuration,
        private val firstMillis: Long,
        private val restMillis: Long,
        private val deliveredOn: MutableMap<Any, Thread>,
    ) : Client(configuration) {
        override fun deliver(payload: Map<String, Any?>): Boolean {
            Thread.sleep(if (payload["n"] == 1) firstMillis else restMillis)
            deliveredOn[payload["n"]!!] = Thread.currentThread()
            return true
        }
    }
}

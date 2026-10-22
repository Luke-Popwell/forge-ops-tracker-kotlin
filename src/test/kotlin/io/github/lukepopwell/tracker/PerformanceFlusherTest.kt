package io.github.lukepopwell.tracker

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class PerformanceFlusherTest {
    private var server: HttpServer? = null
    private var flusher: PerformanceFlusher? = null

    @AfterEach
    fun cleanup() {
        flusher?.shutdown()
        server?.stop(0)
    }

    private fun flusherAgainst(port: Int, configure: Configuration.() -> Unit = {}): PerformanceFlusher {
        val config = Configuration().apply {
            dsn = "http://key@localhost:$port/api/v1/events"
            environment = "production"
            performanceFlushInterval = Duration.ofHours(1) // tests flush by hand
            configure()
        }
        return PerformanceFlusher(config, Client(config)).also { flusher = it }
    }

    private fun deadEndFlusher() = flusherAgainst(1)

    private fun startSamplesServer(status: Int = 202, bodies: LinkedBlockingQueue<String> = LinkedBlockingQueue(), gate: CountDownLatch? = null, received: CountDownLatch? = null): HttpServer =
        startTestHttpServer("/api/v1/performance_samples") { exchange, body ->
            bodies.add(body)
            received?.countDown()
            gate?.await(5, TimeUnit.SECONDS)
            exchange.sendResponseHeaders(status, -1)
        }.also { server = it }

    @Test
    fun `strict privacy takes the ids out of a raw path transaction name and keeps a route`() {
        val f = flusherAgainst(1) { strictPrivacy = true }

        f.record("GET /patients/42", 10.0)
        f.record("GET /patients/43", 30.0)
        f.record("GET /users/{id}", 5.0)

        assertEquals(setOf("GET /patients/:path", "GET /users/{id}"), f.tallies().keys)
        assertEquals(2L, f.tallies()["GET /patients/:path"]?.first)
    }

    @Test
    fun `buckets by transaction name with count sum and max`() {
        val f = deadEndFlusher()

        f.record("GET /users/:id", 10.0)
        f.record("GET /users/:id", 30.0)
        f.record("POST /orders", 5.0)

        assertEquals(Triple(2L, 40.0, 30.0), f.tallies()["GET /users/:id"])
        assertEquals(Triple(1L, 5.0, 5.0), f.tallies()["POST /orders"])
    }

    @Test
    fun `flush delivers one batch to performance_samples and empties the buckets`() {
        val bodies = LinkedBlockingQueue<String>()
        val s = startSamplesServer(bodies = bodies)
        val f = flusherAgainst(s.address.port) { release = "a1b2c3d" }
        f.record("GET /users/:id", 10.0)
        f.record("GET /users/:id", 30.0)

        f.flush()

        val body = bodies.poll(2, TimeUnit.SECONDS)!!
        assertTrue(body.startsWith("{\"samples\":[{"), body)
        assertTrue(body.contains("\"transaction_name\":\"GET /users/:id\""), body)
        assertTrue(body.contains("\"request_count\":2"), body)
        assertTrue(body.contains("\"duration_sum_ms\":40.0"), body)
        assertTrue(body.contains("\"max_duration_ms\":30.0"), body)
        assertTrue(body.contains("\"environment\":\"production\""), body)
        assertTrue(body.contains("\"release\":\"a1b2c3d\""), body)
        assertTrue(f.tallies().isEmpty())
    }

    @Test
    fun `flush does nothing when there is nothing to send`() {
        deadEndFlusher().flush() // must not attempt a delivery (nothing is listening) or throw
    }

    @Test
    fun `a failed delivery keeps every bucket so the next flush carries more`() {
        val bodies = LinkedBlockingQueue<String>()
        var calls = 0
        server = startTestHttpServer("/api/v1/performance_samples") { exchange, body ->
            bodies.add(body)
            exchange.sendResponseHeaders(if (++calls == 1) 500 else 202, -1)
        }
        val f = flusherAgainst(server!!.address.port)
        f.record("GET /x", 10.0)

        f.flush()
        assertEquals(Triple(1L, 10.0, 10.0), f.tallies()["GET /x"])

        f.record("GET /x", 20.0)
        f.flush()

        bodies.poll(2, TimeUnit.SECONDS)
        assertTrue(bodies.poll(2, TimeUnit.SECONDS)!!.contains("\"request_count\":2"))
        assertTrue(f.tallies().isEmpty())
    }

    @Test
    fun `a batch the server rejects is dropped and never sent again`() {
        val bodies = LinkedBlockingQueue<String>()
        val log = CopyOnWriteArrayList<String>()
        var status = 403
        server = startTestHttpServer("/api/v1/performance_samples") { exchange, body ->
            bodies.add(body)
            exchange.sendResponseHeaders(status, -1)
        }
        val f = flusherAgainst(server!!.address.port) { logger = { log.add(it) } }
        f.record("GET /x", 10.0)

        f.flush() // rejected: the bucket is let go
        assertTrue(f.tallies().isEmpty())
        f.flush() // nothing left to send
        assertEquals(1, bodies.size)
        assertEquals(1, log.count { it.contains("dropped performance samples for 1 transactions: the server rejected it") })

        status = 202
        f.record("GET /y", 20.0)
        f.flush()

        assertEquals(2, bodies.size)
        bodies.poll()
        val second = bodies.poll()!!
        assertTrue(second.contains("\"transaction_name\":\"GET /y\""), second)
        assertFalse(second.contains("GET /x"), second)
    }

    @Test
    fun `the fifth failed flush in a row drops the batch`() {
        val log = CopyOnWriteArrayList<String>()
        val f = flusherAgainst(ServerSocket(0).use { it.localPort }) { logger = { log.add(it) } }
        f.record("GET /x", 10.0)

        repeat(4) { f.flush() }
        assertEquals(Triple(1L, 10.0, 10.0), f.tallies()["GET /x"]) // four failures in: still kept

        f.flush() // the fifth: dropped

        assertTrue(f.tallies().isEmpty())
        assertEquals(1, log.count { it.contains("delivery failed 5 times in a row") })
    }

    @Test
    fun `a delivered flush starts the count of failed flushes over`() {
        var calls = 0
        server = startTestHttpServer("/api/v1/performance_samples") { exchange, _ ->
            exchange.sendResponseHeaders(if (++calls == 5) 202 else 503, -1) // four failures, one success, then failures again
        }
        val f = flusherAgainst(server!!.address.port)
        f.record("GET /x", 10.0)
        repeat(5) { f.flush() }
        assertTrue(f.tallies().isEmpty())

        f.record("GET /x", 10.0)
        repeat(4) { f.flush() }
        assertEquals(Triple(1L, 10.0, 10.0), f.tallies()["GET /x"])

        f.flush() // only the fifth failure since the success drops it
        assertTrue(f.tallies().isEmpty())
        assertEquals(10, calls)
    }

    @Test
    fun `a record that lands during delivery is never lost`() {
        // Deterministic reproduction of the race flush's own comment describes: the server holds
        // its response until this test has recorded again, guaranteeing record() runs strictly
        // between the snapshot and delivery succeeding.
        val gate = CountDownLatch(1)
        val received = CountDownLatch(1)
        val s = startSamplesServer(gate = gate, received = received)
        val f = flusherAgainst(s.address.port)
        f.record("GET /x", 10.0)

        val flushing = Thread { f.flush() }
        flushing.start()
        assertTrue(received.await(2, TimeUnit.SECONDS))

        f.record("GET /x", 25.0) // same transaction, mid-delivery
        f.record("GET /new", 7.0) // a brand-new one, mid-delivery
        gate.countDown()
        flushing.join(5000)

        assertEquals(Triple(1L, 25.0, 25.0), f.tallies()["GET /x"])
        assertEquals(Triple(1L, 7.0, 7.0), f.tallies()["GET /new"])
    }

    @Test
    fun `the scheduled worker flushes on its own interval`() {
        val bodies = LinkedBlockingQueue<String>()
        val s = startSamplesServer(bodies = bodies)
        val f = flusherAgainst(s.address.port) { performanceFlushInterval = Duration.ofMillis(50) }

        f.record("GET /x", 10.0)

        assertTrue(bodies.poll(3, TimeUnit.SECONDS)!!.contains("\"transaction_name\":\"GET /x\""))
    }

    @Test
    fun `performanceSamplesUri swaps the trailing events segment`() {
        val config = Configuration().apply { dsn = "https://key@tracker.example.com/api/v1/events" }

        assertEquals("https://tracker.example.com/api/v1/performance_samples", config.performanceSamplesUri.toString())
        assertNull(Configuration().apply { dsn = null }.performanceSamplesUri)
    }

    @Test
    fun `delivers a latency histogram per bucket alongside count sum and max`() {
        val bodies = LinkedBlockingQueue<String>()
        val s = startSamplesServer(bodies = bodies)
        val f = flusherAgainst(s.address.port)
        for (duration in listOf(10.0, 40.0, 120.0, 700.0, 12_000.0)) f.record("GET /posts", duration)

        f.flush()

        assertTrue(bodies.poll(3, TimeUnit.SECONDS)!!.contains("\"histogram\":{\"50\":2,\"250\":1,\"1000\":1,\"inf\":1}"))
    }

    @Test
    fun `keeps histogram counts for the next flush when delivery fails`() {
        val bodies = LinkedBlockingQueue<String>()
        var calls = 0
        server = startTestHttpServer("/api/v1/performance_samples") { exchange, body ->
            bodies.add(body)
            exchange.sendResponseHeaders(if (++calls == 1) 500 else 202, -1)
        }
        val f = flusherAgainst(server!!.address.port)
        f.record("GET /posts", 10.0)
        f.flush() // the first delivery fails: the histogram must survive it

        f.record("GET /posts", 300.0)
        f.flush()

        bodies.poll(2, TimeUnit.SECONDS)
        assertTrue(bodies.poll(2, TimeUnit.SECONDS)!!.contains("\"histogram\":{\"50\":1,\"500\":1}"))
    }

    @Test
    fun `a histogram count recorded during delivery is sent on the next flush`() {
        val gate = CountDownLatch(1)
        val received = CountDownLatch(1)
        val bodies = LinkedBlockingQueue<String>()
        val s = startSamplesServer(bodies = bodies, gate = gate, received = received)
        val f = flusherAgainst(s.address.port)
        f.record("GET /posts", 10.0)

        val flushing = Thread { f.flush() }
        flushing.start()
        assertTrue(received.await(2, TimeUnit.SECONDS))
        f.record("GET /posts", 300.0) // same transaction, mid-delivery
        f.record("GET /new", 5.0) // a brand-new one, mid-delivery
        gate.countDown()
        flushing.join(5000)
        assertTrue(bodies.poll(3, TimeUnit.SECONDS)!!.contains("\"histogram\":{\"50\":1}"))

        f.flush()
        val second = bodies.poll(3, TimeUnit.SECONDS)!!
        assertTrue(second.contains("\"histogram\":{\"500\":1}"))
        assertTrue(second.contains("\"transaction_name\":\"GET /new\""))
    }
}

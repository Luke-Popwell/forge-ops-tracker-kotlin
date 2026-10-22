package io.github.lukepopwell.tracker

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MetricBufferTest {
    private val buffers = ArrayList<MetricBuffer>()
    private val servers = ArrayList<com.sun.net.httpserver.HttpServer>()

    @AfterEach
    fun cleanup() {
        buffers.forEach { it.shutdown() }
        servers.forEach { it.stop(0) }
        ForgeOpsTracker.resetForTesting()
    }

    private fun configuration() = Configuration().apply {
        dsn = "https://key@tracker.example.com/api/v1/events"
        environment = "production"
        release = "1.2.3"
    }

    private fun buffer(deliver: (List<Map<String, Any?>>) -> Boolean): MetricBuffer =
        MetricBuffer(configuration(), deliver) { Duration.ofHours(1) }.also { buffers.add(it) }

    private fun entry(name: String, value: Any?) = mapOf("metric_name" to name, "value" to value)

    @Test
    fun `delivers every entry as one batch stamped with recorded_at`() {
        val delivered = CopyOnWriteArrayList<List<Map<String, Any?>>>()
        val buffer = buffer { delivered.add(it.toList()); true }

        buffer.record(entry("signup", 1.0))
        buffer.record(entry("payment", 49.5))
        buffer.flush()

        assertEquals(1, delivered.size)
        assertEquals(2, delivered[0].size)
        assertTrue((delivered[0][0]["recorded_at"] as String).matches(Regex("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ")))
    }

    @Test
    fun `keeps a negative value since a refund is a real metric`() {
        val delivered = CopyOnWriteArrayList<Map<String, Any?>>()
        val buffer = buffer { delivered.addAll(it); true }
        buffer.record(entry("refund", -12.0))
        buffer.flush()
        assertEquals(-12.0, delivered[0]["value"])
    }

    @Test
    fun `drops NaN infinite and non-numeric values so one bad entry cannot poison a batch`() {
        val buffer = buffer { true }
        assertFalse(buffer.record(entry("a", Double.NaN)))
        assertFalse(buffer.record(entry("b", Double.POSITIVE_INFINITY)))
        assertFalse(buffer.record(entry("c", "12")))
        assertTrue(buffer.record(entry("d", 3.0)))
    }

    @Test
    fun `a failed delivery keeps every entry for the next flush`() {
        var calls = 0
        val delivered = CopyOnWriteArrayList<List<String>>()
        val buffer = buffer { entries ->
            delivered.add(entries.map { it["metric_name"] as String })
            ++calls > 1
        }

        buffer.record(entry("a", 1.0))
        buffer.flush()
        buffer.record(entry("b", 2.0))
        buffer.flush()
        buffer.flush() // nothing left: no third delivery

        assertEquals(listOf(listOf("a"), listOf("a", "b")), delivered.toList())
    }

    @Test
    fun `a rejected batch is dropped and never sent again`() {
        val log = CopyOnWriteArrayList<String>()
        val sent = CopyOnWriteArrayList<List<String>>()
        val config = configuration().apply { logger = { log.add(it) } }
        val deliver: (List<Map<String, Any?>>) -> DeliveryResult = { entries ->
            sent.add(entries.map { it["metric_name"] as String })
            if (sent.size == 1) DeliveryResult.REJECTED else DeliveryResult.DELIVERED
        }
        val buffer = MetricBuffer(config, { Duration.ofHours(1) }, deliver).also { buffers.add(it) }

        buffer.record(entry("a", 1.0))
        buffer.flush() // rejected: "a" is let go
        buffer.flush() // nothing left to send
        assertEquals(0, buffer.size())

        buffer.record(entry("b", 2.0))
        buffer.flush()

        assertEquals(listOf(listOf("a"), listOf("b")), sent.toList())
        assertEquals(listOf("[ForgeOpsTracker] dropped 1 metrics: the server rejected it"), log.toList())
    }

    @Test
    fun `a batch the server answers with 403 is not sent again and leaves room for new metrics`() {
        var requests = 0
        val server = startTestHttpServer("/api/v1/custom_metrics") { exchange, _ ->
            requests++
            exchange.sendResponseHeaders(403, -1)
        }.also { servers.add(it) }

        ForgeOpsTracker.init(installUncaughtExceptionHandler = false) {
            it.dsn = "http://key@localhost:${server.address.port}/api/v1/events"
            it.environment = "production"
        }
        repeat(MetricBuffer.MAX_ENTRIES + 10) { ForgeOpsTracker.captureMetric("signup") } // fills the buffer; the last ten are refused
        ForgeOpsTracker.flushMetrics()
        ForgeOpsTracker.flushMetrics()
        assertEquals(1, requests)

        ForgeOpsTracker.captureMetric("signup") // kept again: the rejected batch no longer fills the buffer
        ForgeOpsTracker.flushMetrics()
        assertEquals(2, requests)
    }

    @Test
    fun `the fifth failed flush in a row drops the batch`() {
        val log = CopyOnWriteArrayList<String>()
        var attempts = 0
        val config = configuration().apply { logger = { log.add(it) } }
        val deliver: (List<Map<String, Any?>>) -> Boolean = { attempts++; false }
        val buffer = MetricBuffer(config, deliver) { Duration.ofHours(1) }.also { buffers.add(it) }

        buffer.record(entry("a", 1.0))
        repeat(4) { buffer.flush() }
        assertEquals(1, buffer.size()) // four failures in: still kept

        buffer.flush() // the fifth: dropped
        buffer.flush() // nothing left to send

        assertEquals(5, attempts)
        assertEquals(0, buffer.size())
        assertEquals(listOf("[ForgeOpsTracker] dropped 1 metrics: delivery failed 5 times in a row"), log.toList())
    }

    @Test
    fun `an entry recorded while delivery is in flight is never lost`() {
        val inFlight = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CopyOnWriteArrayList<List<String>>()
        val buffer = buffer { entries ->
            delivered.add(entries.map { it["metric_name"] as String })
            if (delivered.size == 1) {
                inFlight.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            true
        }

        buffer.record(entry("first", 1.0))
        val flushing = Thread { buffer.flush() }.also { it.start() }
        assertTrue(inFlight.await(5, TimeUnit.SECONDS))
        buffer.record(entry("during", 2.0))
        release.countDown()
        flushing.join(5000)
        buffer.flush()

        assertEquals(listOf(listOf("first"), listOf("during")), delivered.toList())
    }

    @Test
    fun `is capped and drops further entries until a flush succeeds`() {
        val buffer = buffer { false }
        val accepted = (1..MetricBuffer.MAX_ENTRIES + 50).count { buffer.record(entry("m", 1.0)) }
        assertEquals(MetricBuffer.MAX_ENTRIES, accepted)
    }

    @Test
    fun `captureMetric and captureInfrastructureMetric deliver to their own endpoints end to end`() {
        val bodies = ConcurrentHashMap<String, String>()
        val auth = ConcurrentHashMap<String, String>()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("localhost", 0), 0).also { servers.add(it) }
        for (path in listOf("/api/v1/custom_metrics", "/api/v1/infrastructure_metrics")) {
            server.createContext(path) { exchange ->
                bodies[path] = String(exchange.requestBody.readAllBytes())
                auth[path] = exchange.requestHeaders.getFirst("Authorization")
                exchange.sendResponseHeaders(202, -1)
                exchange.close()
            }
        }
        server.start()

        ForgeOpsTracker.init(installUncaughtExceptionHandler = false) {
            it.dsn = "http://key@localhost:${server.address.port}/api/v1/events"
            it.environment = "production"
            it.release = "a1b2c3d"
        }
        ForgeOpsTracker.captureMetric("signup")
        ForgeOpsTracker.captureMetric("payment", 49.0)
        ForgeOpsTracker.captureInfrastructureMetric("cpu", 0.42, "db-1")
        ForgeOpsTracker.captureInfrastructureMetric("memory", 0.7)
        ForgeOpsTracker.flushMetrics()

        val custom = bodies["/api/v1/custom_metrics"]
        assertNotNull(custom)
        assertEquals("Bearer key", auth["/api/v1/custom_metrics"])
        assertTrue(custom!!.contains("\"metric_name\":\"signup\""))
        assertTrue(custom.contains("\"value\":49.0"))
        assertTrue(custom.contains("\"environment\":\"production\""))
        assertTrue(custom.contains("\"release\":\"a1b2c3d\""))
        val infrastructure = bodies["/api/v1/infrastructure_metrics"]!!
        assertTrue(infrastructure.contains("\"hostname\":\"db-1\""))
        assertTrue(infrastructure.contains("\"hostname\":\"${Configuration().serverName}\""))
    }

    @Test
    fun `captureMetric is a no-op when the client is not enabled`() {
        ForgeOpsTracker.init(installUncaughtExceptionHandler = false) {
            it.dsn = "https://key@tracker.example.com/api/v1/events"
            it.environment = "development"
        }
        ForgeOpsTracker.captureMetric("signup")
        ForgeOpsTracker.captureInfrastructureMetric("cpu", 1.0)
        ForgeOpsTracker.flushMetrics() // returns without a buffer ever having been created
    }

    @Test
    fun `the metric uris swap the trailing events segment`() {
        val config = configuration()
        assertEquals("https://tracker.example.com/api/v1/custom_metrics", config.customMetricsUri.toString())
        assertEquals("https://tracker.example.com/api/v1/infrastructure_metrics", config.infrastructureMetricsUri.toString())
    }
}

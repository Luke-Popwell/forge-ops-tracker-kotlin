package io.github.lukepopwell.tracker

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.net.ServerSocket
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ChangeTest {
    private var server: HttpServer? = null

    @BeforeEach
    fun reset() {
        ForgeOpsTracker.resetForTesting()
    }

    @AfterEach
    fun cleanup() {
        server?.stop(0)
        ForgeOpsTracker.resetForTesting()
    }

    private fun init(port: Int, environment: String = "production") {
        ForgeOpsTracker.init(installUncaughtExceptionHandler = false) { config ->
            config.dsn = "http://key@localhost:$port/api/v1/events"
            config.environment = environment
        }
    }

    @Test
    fun `recordChange posts the change to the changes endpoint`() {
        val latch = CountDownLatch(1)
        var body = ""
        var authHeader: String? = null
        server = startTestHttpServer("/api/v1/changes") { exchange, received ->
            body = received
            authHeader = exchange.requestHeaders.getFirst("Authorization")
            latch.countDown()
            exchange.sendResponseHeaders(202, -1)
        }
        init(server!!.address.port)

        ForgeOpsTracker.recordChange(
            "feature_flag",
            "new_checkout turned on",
            details = mapOf("key" to "new_checkout", "from" to false, "to" to true),
            service = "checkout",
            actor = "flag-service",
            url = "https://flags.example.com/new_checkout",
            id = "flag-123",
            occurredAt = Instant.parse("2026-09-25T12:00:00Z"),
        )

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals("Bearer key", authHeader)
        assertEquals(
            """{"kind":"feature_flag","title":"new_checkout turned on","environment":"production",""" +
                """"occurred_at":"2026-09-25T12:00:00Z","details":{"key":"new_checkout","from":false,"to":true},""" +
                """"service":"checkout","actor":"flag-service","url":"https://flags.example.com/new_checkout","id":"flag-123"}""",
            body,
        )
    }

    @Test
    fun `optional fields are left out and occurred_at defaults to now`() {
        val config = Configuration().apply { environment = "staging" }
        val before = Instant.now()

        val payload = Change.payload(config, "config", "checkout timeout raised")!!

        assertEquals(setOf("kind", "title", "environment", "occurred_at"), payload.keys)
        assertEquals("staging", payload["environment"])
        assertFalse(Instant.parse(payload["occurred_at"] as String).isBefore(before.minusSeconds(1)))
    }

    @Test
    fun `an unknown kind is sent as other`() {
        Change.KINDS.forEach { assertEquals(it, Change.normalizeKind(it)) }
        assertEquals("other", Change.normalizeKind("flag"))
        assertEquals("other", Change.normalizeKind(null))
        assertEquals("other", Change.payload(Configuration(), "deploy", "x")!!["kind"])
    }

    @Test
    fun `the title is truncated, a blank one drops the change, and a bad url or details are left out`() {
        val config = Configuration()
        assertEquals(200, (Change.payload(config, "other", "a".repeat(250))!!["title"] as String).length)
        assertNull(Change.payload(config, "other", "   "))
        assertNull(Change.payload(config, "other", "x", url = "javascript:alert(1)")!!["url"])
        assertNull(Change.payload(config, "other", "x", details = mapOf("ratio" to listOf(Double.NaN)))!!["details"])
        assertEquals("qa", Change.payload(config, "other", "x", environment = "qa")!!["environment"])
    }

    @Test
    fun `recordChange is a no-op when the client is not enabled`() {
        val received = AtomicInteger(0)
        server = startTestHttpServer("/api/v1/changes") { exchange, _ ->
            received.incrementAndGet()
            exchange.sendResponseHeaders(202, -1)
        }
        init(server!!.address.port, environment = "development")

        ForgeOpsTracker.recordChange("feature_flag", "ignored")

        Thread.sleep(200)
        assertEquals(0, received.get())
    }

    @Test
    fun `recordChange never throws when the plan rejects it or the server is unreachable`() {
        val latch = CountDownLatch(1)
        server = startTestHttpServer("/api/v1/changes") { exchange, _ ->
            latch.countDown()
            exchange.sendResponseHeaders(403, -1)
        }
        init(server!!.address.port)
        assertDoesNotThrow { ForgeOpsTracker.recordChange("feature_flag", "rejected") }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertFalse(Client(ForgeOpsTracker.configuration()).deliverChange(mapOf("kind" to "other", "title" to "x")))

        val closedPort = ServerSocket(0).use { it.localPort }
        ForgeOpsTracker.resetForTesting()
        init(closedPort)
        assertDoesNotThrow { ForgeOpsTracker.recordChange("feature_flag", "unreachable") }
        assertFalse(Client(ForgeOpsTracker.configuration()).deliverChange(mapOf("kind" to "other", "title" to "x")))
    }

    @Test
    fun `the changes uri swaps the trailing events segment`() {
        val config = Configuration().apply { dsn = "https://key@tracker.example.com/api/v1/events" }
        assertEquals("https://tracker.example.com/api/v1/changes", config.changesUri.toString())
    }
}

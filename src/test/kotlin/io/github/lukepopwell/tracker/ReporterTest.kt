package io.github.lukepopwell.tracker

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ReporterTest {
    private var server: HttpServer? = null

    @AfterEach
    fun stopServer() {
        server?.stop(0)
    }

    private fun serverCountingRequests(): Pair<AtomicInteger, CountDownLatch> {
        val received = AtomicInteger(0)
        val latch = CountDownLatch(1)
        server = startTestHttpServer { exchange, _ ->
            received.incrementAndGet()
            latch.countDown()
            exchange.sendResponseHeaders(202, -1)
        }
        return received to latch
    }

    @Test
    fun `report does nothing when disabled`() {
        val (received, _) = serverCountingRequests()
        val config = Configuration()
        config.dsn = "http://key@localhost:${server!!.address.port}/api/v1/events"
        config.environment = "development" // not in enabledEnvironments
        val reporter = Reporter(config)

        reporter.report(IllegalStateException("boom"))
        Thread.sleep(100)

        assertEquals(0, received.get())
    }

    @Test
    fun `report does nothing for a null error`() {
        val (received, _) = serverCountingRequests()
        val config = Configuration()
        config.dsn = "http://key@localhost:${server!!.address.port}/api/v1/events"
        config.environment = "production"
        val reporter = Reporter(config)

        reporter.report(null)
        Thread.sleep(100)

        assertEquals(0, received.get())
    }

    @Test
    fun `report delivers when enabled`() {
        val (received, latch) = serverCountingRequests()
        val config = Configuration()
        config.dsn = "http://key@localhost:${server!!.address.port}/api/v1/events"
        config.environment = "production"
        val reporter = Reporter(config)

        reporter.report(IllegalStateException("boom"))

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(1, received.get())
    }

    @Test
    fun `report includes the given user in the delivered payload`() {
        var deliveredBody = ""
        val latch = CountDownLatch(1)
        server = startTestHttpServer { exchange, body ->
            deliveredBody = body
            latch.countDown()
            exchange.sendResponseHeaders(202, -1)
        }
        val config = Configuration()
        config.dsn = "http://key@localhost:${server!!.address.port}/api/v1/events"
        config.environment = "production"
        val reporter = Reporter(config)

        reporter.report(IllegalStateException("boom"), user = mapOf("id" to 42, "email" to "alice@example.com"))

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(deliveredBody.contains("alice@example.com"))
    }

    @Test
    fun `report includes the given breadcrumbs in the delivered payload`() {
        var deliveredBody = ""
        val latch = CountDownLatch(1)
        server = startTestHttpServer { exchange, body ->
            deliveredBody = body
            latch.countDown()
            exchange.sendResponseHeaders(202, -1)
        }
        val config = Configuration()
        config.dsn = "http://key@localhost:${server!!.address.port}/api/v1/events"
        config.environment = "production"
        val reporter = Reporter(config)
        val crumb = mapOf("category" to "controller", "message" to "GET /orders/42", "level" to "info", "timestamp" to "2024-01-15T10:29:58Z", "data" to emptyMap<String, Any?>())

        reporter.report(IllegalStateException("boom"), breadcrumbs = listOf(crumb))

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(deliveredBody.contains("GET /orders/42"))
    }

    @Test
    fun `report never throws even when the queue backing it fails`() {
        val config = Configuration()
        config.dsn = "http://key@localhost:1/api/v1/events" // nothing listening
        config.environment = "production"
        val reporter = Reporter(config)

        // Must not throw: an error reporter that can crash the host app while reporting is the
        // worst possible failure mode.
        reporter.report(IllegalStateException("boom"))
    }
}

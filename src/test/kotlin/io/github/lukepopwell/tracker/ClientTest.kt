package io.github.lukepopwell.tracker

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

class ClientTest {
    private var server: HttpServer? = null

    @AfterEach
    fun stopServer() {
        server?.stop(0)
    }

    @Test
    fun `delivers successfully on a 2xx response`() {
        val receivedAuth = AtomicReference<String>()
        val receivedBody = AtomicReference<String>()
        server = startTestHttpServer { exchange, body ->
            receivedAuth.set(exchange.requestHeaders.getFirst("Authorization"))
            receivedBody.set(body)
            exchange.sendResponseHeaders(202, -1)
        }

        val config = Configuration()
        config.dsn = "http://the-api-key@localhost:${server!!.address.port}/api/v1/events"
        val client = Client(config)

        val delivered = client.deliver(mapOf("message" to "boom"))

        assertTrue(delivered)
        assertEquals("Bearer the-api-key", receivedAuth.get())
        assertEquals("{\"message\":\"boom\"}", receivedBody.get())
    }

    @Test
    fun `returns false on a non-2xx response`() {
        server = startTestHttpServer { exchange, _ -> exchange.sendResponseHeaders(500, -1) }

        val config = Configuration()
        config.dsn = "http://key@localhost:${server!!.address.port}/api/v1/events"
        val client = Client(config)

        assertFalse(client.deliver(mapOf("message" to "boom")))
    }

    @Test
    fun `returns false when there is no ingestion uri`() {
        val config = Configuration()
        config.dsn = null
        val client = Client(config)

        assertFalse(client.deliver(mapOf("message" to "boom")))
    }

    @Test
    fun `returns false rather than throwing when nothing is listening`() {
        val closedPort = ServerSocket(0).use { it.localPort }

        val config = Configuration()
        config.dsn = "http://key@localhost:$closedPort/api/v1/events"
        config.connectTimeout = Duration.ofMillis(300)
        val client = Client(config)

        assertFalse(client.deliver(mapOf("message" to "boom")))
    }

    @Test
    fun `outcome tells a rejected payload apart from one that only failed for now`() {
        var status = 202
        server = startTestHttpServer { exchange, _ -> exchange.sendResponseHeaders(status, -1) }

        val config = Configuration()
        config.dsn = "http://key@localhost:${server!!.address.port}/api/v1/events"
        val client = Client(config)

        val expected = mapOf(
            202 to DeliveryResult.DELIVERED,
            400 to DeliveryResult.REJECTED,
            401 to DeliveryResult.REJECTED,
            403 to DeliveryResult.REJECTED,
            413 to DeliveryResult.REJECTED,
            422 to DeliveryResult.REJECTED,
            408 to DeliveryResult.FAILED,
            429 to DeliveryResult.FAILED,
            500 to DeliveryResult.FAILED,
            503 to DeliveryResult.FAILED,
        )
        for ((code, result) in expected) {
            status = code
            assertEquals(result, client.outcome { client.deliver(mapOf("message" to "boom")) }, "HTTP $code")
        }
    }

    @Test
    fun `outcome is a failure for now when nothing is listening`() {
        val closedPort = ServerSocket(0).use { it.localPort }

        val config = Configuration()
        config.dsn = "http://key@localhost:$closedPort/api/v1/events"
        config.connectTimeout = Duration.ofMillis(300)
        val client = Client(config)

        assertEquals(DeliveryResult.FAILED, client.outcome { client.deliver(mapOf("message" to "boom")) })
    }
}

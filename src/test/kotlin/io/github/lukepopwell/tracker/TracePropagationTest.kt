package io.github.lukepopwell.tracker

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class TracePropagationTest {
    private var server: HttpServer? = null
    private val spans = LinkedBlockingQueue<String>()
    private val events = LinkedBlockingQueue<String>()

    private val incomingTraceId = "4bf92f3577b34da6a3ce929d0e0e4736"
    private val incomingSpanId = "00f067aa0ba902b7"
    private val incoming = "00-$incomingTraceId-$incomingSpanId-01"

    @BeforeEach
    fun start() {
        ForgeOpsTracker.resetForTesting()
        server = startTestHttpServer { exchange, body ->
            events.add(body)
            exchange.sendResponseHeaders(202, -1)
        }.also { server ->
            server.createContext("/api/v1/spans") { exchange ->
                spans.add(exchange.requestBody.readAllBytes().decodeToString())
                exchange.sendResponseHeaders(202, -1)
                exchange.close()
            }
        }
    }

    @AfterEach
    fun stop() {
        ForgeOpsTracker.resetForTesting()
        server?.stop(0)
    }

    private fun init(configure: (Configuration) -> Unit = {}) {
        ForgeOpsTracker.init(installUncaughtExceptionHandler = false) {
            it.dsn = "http://key@localhost:${server!!.address.port}/api/v1/events"
            it.environment = "production"
            it.traceCaptureThreshold = Duration.ofMillis(10)
            configure(it)
        }
    }

    private fun spanNamed(json: String, name: String): String =
        Regex("\\{[^{}]*\"name\":\"${Regex.escape(name)}\"[^{}]*").find(json)?.value ?: error("no span named $name in $json")

    private fun field(span: String, key: String): String? =
        Regex("\"$key\":(null|\"([^\"]*)\")").find(span)?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }

    // TraceParent

    @Test
    fun `parses a valid traceparent`() {
        assertEquals(TraceParent.Context(incomingTraceId, incomingSpanId), TraceParent.parse(incoming))
        assertEquals(TraceParent.Context(incomingTraceId, incomingSpanId), TraceParent.parse("  $incoming "))
    }

    @Test
    fun `rejects malformed or invalid traceparent values`() {
        val invalid = listOf(
            null,
            "",
            "garbage",
            incoming.uppercase(),
            "ff-$incomingTraceId-$incomingSpanId-01",
            "00-${"0".repeat(32)}-$incomingSpanId-01",
            "00-$incomingTraceId-${"0".repeat(16)}-01",
            "00-$incomingTraceId-$incomingSpanId-01-extra",
            "00-${incomingTraceId.drop(1)}-$incomingSpanId-01",
        )
        for (value in invalid) assertNull(TraceParent.parse(value), "value = $value")
    }

    @Test
    fun `accepts a future version with extra fields`() {
        assertEquals(TraceParent.Context(incomingTraceId, incomingSpanId), TraceParent.parse("01-$incomingTraceId-$incomingSpanId-01-future"))
    }

    @Test
    fun `builds a sampled version 00 header`() {
        assertEquals(incoming, TraceParent.build(incomingTraceId, incomingSpanId))
    }

    @Test
    fun `generates W3C shaped ids`() {
        repeat(50) {
            assertTrue(TraceParent.generateTraceId().matches(Regex("[0-9a-f]{32}")))
            assertTrue(TraceParent.generateSpanId().matches(Regex("[0-9a-f]{16}")))
        }
        assertTrue(SpanBuffer(Configuration()).traceId.matches(Regex("[0-9a-f]{32}")))
    }

    // Continuing an incoming trace

    @Test
    fun `trace continues an incoming traceparent and parents its root under the remote span`() {
        init()
        ForgeOpsTracker.trace("POST /orders", incoming) {
            assertEquals(incomingTraceId, ForgeOpsTracker.currentTraceId())
            Thread.sleep(30)
        }

        val json = spans.poll(3, TimeUnit.SECONDS) ?: error("trace was never delivered")
        assertTrue(json.contains("\"trace_id\":\"$incomingTraceId\""))
        assertEquals(incomingSpanId, field(spanNamed(json, "POST /orders"), "parent_span_id"))
    }

    @Test
    fun `a malformed incoming traceparent starts a fresh trace with no parent`() {
        init()
        ForgeOpsTracker.trace("POST /orders", "not-a-header") { Thread.sleep(30) }

        val json = spans.poll(3, TimeUnit.SECONDS) ?: error("trace was never delivered")
        assertFalse(json.contains(incomingTraceId))
        assertTrue(json.contains("\"parent_span_id\":null"))
    }

    @Test
    fun `currentTraceId is null outside a trace`() {
        init()
        assertNull(ForgeOpsTracker.currentTraceId())
        ForgeOpsTracker.trace("t") { assertTrue(ForgeOpsTracker.currentTraceId()!!.matches(Regex("[0-9a-f]{32}"))) }
        assertNull(ForgeOpsTracker.currentTraceId())
    }

    // Outgoing header

    @Test
    fun `httpSpan hands the block a traceparent naming the http span it records`() {
        init()
        var header: String? = null
        val result = ForgeOpsTracker.trace("GET /checkout") {
            ForgeOpsTracker.httpSpan("post", URI("https://payments.example.com/charges?card=4242")) { headers ->
                header = headers[TraceParent.HEADER]
                Thread.sleep(30)
                "charged"
            }
        }
        assertEquals("charged", result)

        val json = spans.poll(3, TimeUnit.SECONDS) ?: error("trace was never delivered")
        val span = spanNamed(json, "POST payments.example.com")
        assertEquals("http", field(span, "kind"))
        val parsed = TraceParent.parse(header) ?: error("no usable header: $header")
        assertEquals(field(span, "span_id"), parsed.parentSpanId)
        assertTrue(json.contains("\"trace_id\":\"${parsed.traceId}\""))
        assertEquals(field(spanNamed(json, "GET /checkout"), "span_id"), field(span, "parent_span_id"))
        assertFalse(json.contains("4242"))
    }

    @Test
    fun `httpSpan records the span and rethrows unchanged when the call fails`() {
        init()
        val failure = java.io.IOException("connection reset")
        val thrown = assertThrows(java.io.IOException::class.java) {
            ForgeOpsTracker.trace("GET /checkout") {
                ForgeOpsTracker.httpSpan("GET", "https://api.example.com/x") { Thread.sleep(30); throw failure }
            }
        }
        assertEquals(failure, thrown)
        spanNamed(spans.poll(3, TimeUnit.SECONDS) ?: error("trace was never delivered"), "GET api.example.com")
    }

    @Test
    fun `httpSpan outside a trace passes no headers and records nothing`() {
        init()
        assertEquals(emptyMap<String, String>(), ForgeOpsTracker.httpSpan("GET", "https://api.example.com/") { it })
    }

    @Test
    fun `propagateTraces off still records the span but sends no header`() {
        init { it.propagateTraces = false }
        val headers = ForgeOpsTracker.trace("t") {
            ForgeOpsTracker.httpSpan("GET", "https://api.example.com/") { Thread.sleep(30); it }
        }
        assertEquals(emptyMap<String, String>(), headers)
        spanNamed(spans.poll(3, TimeUnit.SECONDS) ?: error("trace was never delivered"), "GET api.example.com")
    }

    @Test
    fun `tracePropagationTargets limits which hosts get the header`() {
        init { it.tracePropagationTargets = listOf("internal.example") }
        ForgeOpsTracker.trace("t") {
            assertTrue(ForgeOpsTracker.httpSpan("GET", "https://orders.internal.example/") { it }.containsKey("traceparent"))
            assertTrue(ForgeOpsTracker.httpSpan("GET", "https://api.thirdparty.example/") { it }.isEmpty())
            assertTrue(ForgeOpsTracker.httpSpan("GET", "::not a url::") { it }.isEmpty())
        }
    }

    @Test
    fun `trackTracing off sends no spans but still propagates the trace id and tags errors`() {
        init { it.trackTracing = false }
        var traceId: String? = null
        ForgeOpsTracker.trace("t") {
            traceId = ForgeOpsTracker.currentTraceId()
            val headers = ForgeOpsTracker.httpSpan("GET", "https://api.example.com/") { it }
            assertEquals(traceId, TraceParent.parse(headers["traceparent"])?.traceId)
            ForgeOpsTracker.captureException(IllegalStateException("boom"))
            Thread.sleep(30)
        }
        val event = events.poll(3, TimeUnit.SECONDS) ?: error("event was never delivered")
        assertTrue(event.contains("\"trace_id\":\"$traceId\""))
        assertNull(spans.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `reporting disabled starts no trace and passes no headers`() {
        init { it.environment = "development" }
        ForgeOpsTracker.trace("t", incoming) {
            assertNull(ForgeOpsTracker.currentTraceId())
            assertTrue(ForgeOpsTracker.httpSpan("GET", "https://api.example.com/") { it }.isEmpty())
        }
    }

    // Errors

    @Test
    fun `an error captured inside a trace carries its trace id`() {
        init()
        ForgeOpsTracker.trace("POST /orders", incoming) {
            ForgeOpsTracker.captureException(IllegalStateException("boom"))
        }
        val event = events.poll(3, TimeUnit.SECONDS) ?: error("event was never delivered")
        assertTrue(event.contains("\"trace_id\":\"$incomingTraceId\""))
    }

    @Test
    fun `an error captured outside a trace has no trace_id key at all`() {
        init()
        ForgeOpsTracker.trace("t") { }
        ForgeOpsTracker.captureException(IllegalStateException("boom"))
        val event = events.poll(3, TimeUnit.SECONDS) ?: error("event was never delivered")
        assertFalse(event.contains("\"trace_id\":")) // the key; this test's own name is in the backtrace
    }

    @Test
    fun `nested traces keep the outer trace id`() {
        init()
        ForgeOpsTracker.trace("outer") {
            val outer = ForgeOpsTracker.currentTraceId()
            ForgeOpsTracker.trace("inner", incoming) { assertEquals(outer, ForgeOpsTracker.currentTraceId()) }
            assertNotEquals(incomingTraceId, outer)
        }
    }
}

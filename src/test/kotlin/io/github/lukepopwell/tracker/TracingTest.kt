package io.github.lukepopwell.tracker

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class TracingTest {
    private var server: HttpServer? = null
    private val bodies = LinkedBlockingQueue<String>()

    @BeforeEach
    fun start() {
        ForgeOpsTracker.resetForTesting()
        server = startTestHttpServer("/api/v1/spans") { exchange, body ->
            bodies.add(body)
            exchange.sendResponseHeaders(202, -1)
        }
    }

    @AfterEach
    fun stop() {
        ForgeOpsTracker.resetForTesting()
        server?.stop(0)
    }

    private fun init(tracing: Boolean = true) {
        ForgeOpsTracker.init(installUncaughtExceptionHandler = false) {
            it.dsn = "http://key@localhost:${server!!.address.port}/api/v1/events"
            it.environment = "production"
            it.release = "abc123"
            it.trackTracing = tracing
            it.traceCaptureThreshold = Duration.ofMillis(10)
        }
    }

    @Test
    fun `delivers a slow trace with nested spans to the spans endpoint`() {
        init()
        val result = ForgeOpsTracker.trace("GET /checkout") {
            ForgeOpsTracker.span("charge", "service", mapOf("orderId" to 1)) {
                ForgeOpsTracker.recordSpan("SELECT", "database", Instant.now(), 3.0)
                Thread.sleep(30)
                "ok"
            }
        }
        assertEquals("ok", result)

        val json = bodies.poll(3, TimeUnit.SECONDS) ?: error("trace was never delivered")
        assertTrue(json.contains("\"name\":\"GET /checkout\""))
        assertTrue(json.contains("\"name\":\"charge\""))
        assertTrue(json.contains("\"name\":\"SELECT\""))
        assertTrue(json.contains("\"parent_span_id\":null"))
        assertTrue(json.contains("\"environment\":\"production\""))
        assertTrue(json.contains("\"release\":\"abc123\""))
        assertTrue(Regex("\"trace_id\":\"[0-9a-f]{32}\"").containsMatchIn(json))
        assertTrue(Regex("\"span_id\":\"[0-9a-f]{16}\"").containsMatchIn(json))
        assertTrue(Regex("\"started_at\":\"\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z\"").containsMatchIn(json))
    }

    @Test
    fun `a child span's parent is the span open around it, and the root otherwise`() {
        init()
        val config = ForgeOpsTracker.configuration()
        val buffer = SpanBuffer(config)
        val outer = buffer.open()
        buffer.recordLeaf("inner", "database", Instant.now(), 1.0, emptyMap())
        buffer.finish(outer, "outer", "service", Instant.now(), 5.0, emptyMap())
        buffer.recordLeaf("sibling", "database", Instant.now(), 1.0, emptyMap())

        @Suppress("UNCHECKED_CAST")
        val spans = buffer.finishTrace("root", Instant.now(), 100.0)!!["spans"] as List<Map<String, Any?>>
        val byName = spans.associateBy { it["name"] }
        assertNull(byName["root"]!!["parent_span_id"])
        assertEquals(byName["outer"]!!["span_id"], byName["inner"]!!["parent_span_id"])
        assertEquals(byName["root"]!!["span_id"], byName["outer"]!!["parent_span_id"])
        assertEquals(byName["root"]!!["span_id"], byName["sibling"]!!["parent_span_id"])
    }

    @Test
    fun `sends an unknown kind as other since the server would reject the whole trace`() {
        val buffer = SpanBuffer(Configuration())
        buffer.recordLeaf("q", "db", Instant.now(), 1.0, emptyMap())
        buffer.recordLeaf("r", "database", Instant.now(), 1.0, emptyMap())
        @Suppress("UNCHECKED_CAST")
        val spans = buffer.finishTrace("root", Instant.now(), 5000.0)!!["spans"] as List<Map<String, Any?>>
        assertEquals("other", spans[1]["kind"])
        assertEquals("database", spans[2]["kind"])
    }

    @Test
    fun `caps a trace at 500 spans including the root`() {
        val buffer = SpanBuffer(Configuration())
        repeat(700) { buffer.recordLeaf("q", "database", Instant.now(), 1.0, emptyMap()) }
        @Suppress("UNCHECKED_CAST")
        val spans = buffer.finishTrace("root", Instant.now(), 5000.0)!!["spans"] as List<*>
        assertEquals(500, spans.size)
    }

    @Test
    fun `records the span and rethrows unchanged when the block throws`() {
        init()
        val failure = RuntimeException("boom")
        val thrown = assertThrows(RuntimeException::class.java) {
            ForgeOpsTracker.trace("GET /x") {
                ForgeOpsTracker.span("bad") { Thread.sleep(30); throw failure }
            }
        }
        assertEquals(failure, thrown)
        val json = bodies.poll(3, TimeUnit.SECONDS) ?: error("trace was never delivered")
        assertTrue(json.contains("\"name\":\"bad\""))
    }

    @Test
    fun `span just runs the block outside a trace`() {
        init()
        assertEquals(7, ForgeOpsTracker.span("free") { 7 })
    }

    @Test
    fun `sends nothing when the root is under the threshold`() {
        init()
        ForgeOpsTracker.trace("GET /fast") { ForgeOpsTracker.span("x") { 1 } }
        assertNull(bodies.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `trackTracing off disables the whole feature`() {
        init(tracing = false)
        ForgeOpsTracker.trace("GET /x") { Thread.sleep(30) }
        assertNull(bodies.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a trace inside a trace records a span instead of starting a second trace`() {
        init()
        ForgeOpsTracker.trace("outer") { ForgeOpsTracker.trace("inner") { Thread.sleep(30) } }
        val json = bodies.poll(3, TimeUnit.SECONDS) ?: error("trace was never delivered")
        assertEquals(1, Regex("\"trace_id\"").findAll(json).count())
        assertTrue(json.contains("\"name\":\"inner\""))
        assertNull(bodies.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a database span sends its statement masked as db statement, with db system`() {
        init()
        ForgeOpsTracker.trace("GET /orders") {
            ForgeOpsTracker.span(
                "Load orders",
                "database",
                mapOf("rows" to 3),
                statement = "SELECT * FROM orders WHERE email = 'a@b.co' AND total > 4200",
                dbSystem = "SQLite",
            ) { Thread.sleep(30) }
            ForgeOpsTracker.recordSpan(
                "Count",
                "database",
                Instant.now(),
                1.0,
                mapOf("db.statement" to "SELECT count(*) FROM carts WHERE token = 'secret-token'"),
            )
            ForgeOpsTracker.recordSpan("Not db", "service", Instant.now(), 1.0, statement = "SELECT 'x'", dbSystem = "sqlite")
        }

        val json = bodies.poll(3, TimeUnit.SECONDS) ?: error("trace was never delivered")
        assertTrue(json.contains("\"db.statement\":\"SELECT * FROM orders WHERE email = ? AND total > ?\""), json)
        assertTrue(json.contains("\"db.system\":\"sqlite\""), json)
        assertTrue(json.contains("\"rows\":3"), json)
        assertTrue(json.contains("\"db.statement\":\"SELECT count(*) FROM carts WHERE token = ?\""), json)
        assertEquals(1, Regex("\"db.system\"").findAll(json).count())
        assertTrue(!json.contains("a@b.co") && !json.contains("secret-token") && !json.contains("'x'"), json)
    }

    @Test
    fun `a database statement is cut at 4000 characters`() {
        val sql = "SELECT " + "a, ".repeat(3000) + "b FROM t"
        val statement = ForgeOpsTracker.spanData("database", emptyMap(), sql, null)["db.statement"] as String
        assertEquals(4003, statement.length)
        assertTrue(statement.endsWith("..."))
    }

    @Test
    fun `double quoted strings are masked only for mysql and mariadb`() {
        val sql = "SELECT id FROM users WHERE token = \"abc123secret\""
        assertEquals("SELECT id FROM users WHERE token = ?", ForgeOpsTracker.spanData("database", emptyMap(), sql, "MySQL")["db.statement"])
        assertEquals(
            "SELECT id FROM users WHERE token = ?",
            ForgeOpsTracker.spanData("database", mapOf("db.statement" to sql, "db.system" to "mariadb"), null, null)["db.statement"],
        )
        assertEquals(sql, ForgeOpsTracker.spanData("database", emptyMap(), sql, "postgresql")["db.statement"])
    }
}

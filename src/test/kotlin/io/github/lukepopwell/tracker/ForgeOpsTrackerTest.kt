package io.github.lukepopwell.tracker

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ForgeOpsTrackerTest {
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

    @Test
    fun `configure configures and returns the shared configuration`() {
        val config = ForgeOpsTracker.configuration()
        config.dsn = "https://key@tracker.example.com/api/v1/events"
        config.release = "abc123"

        assertEquals("https://key@tracker.example.com/api/v1/events", ForgeOpsTracker.configuration().dsn)
        assertEquals("abc123", ForgeOpsTracker.configuration().release)
    }

    @Test
    fun `captureException delivers through the full stack`() {
        val received = AtomicInteger(0)
        val latch = CountDownLatch(1)
        var authHeader: String? = null
        server = startTestHttpServer { exchange, _ ->
            authHeader = exchange.requestHeaders.getFirst("Authorization")
            received.incrementAndGet()
            latch.countDown()
            exchange.sendResponseHeaders(202, -1)
        }

        ForgeOpsTracker.init(installUncaughtExceptionHandler = false) { config ->
            config.dsn = "http://key@localhost:${server!!.address.port}/api/v1/events"
            config.environment = "production"
        }

        ForgeOpsTracker.captureException(IllegalStateException("boom"))

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(1, received.get())
        assertEquals("Bearer key", authHeader)
    }

    @Test
    fun `setUser attaches the user to a later captureException call`() {
        val latch = CountDownLatch(1)
        var deliveredBody = ""
        server = startTestHttpServer { exchange, body ->
            deliveredBody = body
            latch.countDown()
            exchange.sendResponseHeaders(202, -1)
        }

        ForgeOpsTracker.init(installUncaughtExceptionHandler = false) { config ->
            config.dsn = "http://key@localhost:${server!!.address.port}/api/v1/events"
            config.environment = "production"
        }

        ForgeOpsTracker.setUser(mapOf("id" to 42, "email" to "alice@example.com"))
        ForgeOpsTracker.captureException(IllegalStateException("boom"))

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(deliveredBody.contains("alice@example.com"))
    }

    @Test
    fun `an explicit user argument overrides whatever setUser last set`() {
        val latch = CountDownLatch(1)
        var deliveredBody = ""
        server = startTestHttpServer { exchange, body ->
            deliveredBody = body
            latch.countDown()
            exchange.sendResponseHeaders(202, -1)
        }

        ForgeOpsTracker.init(installUncaughtExceptionHandler = false) { config ->
            config.dsn = "http://key@localhost:${server!!.address.port}/api/v1/events"
            config.environment = "production"
        }

        ForgeOpsTracker.setUser(mapOf("id" to 42))
        ForgeOpsTracker.captureException(IllegalStateException("boom"), user = mapOf("id" to 99))

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(deliveredBody.contains("\"id\":99"))
    }

    @Test
    fun `addBreadcrumb accumulates on this thread and captureException attaches the trail automatically`() {
        val latch = CountDownLatch(1)
        var deliveredBody = ""
        server = startTestHttpServer { exchange, body ->
            deliveredBody = body
            latch.countDown()
            exchange.sendResponseHeaders(202, -1)
        }

        ForgeOpsTracker.init(installUncaughtExceptionHandler = false) { config ->
            config.dsn = "http://key@localhost:${server!!.address.port}/api/v1/events"
            config.environment = "production"
        }

        ForgeOpsTracker.addBreadcrumb("opened checkout")
        ForgeOpsTracker.addBreadcrumb("charged card", data = mapOf("order_id" to 42))
        ForgeOpsTracker.captureException(IllegalStateException("boom"))

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(deliveredBody.contains("opened checkout"))
        assertTrue(deliveredBody.contains("charged card"))
    }

    @Test
    fun `clearBreadcrumbs empties the trail before the next report`() {
        val latch = CountDownLatch(1)
        var deliveredBody = ""
        server = startTestHttpServer { exchange, body ->
            deliveredBody = body
            latch.countDown()
            exchange.sendResponseHeaders(202, -1)
        }

        ForgeOpsTracker.init(installUncaughtExceptionHandler = false) { config ->
            config.dsn = "http://key@localhost:${server!!.address.port}/api/v1/events"
            config.environment = "production"
        }

        ForgeOpsTracker.addBreadcrumb("stale entry from an earlier request")
        ForgeOpsTracker.clearBreadcrumbs()
        ForgeOpsTracker.captureException(IllegalStateException("boom"))

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(!deliveredBody.contains("\"breadcrumbs\""), "an empty trail should be omitted entirely, not sent as an empty array")
    }

    @Test
    fun `addBreadcrumb is a no-op when trackBreadcrumbs is false`() {
        val latch = CountDownLatch(1)
        var deliveredBody = ""
        server = startTestHttpServer { exchange, body ->
            deliveredBody = body
            latch.countDown()
            exchange.sendResponseHeaders(202, -1)
        }

        ForgeOpsTracker.init(installUncaughtExceptionHandler = false) { config ->
            config.dsn = "http://key@localhost:${server!!.address.port}/api/v1/events"
            config.environment = "production"
            config.trackBreadcrumbs = false
        }

        ForgeOpsTracker.addBreadcrumb("should never be recorded")
        ForgeOpsTracker.captureException(IllegalStateException("boom"))

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(!deliveredBody.contains("\"breadcrumbs\""))
    }

    @Test
    fun `resetForTesting clears the current user`() {
        val latch = CountDownLatch(1)
        var deliveredBody = ""
        server = startTestHttpServer { exchange, body ->
            deliveredBody = body
            latch.countDown()
            exchange.sendResponseHeaders(202, -1)
        }

        ForgeOpsTracker.setUser(mapOf("id" to 42))
        ForgeOpsTracker.resetForTesting()
        ForgeOpsTracker.init(installUncaughtExceptionHandler = false) { config ->
            config.dsn = "http://key@localhost:${server!!.address.port}/api/v1/events"
            config.environment = "production"
        }

        ForgeOpsTracker.captureException(IllegalStateException("boom"))

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(!deliveredBody.contains("\"user\""), "the user set before resetForTesting should not have survived it")
    }

    @Test
    fun `resetForTesting clears the current breadcrumb trail too`() {
        val latch = CountDownLatch(1)
        var deliveredBody = ""
        server = startTestHttpServer { exchange, body ->
            deliveredBody = body
            latch.countDown()
            exchange.sendResponseHeaders(202, -1)
        }

        ForgeOpsTracker.addBreadcrumb("stale entry")
        ForgeOpsTracker.resetForTesting()
        ForgeOpsTracker.init(installUncaughtExceptionHandler = false) { config ->
            config.dsn = "http://key@localhost:${server!!.address.port}/api/v1/events"
            config.environment = "production"
        }

        ForgeOpsTracker.captureException(IllegalStateException("boom"))

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(!deliveredBody.contains("\"breadcrumbs\""), "the breadcrumb added before resetForTesting should not have survived it")
    }

    @Test
    fun `uncaught exception handler chains to the previous handler`() {
        val received = AtomicInteger(0)
        val reportLatch = CountDownLatch(1)
        server = startTestHttpServer { _, _ ->
            received.incrementAndGet()
            reportLatch.countDown()
        }

        val previousHandlerRan = CountDownLatch(1)
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> previousHandlerRan.countDown() }

        ForgeOpsTracker.init(installUncaughtExceptionHandler = true) { config ->
            config.dsn = "http://key@localhost:${server!!.address.port}/api/v1/events"
            config.environment = "production"
        }

        val thread = Thread {
            throw RuntimeException("uncaught boom")
        }
        thread.start()
        thread.join()

        assertTrue(reportLatch.await(2, TimeUnit.SECONDS), "expected the uncaught exception to be reported")
        assertTrue(previousHandlerRan.await(2, TimeUnit.SECONDS), "expected the previously-installed handler to still run")
    }

    private fun initAgainstSamplesServer(configure: Configuration.() -> Unit = {}): java.util.concurrent.LinkedBlockingQueue<String> {
        val bodies = java.util.concurrent.LinkedBlockingQueue<String>()
        server = startTestHttpServer("/api/v1/performance_samples") { exchange, body ->
            bodies.add(body)
            exchange.sendResponseHeaders(202, -1)
        }
        ForgeOpsTracker.init(installUncaughtExceptionHandler = false) { config ->
            config.dsn = "http://key@localhost:${server!!.address.port}/api/v1/events"
            config.environment = "production"
            config.performanceFlushInterval = java.time.Duration.ofHours(1) // flushed by hand below
            config.configure()
        }
        return bodies
    }

    @Test
    fun `recordPerformance tallies in-process and flushPerformance delivers one aggregate batch`() {
        val bodies = initAgainstSamplesServer()

        ForgeOpsTracker.recordPerformance("GET /users/:id", 10.0)
        ForgeOpsTracker.recordPerformance("GET /users/:id", 30.0)
        ForgeOpsTracker.flushPerformance()

        val body = bodies.poll(2, TimeUnit.SECONDS)!!
        assertTrue(body.contains("\"transaction_name\":\"GET /users/:id\""), body)
        assertTrue(body.contains("\"request_count\":2"), body)
    }

    @Test
    fun `timeTransaction returns the block's own value and records how long it took`() {
        val bodies = initAgainstSamplesServer()

        val value = ForgeOpsTracker.timeTransaction("timed") { 42 }
        ForgeOpsTracker.flushPerformance()

        assertEquals(42, value)
        assertTrue(bodies.poll(2, TimeUnit.SECONDS)!!.contains("\"transaction_name\":\"timed\""))
    }

    @Test
    fun `timeTransaction records even when the block throws, and rethrows it unchanged`() {
        val bodies = initAgainstSamplesServer()

        val thrown = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            ForgeOpsTracker.timeTransaction<Unit>("timed throws") { throw IllegalStateException("inside") }
        }
        ForgeOpsTracker.flushPerformance()

        assertEquals("inside", thrown.message)
        assertTrue(bodies.poll(2, TimeUnit.SECONDS)!!.contains("\"transaction_name\":\"timed throws\""))
    }

    @Test
    fun `trackPerformance off records and delivers nothing`() {
        val bodies = initAgainstSamplesServer { trackPerformance = false }

        ForgeOpsTracker.recordPerformance("never recorded", 10.0)
        ForgeOpsTracker.flushPerformance()

        assertEquals(null, bodies.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `recordPerformance does nothing when reporting is not enabled for this environment`() {
        val bodies = initAgainstSamplesServer { environment = "development" }

        ForgeOpsTracker.recordPerformance("never recorded", 10.0)
        ForgeOpsTracker.flushPerformance()

        assertEquals(null, bodies.poll(300, TimeUnit.MILLISECONDS))
    }
}

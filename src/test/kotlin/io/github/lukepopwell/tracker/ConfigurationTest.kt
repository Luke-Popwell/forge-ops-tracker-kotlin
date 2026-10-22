package io.github.lukepopwell.tracker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

class ConfigurationTest {
    private val developmentWarning = "Not sending: this environment is \"development\", and only production, staging " +
        "are enabled. Set FORGE_OPS_ENVIRONMENT=production (or add \"development\" to the enabled environments) " +
        "to send from here."

    private fun developmentConfig() = Configuration().apply {
        dsn = "https://abc123@example.com/api/v1/events"
        environment = "development"
    }

    @Test
    fun `environment defaults to production when nothing is set`() {
        assertEquals("production", Configuration.resolveEnvironment { null })
        assertEquals("production", Configuration.resolveEnvironment { "  " })
    }

    @Test
    fun `environment comes from FORGE_OPS_ENVIRONMENT`() {
        assertEquals("development", Configuration.resolveEnvironment(mapOf("FORGE_OPS_ENVIRONMENT" to "development")::get))
    }

    @Test
    fun `spring profiles are not an environment`() {
        assertEquals("production", Configuration.resolveEnvironment(mapOf("SPRING_PROFILES_ACTIVE" to "dev")::get))
    }

    @Test
    fun `not sending warning explains a disabled environment`() {
        assertEquals(developmentWarning, developmentConfig().notSendingWarning)
    }

    @Test
    fun `no not sending warning when enabled or unconfigured`() {
        val config = developmentConfig()
        config.environment = "staging"
        assertNull(config.notSendingWarning)

        config.environment = "development"
        config.dsn = null
        assertNull(config.notSendingWarning)

        config.dsn = "https://abc123@example.com/api/v1/events"
        config.enabledEnvironments = setOf("production", "development")
        assertNull(config.notSendingWarning)
    }

    @Test
    fun `warnIfNotSending prints to stderr once`() {
        val bytes = ByteArrayOutputStream()
        val err = PrintStream(bytes, true, Charsets.UTF_8)
        val warned = AtomicBoolean()

        ForgeOpsTracker.warnIfNotSending(developmentConfig(), warned, err)
        ForgeOpsTracker.warnIfNotSending(developmentConfig(), warned, err)

        assertEquals("[ForgeOps] $developmentWarning${System.lineSeparator()}", bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun `warnIfNotSending uses the configured logger`() {
        val logged = mutableListOf<String>()
        val config = developmentConfig().apply { logger = { logged.add(it) } }
        val bytes = ByteArrayOutputStream()

        ForgeOpsTracker.warnIfNotSending(config, AtomicBoolean(), PrintStream(bytes, true, Charsets.UTF_8))

        assertEquals(listOf("[ForgeOpsTracker] $developmentWarning"), logged)
        assertEquals(0, bytes.size())
    }

    @Test
    fun `warnIfNotSending is silent without a DSN`() {
        val bytes = ByteArrayOutputStream()
        val warned = AtomicBoolean()

        ForgeOpsTracker.warnIfNotSending(developmentConfig().apply { dsn = null }, warned, PrintStream(bytes, true, Charsets.UTF_8))

        assertEquals(0, bytes.size())
        assertFalse(warned.get())
    }
    @Test
    fun `defaults`() {
        val config = Configuration()
        assertEquals(1000, config.queueSize)
        assertEquals(Duration.ofSeconds(2), config.connectTimeout)
        assertEquals(Duration.ofSeconds(2), config.readTimeout)
        assertTrue(config.scrubPii)
        assertTrue(config.captureSourceContext)
        assertTrue(config.enabledEnvironments.contains("production"))
        assertTrue(config.enabledEnvironments.contains("staging"))
        assertTrue(config.propagateTraces)
        assertNull(config.tracePropagationTargets)
    }

    @Test
    fun `trace propagation goes to every host by default`() {
        val config = Configuration()
        assertTrue(config.shouldPropagateTrace("api.example.com"))
        assertTrue(config.shouldPropagateTrace(null))
    }

    @Test
    fun `trace propagation targets match a host or its subdomains on a dot boundary, and regexes`() {
        val config = Configuration()
        config.tracePropagationTargets = listOf("example.com", ".internal.example", Regex("^10\\.0\\."), java.util.regex.Pattern.compile("corp$"))

        assertTrue(config.shouldPropagateTrace("example.com"))
        assertTrue(config.shouldPropagateTrace("api.example.com"))
        assertTrue(config.shouldPropagateTrace("API.Example.COM"))
        assertFalse(config.shouldPropagateTrace("badexample.com"))
        assertFalse(config.shouldPropagateTrace("example.com.evil.net"))
        assertTrue(config.shouldPropagateTrace("orders.internal.example"))
        assertTrue(config.shouldPropagateTrace("10.0.3.4"))
        assertFalse(config.shouldPropagateTrace("110.0.3.4"))
        assertTrue(config.shouldPropagateTrace("billing.corp"))
        assertFalse(config.shouldPropagateTrace(null))
    }

    @Test
    fun `an empty target list or propagateTraces off sends the header nowhere`() {
        val config = Configuration()
        config.tracePropagationTargets = emptyList()
        assertFalse(config.shouldPropagateTrace("api.example.com"))

        config.tracePropagationTargets = null
        config.propagateTraces = false
        assertFalse(config.shouldPropagateTrace("api.example.com"))
    }

    @Test
    fun `apiKey and ingestionUri`() {
        val config = Configuration()
        config.dsn = "https://abc123@forgeops.example/api/v1/events"

        assertEquals("abc123", config.apiKey)
        assertEquals("https://forgeops.example/api/v1/events", config.ingestionUri.toString())
    }

    @Test
    fun `empty or malformed dsn`() {
        for (dsn in listOf("", "not a url")) {
            val config = Configuration()
            config.dsn = dsn
            assertNull(config.apiKey, "dsn = $dsn")
            assertNull(config.ingestionUri, "dsn = $dsn")
        }
    }

    @Test
    fun `dsn with no userinfo has no api key but still has an ingestion uri`() {
        val config = Configuration()
        config.dsn = "https://forgeops.example/no-userinfo"

        assertNull(config.apiKey)
        assertEquals("https://forgeops.example/no-userinfo", config.ingestionUri.toString())
    }

    @Test
    fun `isEnabled requires dsn api key and enabled environment`() {
        val config = Configuration()
        config.dsn = "https://key@host/path"

        config.environment = "production"
        assertTrue(config.isEnabled)

        config.environment = "development"
        assertFalse(config.isEnabled)

        config.environment = "production"
        config.dsn = null
        assertFalse(config.isEnabled)
    }

    @Test
    fun `log calls the configured logger with a prefix`() {
        val config = Configuration()
        var received: String? = null
        config.logger = { received = it }

        config.log("something happened")

        assertEquals("[ForgeOpsTracker] something happened", received)
    }

    @Test
    fun `log does nothing when no logger is configured`() {
        val config = Configuration()
        config.log("should not throw") // no logger set: must not throw
    }
}

package io.github.lukepopwell.tracker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Checks this client against `strict_privacy_vectors.json` (a byte-identical copy of
 * `sdks/strict_privacy_vectors.json`, generated from the server's own `StrictPrivacy`), and that
 * strict privacy strips every free-text field from an event while leaving events exactly as
 * before when it's off.
 */
class StrictPrivacyTest {
    @Suppress("UNCHECKED_CAST")
    private fun vectors(kind: String): List<Map<String, Any?>> {
        val json = javaClass.getResourceAsStream("/strict_privacy_vectors.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        return (JsonReader(json).read() as Map<String, Any?>)[kind] as List<Map<String, Any?>>
    }

    @Test
    fun `normalizes and fingerprints every vector exactly as the server does`() {
        val vectors = vectors("fingerprint")
        assertTrue(vectors.isNotEmpty())
        for (vector in vectors) {
            val message = vector["message"] as String
            assertEquals(vector["normalized"], StrictPrivacy.normalizeMessage(message), message)
            assertEquals(vector["fingerprint"], StrictPrivacy.messageFingerprint(message), message)
        }
    }

    @Test
    fun `an absent message fingerprints as the empty one`() {
        assertEquals(StrictPrivacy.messageFingerprint(""), StrictPrivacy.messageFingerprint(null))
    }

    @Test
    fun `takes ids out of every path vector`() {
        for (vector in vectors("path")) {
            assertEquals(vector["strict"], StrictPrivacy.strictPath(vector["path"] as String), vector["path"] as String)
        }
    }

    @Test
    fun `names every span vector by method and host only`() {
        for (vector in vectors("span_name")) {
            assertEquals(vector["strict"], StrictPrivacy.spanName(vector["name"] as String), vector["name"] as String)
        }
    }

    @Test
    fun `is off by default and read from the environment`() {
        assertEquals(Configuration.resolveStrictPrivacy(System::getenv), Configuration().strictPrivacy)
        assertFalse(Configuration.resolveStrictPrivacy { null })
        assertTrue(Configuration.resolveStrictPrivacy(mapOf("FORGE_OPS_STRICT_PRIVACY" to "true")::get))
        assertTrue(Configuration.resolveStrictPrivacy(mapOf("FORGE_OPS_STRICT_PRIVACY" to "TRUE")::get))
        assertFalse(Configuration.resolveStrictPrivacy(mapOf("FORGE_OPS_STRICT_PRIVACY" to "false")::get))
        assertEquals(emptySet<String>(), Configuration.resolvePrivacyAllowedFields { null })
        assertEquals(
            listOf("error_code", "feature"),
            Configuration.resolvePrivacyAllowedFields(mapOf("FORGE_OPS_PRIVACY_ALLOWED_FIELDS" to "error_code, feature,,")::get).toList(),
        )
    }

    @Test
    fun `an explicit option wins over the environment`() {
        val config = Configuration().apply {
            strictPrivacy = true
            privacyAllowedFields = setOf("error_code")
        }
        assertTrue(config.strictPrivacy)
        assertEquals(setOf("error_code"), config.privacyAllowedFields)
    }

    @Test
    fun `a strict event carries no free text`() {
        val config = newConfiguration().apply {
            strictPrivacy = true
            privacyAllowedFields = setOf("error_code", "retries", "flagged", "nested")
        }
        val builder = CountingEventBuilder(config)

        val payload = build(builder)

        assertEquals("", payload["message"])
        assertEquals(StrictPrivacy.messageFingerprint(SCRUBBED_MESSAGE), payload["message_fingerprint"])
        assertTrue((payload["message_fingerprint"] as String).matches(Regex("[0-9a-f]{32}")))
        assertEquals(mapOf("error_code" to "E42", "retries" to 3, "flagged" to true), payload["context"])
        assertFalse(payload.containsKey("tags"))
        assertEquals(mapOf("id" to "42"), payload["user"])
        assertFalse(payload.containsKey("breadcrumbs"))
        assertFalse(payload.containsKey("endpoint"))
        assertFalse(payload.containsKey("sql_statement"))
        assertEquals(0, builder.reads)
        @Suppress("UNCHECKED_CAST")
        for (frame in payload["backtrace"] as List<Map<String, Any?>>) {
            assertFalse(frame.containsKey("context_line"))
            assertFalse(frame.containsKey("pre_context"))
            assertFalse(frame.containsKey("post_context"))
        }

        // The structured fields stay exactly as they are without strict privacy.
        assertEquals(SqlStatementTest.FakeJdbcException::class.java.name, payload["exception_class"])
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", payload["trace_id"])
        assertEquals("production", payload["environment"])
        assertEquals("1.2.3", payload["release"])
        assertEquals("kotlin", payload["sdk_name"])
        assertTrue(payload.containsKey("sql_objects"))
        assertFalse(Json.encode(payload).contains("jane"))
    }

    @Test
    fun `a strict event leaves out context and user when nothing is allowed`() {
        val config = newConfiguration().apply { strictPrivacy = true }
        val payload = EventBuilder(config).build(RuntimeException("x"), mapOf("error_code" to "E42"), mapOf("email" to "jane@example.com"))
        assertFalse(payload.containsKey("context"))
        assertFalse(payload.containsKey("tags"))
        assertFalse(payload.containsKey("user"))

        val noMessage = EventBuilder(config).build(RuntimeException(), emptyMap())
        assertEquals(StrictPrivacy.messageFingerprint(""), noMessage["message_fingerprint"])
    }

    @Test
    fun `with strict privacy off events are exactly as before`() {
        val builder = CountingEventBuilder(newConfiguration())

        val payload = build(builder)

        assertEquals(
            listOf(
                "exception_class", "message", "backtrace", "occurred_at", "environment", "release", "server_name",
                "context", "tags", "sdk_name", "user", "breadcrumbs", "trace_id", "sql_objects", "sql_statement",
            ),
            payload.keys.toList(),
        )
        assertEquals(SCRUBBED_MESSAGE, payload["message"])
        assertEquals(
            mapOf("error_code" to "E42", "retries" to 3, "flagged" to true, "nested" to mapOf("a" to 1), "note" to "free text"),
            payload["context"],
        )
        assertEquals(emptyMap<String, Any?>(), payload["tags"])
        assertEquals(mapOf("id" to 42, "email" to "jane@example.com", "username" to "jane"), payload["user"])
        assertEquals(1, (payload["breadcrumbs"] as List<*>).size)
        assertEquals("SELECT * FROM patients WHERE id = ?", payload["sql_statement"])
        assertFalse(payload.containsKey("message_fingerprint"))
        assertTrue(builder.reads > 0)
    }

    @Test
    fun `strict privacy records no breadcrumbs`() {
        val config = newConfiguration().apply { strictPrivacy = true }
        val buffer = BreadcrumbBuffer(config)
        buffer.add("looked up jane@example.com", "custom", "info", emptyMap())
        assertEquals(emptyList<Map<String, Any?>>(), buffer.all())

        config.strictPrivacy = false
        buffer.add("looked up", "custom", "info", emptyMap())
        assertEquals(1, buffer.all().size)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `strict spans keep only method host and database data`() {
        val config = newConfiguration().apply { strictPrivacy = true }
        val buffer = SpanBuffer(config)
        buffer.recordLeaf(
            "GET https://api.example.com/patients/42?x=1", "http", Instant.now(), 5.0,
            mapOf("url" to "https://api.example.com/patients/42", "status" to 200),
        )
        buffer.recordLeaf(
            "Load patient", "database", Instant.now(), 5.0,
            ForgeOpsTracker.spanData("database", mapOf("rows" to 1), "SELECT * FROM patients WHERE name = 'jane'", "postgresql"),
        )
        val spans = buffer.finishTrace("GET /patients/42", Instant.now(), 5000.0)!!["spans"] as List<Map<String, Any?>>

        assertEquals("GET /patients/:path", spans[0]["name"])
        assertEquals(emptyMap<String, Any?>(), spans[0]["data"])
        assertEquals("GET api.example.com", spans[1]["name"])
        assertEquals(emptyMap<String, Any?>(), spans[1]["data"])
        assertEquals("Load patient", spans[2]["name"])
        assertEquals(mapOf("db.statement" to "SELECT * FROM patients WHERE name = ?", "db.system" to "postgresql"), spans[2]["data"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `spans are unchanged with strict privacy off`() {
        val buffer = SpanBuffer(newConfiguration())
        buffer.recordLeaf("GET https://api.example.com/patients/42", "http", Instant.now(), 5.0, mapOf("status" to 200))
        val spans = buffer.finishTrace("GET /patients/42", Instant.now(), 5000.0)!!["spans"] as List<Map<String, Any?>>
        assertEquals("GET /patients/42", spans[0]["name"])
        assertEquals("GET https://api.example.com/patients/42", spans[1]["name"])
        assertEquals(mapOf("status" to 200), spans[1]["data"])
    }

    private fun newConfiguration(): Configuration = Configuration().apply {
        environment = "production"
        release = "1.2.3"
        serverName = "web-1"
        strictPrivacy = false
        privacyAllowedFields = emptySet()
        captureSqlStatement = true
        appPackagePrefix = "io.github.lukepopwell"
    }

    private fun build(builder: EventBuilder): Map<String, Any?> = builder.build(
        SqlStatementTest.FakeJdbcException(EMAIL_MESSAGE, "SELECT * FROM patients WHERE id = 8821"),
        linkedMapOf("error_code" to "E42", "retries" to 3, "flagged" to true, "nested" to mapOf("a" to 1), "note" to "free text"),
        linkedMapOf("id" to 42, "email" to "jane@example.com", "username" to "jane"),
        listOf(mapOf("category" to "custom", "message" to "opened jane's chart", "level" to "info")),
        "4bf92f3577b34da6a3ce929d0e0e4736",
    )

    /** Counts source reads, and pretends every file has enough lines, so source context would be attached. */
    private class CountingEventBuilder(configuration: Configuration) : EventBuilder(configuration) {
        var reads = 0

        override fun readSourceLines(file: String): List<String> {
            reads++
            return List(5000) { "line $it" }
        }
    }

    /** Just enough JSON for the vectors files: objects, arrays, strings and the escapes they use,
     * and booleans. Also read by [PiiScrubberTest]. */
    internal class JsonReader(private val json: String) {
        private var at = 0

        fun read(): Any? {
            skipWhitespace()
            return when (json[at]) {
                '{' -> {
                    val result = linkedMapOf<String, Any?>()
                    at++
                    skipWhitespace()
                    if (json[at] == '}') {
                        at++
                    } else {
                        while (true) {
                            skipWhitespace()
                            val key = read() as String
                            skipWhitespace()
                            at++ // ':'
                            result[key] = read()
                            skipWhitespace()
                            if (json[at++] == '}') break
                        }
                    }
                    result
                }
                '[' -> {
                    val result = mutableListOf<Any?>()
                    at++
                    skipWhitespace()
                    if (json[at] == ']') {
                        at++
                    } else {
                        while (true) {
                            result.add(read())
                            skipWhitespace()
                            if (json[at++] == ']') break
                        }
                    }
                    result
                }
                '"' -> {
                    val out = StringBuilder()
                    at++
                    while (json[at] != '"') {
                        val next = json[at++]
                        if (next != '\\') {
                            out.append(next)
                            continue
                        }
                        when (val escape = json[at++]) {
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'r' -> out.append('\r')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'u' -> {
                                out.append(json.substring(at, at + 4).toInt(16).toChar())
                                at += 4
                            }
                            else -> out.append(escape)
                        }
                    }
                    at++
                    out.toString()
                }
                't' -> {
                    at += 4
                    true
                }
                'f' -> {
                    at += 5
                    false
                }
                else -> throw IllegalArgumentException("unexpected JSON at $at")
            }
        }

        private fun skipWhitespace() {
            while (at < json.length && json[at].isWhitespace()) at++
        }
    }

    private companion object {
        const val EMAIL_MESSAGE = "Couldn't find patient jane@example.com with id=8821"

        // The fingerprint is taken from the message as it would otherwise have been sent: scrubbed.
        const val SCRUBBED_MESSAGE = "Couldn't find patient [EMAIL FILTERED] with id=8821"
    }
}

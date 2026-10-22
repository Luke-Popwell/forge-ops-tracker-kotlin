package io.github.lukepopwell.tracker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class EventBuilderTest {
    @TempDir
    lateinit var tempDir: Path

    private fun newConfiguration(): Configuration = Configuration().apply {
        environment = "production"
        release = "abc123"
        serverName = "web-1"
        appPackagePrefix = "io.github.lukepopwell.tracker"
    }

    @Test
    fun `builds payload with exception class message and configured metadata`() {
        val builder = EventBuilder(newConfiguration())
        val error = IllegalStateException("boom")

        val payload = builder.build(error, mapOf("url" to "https://example.com"))

        assertEquals("java.lang.IllegalStateException", payload["exception_class"])
        assertEquals("boom", payload["message"])
        assertEquals("production", payload["environment"])
        assertEquals("abc123", payload["release"])
        assertEquals("web-1", payload["server_name"])
        assertEquals(mapOf("url" to "https://example.com"), payload["context"])
        assertTrue(payload["occurred_at"] is String)
        assertEquals("kotlin", payload["sdk_name"])
        assertFalse(payload.containsKey("trace_id"))
    }

    @Test
    fun `includes a top-level trace_id when given one`() {
        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        val payload = EventBuilder(newConfiguration()).build(IllegalStateException("boom"), null, traceId = traceId)
        assertEquals(traceId, payload["trace_id"])
    }

    @Test
    fun `parses real stack frames with class method and line`() {
        val builder = EventBuilder(newConfiguration())
        val error = IllegalStateException("boom")

        @Suppress("UNCHECKED_CAST")
        val frames = builder.build(error, null)["backtrace"] as List<Map<String, Any?>>

        assertTrue(frames.isNotEmpty())
        val frame = frames.first()
        assertTrue(frame["file"] is String)
        assertTrue(frame["line"] is Int)
        assertTrue((frame["method"] as String).contains("#"))
    }

    @Test
    fun `marks a frame from this package as in_app when the prefix matches`() {
        val builder = EventBuilder(newConfiguration())
        val error = IllegalStateException("boom")

        @Suppress("UNCHECKED_CAST")
        val frames = builder.build(error, null)["backtrace"] as List<Map<String, Any?>>

        assertTrue(frames.any { it["in_app"] == true }, "the frame that threw should be in this test's own package")
    }

    @Test
    fun `no frame is in_app when appPackagePrefix is unset`() {
        val config = newConfiguration().apply { appPackagePrefix = null }
        val builder = EventBuilder(config)
        val error = IllegalStateException("boom")

        @Suppress("UNCHECKED_CAST")
        val frames = builder.build(error, null)["backtrace"] as List<Map<String, Any?>>

        assertTrue(frames.none { it["in_app"] == true })
    }

    @Test
    fun `scrubs likely pii out of message and context by default`() {
        val builder = EventBuilder(newConfiguration())
        val error = IllegalStateException("failed for user@example.com")

        val payload = builder.build(error, mapOf("user" to mapOf("email" to "ada@example.com", "password" to "hunter2")))

        assertEquals("failed for [EMAIL FILTERED]", payload["message"])
        assertEquals(mapOf("user" to mapOf("email" to "[EMAIL FILTERED]", "password" to PiiScrubber.REDACTED)), payload["context"])
    }

    @Test
    fun `leaves payload untouched when scrubPii is disabled`() {
        val config = newConfiguration().apply { scrubPii = false }
        val builder = EventBuilder(config)
        val error = IllegalStateException("failed for user@example.com")

        val payload = builder.build(error, mapOf("email" to "ada@example.com"))

        assertEquals("failed for user@example.com", payload["message"])
        assertEquals(mapOf("email" to "ada@example.com"), payload["context"])
    }

    @Test
    fun `includes the user when given one, never scrubbed even though it's an email`() {
        val builder = EventBuilder(newConfiguration())
        val error = IllegalStateException("boom")

        val payload = builder.build(error, emptyMap(), mapOf("id" to 42, "email" to "ada@example.com"))

        assertEquals(mapOf("id" to 42, "email" to "ada@example.com"), payload["user"])
    }

    @Test
    fun `omits the user key entirely when none was given`() {
        val builder = EventBuilder(newConfiguration())
        val error = IllegalStateException("boom")

        val payload = builder.build(error, emptyMap())

        assertFalse(payload.containsKey("user"))
    }

    @Test
    fun `includes breadcrumbs when given`() {
        val builder = EventBuilder(newConfiguration())
        val error = IllegalStateException("boom")
        val crumb = mapOf(
            "category" to "controller",
            "message" to "GET /orders/42",
            "level" to "info",
            "timestamp" to "2024-01-15T10:29:58Z",
            "data" to mapOf("status" to 200),
        )

        val payload = builder.build(error, emptyMap(), null, listOf(crumb))

        assertEquals(listOf(crumb), payload["breadcrumbs"])
    }

    @Test
    fun `omits the breadcrumbs key entirely when none were given`() {
        val builder = EventBuilder(newConfiguration())
        val error = IllegalStateException("boom")

        val payload = builder.build(error, emptyMap())

        assertFalse(payload.containsKey("breadcrumbs"))
    }

    @Test
    fun `scrubs breadcrumb message and data but leaves category level and timestamp untouched`() {
        val builder = EventBuilder(newConfiguration())
        val error = IllegalStateException("boom")
        val crumb = mapOf(
            "category" to "custom",
            "message" to "emailed alice@example.com",
            "level" to "info",
            "timestamp" to "2024-01-15T10:29:58Z",
            "data" to mapOf("email" to "alice@example.com", "password" to "hunter2"),
        )

        val payload = builder.build(error, emptyMap(), null, listOf(crumb))

        @Suppress("UNCHECKED_CAST")
        val scrubbed = (payload["breadcrumbs"] as List<Map<String, Any?>>)[0]
        assertFalse((scrubbed["message"] as String).contains("alice@example.com"))
        assertEquals("custom", scrubbed["category"])
        assertEquals("info", scrubbed["level"])
        assertEquals("2024-01-15T10:29:58Z", scrubbed["timestamp"])
        @Suppress("UNCHECKED_CAST")
        val data = scrubbed["data"] as Map<String, Any?>
        assertFalse(data["password"].toString().contains("hunter2"))
    }

    // --- source context capture -------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun frameFor(builder: EventBuilder, file: Path, line: Int): Map<String, Any?> {
        val error = IllegalStateException("boom")
        error.stackTrace = arrayOf(
            StackTraceElement("io.github.lukepopwell.tracker.SomeClass", "call", file.toString(), line),
        )
        val frames = builder.build(error, null)["backtrace"] as List<Map<String, Any?>>
        return frames.first()
    }

    private fun writeSourceFile(lineCount: Int): Path {
        val file = Files.createTempFile(tempDir, "app_file", ".kt")
        Files.writeString(file, (1..lineCount).joinToString("\n") { "line $it" })
        return file
    }

    private fun linesInRange(from: Int, to: Int): List<String> = (from..to).map { "line $it" }

    private class RecordingEventBuilder(configuration: Configuration) : EventBuilder(configuration) {
        var reads = 0

        override fun readSourceLines(file: String): List<String> {
            reads++
            return super.readSourceLines(file)
        }
    }

    @Test
    fun `attaches context_line pre_context and post_context around an in-app frame's culprit line by default`() {
        val file = writeSourceFile(20)
        val builder = EventBuilder(newConfiguration())

        val frame = frameFor(builder, file, 10)

        assertEquals("line 10", frame["context_line"])
        assertEquals(linesInRange(5, 9), frame["pre_context"])
        assertEquals(linesInRange(11, 15), frame["post_context"])
    }

    @Test
    fun `clamps pre_context and post_context at the start and end of the file rather than failing`() {
        val file = writeSourceFile(3)
        val builder = EventBuilder(newConfiguration())

        val firstFrame = frameFor(builder, file, 1)
        val lastFrame = frameFor(builder, file, 3)

        assertEquals(emptyList<String>(), firstFrame["pre_context"])
        assertEquals(listOf("line 2", "line 3"), firstFrame["post_context"])
        assertEquals(listOf("line 1", "line 2"), lastFrame["pre_context"])
        assertEquals(emptyList<String>(), lastFrame["post_context"])
    }

    @Test
    fun `truncates an individual line longer than MAX_CONTEXT_LINE_LENGTH`() {
        val file = Files.createTempFile(tempDir, "app_file", ".kt")
        Files.writeString(file, "x".repeat(600))
        val builder = EventBuilder(newConfiguration())

        val frame = frameFor(builder, file, 1)

        assertEquals("${"x".repeat(500)}...", frame["context_line"])
    }

    @Test
    fun `never attaches context to a frame that is not in_app`() {
        val file = writeSourceFile(20)
        val config = newConfiguration().apply { appPackagePrefix = null }
        val builder = EventBuilder(config)

        val frame = frameFor(builder, file, 10)

        assertFalse(frame.containsKey("context_line"))
        assertFalse(frame.containsKey("pre_context"))
        assertFalse(frame.containsKey("post_context"))
    }

    @Test
    fun `leaves the frame untouched with no file read attempted when captureSourceContext is disabled`() {
        val file = writeSourceFile(20)
        val config = newConfiguration().apply { captureSourceContext = false }
        val builder = RecordingEventBuilder(config)

        val frame = frameFor(builder, file, 10)

        assertFalse(frame.containsKey("context_line"))
        assertEquals(0, builder.reads)
    }

    @Test
    fun `leaves the frame untouched when the recorded file can't be read`() {
        val builder = EventBuilder(newConfiguration())
        val missing = tempDir.resolve("does-not-exist-${UUID.randomUUID()}.kt")

        val frame = frameFor(builder, missing, 1)

        assertFalse(frame.containsKey("context_line"))
    }
}

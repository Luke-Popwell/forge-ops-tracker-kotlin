package io.github.lukepopwell.tracker

import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Turns a caught [Throwable] into the JSON-able payload shape the ingestion API expects. Mirrors
 * `sdks/java`'s own `EventBuilder`: a Kotlin exception is a JVM `Throwable` under the hood, so
 * backtrace parsing is the same structured [Throwable.getStackTrace] this repo's Java client
 * already uses (not a regex over a string, the situation the Ruby/PHP/Node clients are in). Also
 * verified there directly, and still true here: the JVM fills in a `Throwable`'s stack trace at
 * construction time, not at `throw` time, so there's no "empty backtrace when never thrown" case.
 */
open class EventBuilder(private val configuration: Configuration) {
    private val timestampFormat = DateTimeFormatter.ISO_INSTANT

    fun build(
        error: Throwable,
        context: Map<String, Any?>?,
        user: Map<String, Any?>? = null,
        breadcrumbs: List<Map<String, Any?>> = emptyList(),
        traceId: String? = null,
    ): Map<String, Any?> {
        if (configuration.strictPrivacy) return strictBuild(error, context, user, traceId)
        val payload = linkedMapOf<String, Any?>(
            "exception_class" to error.javaClass.name,
            "message" to error.message,
            "backtrace" to backtraceFrames(error),
            "occurred_at" to timestampFormat.format(Instant.now()),
            "environment" to configuration.environment,
            "release" to configuration.release,
            "server_name" to configuration.serverName,
            "context" to (context ?: emptyMap()),
            "tags" to emptyMap<String, Any?>(),
            "sdk_name" to SDK_NAME,
        )
        if (!user.isNullOrEmpty()) {
            payload["user"] = user
        }
        // Omitted entirely rather than an empty list, the same "no key at all" shape user above
        // already gets when absent: matches gems/forge_ops_tracker's own
        // `if breadcrumbs && !breadcrumbs.empty?`.
        if (breadcrumbs.isNotEmpty()) {
            payload["breadcrumbs"] = breadcrumbs
        }
        // The open trace's W3C id, so the server can link this error to errors in other services
        // that handled the same request. Left out entirely outside a trace.
        if (traceId != null) {
            payload["trace_id"] = traceId
        }
        attachSql(payload, error)
        return scrub(payload)
    }

    /**
     * The same event under [Configuration.strictPrivacy]: no message (only its fingerprint), no
     * breadcrumbs, statement or source context, the user's id only, and context/tags reduced to
     * the allowed keys, each left out entirely when nothing is left.
     */
    private fun strictBuild(error: Throwable, context: Map<String, Any?>?, user: Map<String, Any?>?, traceId: String?): Map<String, Any?> {
        val allowedFields = configuration.privacyAllowedFields
        val payload = linkedMapOf<String, Any?>(
            "exception_class" to error.javaClass.name,
            "message" to "",
            // Taken from the message as it would otherwise have been sent (scrubbed, when scrubbing
            // is on), so it matches what the server fingerprints from a client without strict privacy.
            "message_fingerprint" to StrictPrivacy.messageFingerprint(
                error.message?.let { if (configuration.scrubPii) PiiScrubber.scrub(it) as String else it },
            ),
            "backtrace" to backtraceFrames(error),
            "occurred_at" to timestampFormat.format(Instant.now()),
            "environment" to configuration.environment,
            "release" to configuration.release,
            "server_name" to configuration.serverName,
        )
        StrictPrivacy.allowed(context, allowedFields)?.let { payload["context"] = it }
        StrictPrivacy.allowed(emptyMap(), allowedFields)?.let { payload["tags"] = it }
        payload["sdk_name"] = SDK_NAME
        StrictPrivacy.user(user)?.let { payload["user"] = it }
        if (traceId != null) payload["trace_id"] = traceId
        attachSql(payload, error)
        return scrub(payload)
    }

    // See SqlStatement for what's read off the error and how it's masked. The statement itself only
    // goes out when captureSqlStatement is on (and strict privacy is off); the extracted names go out on their own
    // (captureSqlObjects) so an issue can still name the procedure or view involved.
    private fun attachSql(payload: MutableMap<String, Any?>, error: Throwable) {
        if (!configuration.captureSqlObjects && !configuration.captureSqlStatement) return

        val masked = SqlStatement.mask(SqlStatement.findIn(error)) ?: return
        val objects = SqlStatement.objects(masked)
        if (objects != null && configuration.captureSqlObjects) payload["sql_objects"] = objects
        if (configuration.captureSqlStatement && !configuration.strictPrivacy) payload["sql_statement"] = masked
    }

    // exception_class/occurred_at/environment/release/server_name/sdk_name/user/trace_id are left alone:
    // structured fields this client or the host app sets deliberately, not free text an
    // exception or its context could accidentally spill sensitive data into. user specifically
    // is a deliberate exemption, not an oversight: PiiScrubber's own email pattern would
    // otherwise redact the exact thing this field exists to carry.
    private fun scrub(payload: Map<String, Any?>): Map<String, Any?> {
        if (!configuration.scrubPii) return payload
        return payload.toMutableMap().apply {
            this["message"] = PiiScrubber.scrub(payload["message"])
            this["backtrace"] = PiiScrubber.scrub(payload["backtrace"])
            // Under strict privacy context and tags are left out when nothing allowed is left in them.
            if (payload.containsKey("context")) this["context"] = PiiScrubber.scrub(payload["context"])
            if (payload.containsKey("tags")) this["tags"] = PiiScrubber.scrub(payload["tags"])
            if (payload.containsKey("sql_statement")) this["sql_statement"] = PiiScrubber.scrub(payload["sql_statement"])
            // category/level/timestamp are left alone, the same "structured fields this client
            // sets deliberately, not free text" exemption exception_class/environment/etc.
            // already get above: only message (arbitrary text) and data (arbitrary
            // caller-supplied values, the same shape context already is) can carry anything
            // worth scrubbing.
            @Suppress("UNCHECKED_CAST")
            (payload["breadcrumbs"] as? List<Map<String, Any?>>)?.let { breadcrumbs ->
                this["breadcrumbs"] = breadcrumbs.map { crumb ->
                    crumb.toMutableMap().apply {
                        this["message"] = PiiScrubber.scrub(crumb["message"])
                        this["data"] = PiiScrubber.scrub(crumb["data"])
                    }
                }
            }
        }
    }

    private fun backtraceFrames(error: Throwable): List<Map<String, Any?>> =
        error.stackTrace.take(MAX_FRAMES).map(::frame)

    private fun frame(element: StackTraceElement): Map<String, Any?> {
        val file = element.fileName
        val inApp = isInApp(element)
        val base = mapOf(
            "file" to file,
            "line" to element.lineNumber,
            "method" to "${element.className}#${element.methodName}",
            "in_app" to inApp,
        )
        if (!configuration.captureSourceContext || configuration.strictPrivacy || !inApp) return base
        return attachSourceContext(base, file, element.lineNumber)
    }

    /**
     * Reads a few lines of source straight off disk around the culprit line, at exactly the path
     * recorded on the frame, and adds `context_line`/`pre_context`/`post_context` to it. Only
     * ever called for an in-app frame with [Configuration.captureSourceContext] on (see [frame]):
     * never a third-party dependency, and never at all when the flag is off. Best-effort: any
     * failure (the file doesn't exist, isn't readable, or the path doesn't resolve: the
     * expected case for most production deployments of a compiled language, which ship only the
     * compiled `.class`/`.jar`, not the original `.kt` files) just leaves the frame without
     * context, never a reason to fail building the whole event.
     */
    private fun attachSourceContext(frame: Map<String, Any?>, file: String?, line: Int): Map<String, Any?> {
        if (file == null) return frame
        return try {
            val lines = readSourceLines(file)
            val index = line - 1
            if (index !in lines.indices) return frame

            val from = maxOf(index - CONTEXT_LINES, 0)
            val to = minOf(index + CONTEXT_LINES, lines.size - 1)
            frame + mapOf(
                "context_line" to truncateLine(lines[index]),
                "pre_context" to lines.subList(from, index).map(::truncateLine),
                "post_context" to lines.subList(index + 1, to + 1).map(::truncateLine),
            )
        } catch (e: IOException) {
            frame
        } catch (e: SecurityException) {
            frame
        }
    }

    /**
     * Broken out as its own overridable function purely so a test can substitute a counting
     * subclass to prove zero disk reads happen at all when `captureSourceContext` is gated off,
     * the same subclassing seam `sdks/java`'s own `EventBuilderTest.RecordingEventBuilder` uses.
     */
    protected open fun readSourceLines(file: String): List<String> = File(file).readLines()

    private fun truncateLine(line: String): String {
        if (line.length <= MAX_CONTEXT_LINE_LENGTH) return line
        return "${line.take(MAX_CONTEXT_LINE_LENGTH)}..."
    }

    /**
     * [StackTraceElement.getFileName] is always a bare file name (`"Foo.kt"`), never a full path:
     * so file-path matching (the approach the Ruby/Python/PHP/Node clients all use) isn't
     * possible here, same as `sdks/java`. This compares the frame's declaring class package
     * against a configured prefix instead.
     */
    private fun isInApp(element: StackTraceElement): Boolean {
        val prefix = configuration.appPackagePrefix
        if (prefix.isNullOrBlank()) return false
        return element.className.startsWith(prefix)
    }

    private companion object {
        const val MAX_FRAMES = 500

        // How many lines of source to grab on either side of the culprit line (see
        // attachSourceContext), and the longest a single captured line is allowed to be before
        // getting truncated: guards against a single pathological minified/generated line
        // ballooning the payload. ForgeOps itself re-truncates on arrival too, the same "don't
        // just trust the SDK" posture MAX_FRAMES already gets on the server side.
        const val CONTEXT_LINES = 5
        const val MAX_CONTEXT_LINE_LENGTH = 500

        // Identifies this client to the server's auto language-detection on the project the
        // event lands in (see Project#note_sdk_platform server-side); matches this repo's own
        // sdks/kotlin directory name, the same convention every other language's client follows.
        const val SDK_NAME = "kotlin"
    }
}

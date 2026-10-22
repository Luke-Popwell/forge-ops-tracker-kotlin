package io.github.lukepopwell.tracker

import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * One trace's worth of spans, sharing a single trace id. Held one-per-thread by
 * [ForgeOpsTracker], the same isolation boundary [BreadcrumbBuffer] uses, so only the owning
 * thread ever touches it and no locking is needed. Nesting comes from a stack of open span ids:
 * a span started while another is open becomes its child, and anything else parents under the root.
 *
 * [traceId] and [remoteParentSpanId] come from an incoming `traceparent` header when this trace
 * continues another service's (see [TraceParent]); the root span then points at that remote span,
 * which the server nests it under even though it arrives in a different upload.
 */
class SpanBuffer @JvmOverloads constructor(
    private val configuration: Configuration,
    val traceId: String = TraceParent.generateTraceId(),
    private val remoteParentSpanId: String? = null,
) {
    private val rootSpanId: String = TraceParent.generateSpanId()
    private val spans = ArrayList<Map<String, Any?>>()
    private val openSpanIds = ArrayDeque<String>()

    /** Opens a span and returns its id; pair with [finish]. */
    fun open(): String = open(TraceParent.generateSpanId())

    /** Opens a span under an id generated beforehand, as [ForgeOpsTracker.httpSpan] does so its header can name it. */
    fun open(id: String): String = id.also { openSpanIds.addLast(it) }

    fun finish(id: String, name: String, kind: String, startedAt: Instant, durationMs: Double, data: Map<String, Any?>) {
        openSpanIds.remove(id)
        record(id, name, kind, startedAt, durationMs, data)
    }

    /** Records an already-finished span as a child of whatever is currently open. */
    fun recordLeaf(name: String, kind: String, startedAt: Instant, durationMs: Double, data: Map<String, Any?>) {
        record(TraceParent.generateSpanId(), name, kind, startedAt, durationMs, data)
    }

    private fun record(id: String, name: String, kind: String, startedAt: Instant, durationMs: Double, data: Map<String, Any?>) {
        if (spans.size >= MAX_SPANS - 1) return // leave room for the root
        spans.add(build(id, openSpanIds.lastOrNull() ?: rootSpanId, name, kind, startedAt, durationMs, data))
    }

    // Strict privacy: an outbound call is named by its method and host only, never a path that could
    // name a person, and data keeps only the masked statement and the database system.
    private fun build(id: String, parentId: String?, name: String, kind: String, startedAt: Instant, durationMs: Double, data: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf(
            "span_id" to id,
            "parent_span_id" to parentId,
            "name" to if (configuration.strictPrivacy) StrictPrivacy.spanName(name) else name,
            "kind" to if (kind in KINDS) kind else "other",
            "started_at" to TIMESTAMP_FORMAT.format(startedAt),
            "duration_ms" to Math.round(durationMs * 100.0) / 100.0,
            "environment" to configuration.environment,
            "release" to configuration.release,
            "data" to if (configuration.strictPrivacy) StrictPrivacy.spanData(data) else data,
        )

    /**
     * The wire payload once the trace is over: the root span plus everything recorded beneath it,
     * or `null` when the root was faster than [Configuration.traceCaptureThreshold].
     */
    fun finishTrace(rootName: String, startedAt: Instant, durationMs: Double): Map<String, Any?>? {
        if (durationMs < configuration.traceCaptureThreshold.toNanos() / 1_000_000.0) return null
        val all = ArrayList<Map<String, Any?>>(spans.size + 1)
        all.add(build(rootSpanId, remoteParentSpanId, rootName, "controller", startedAt, durationMs, emptyMap()))
        all.addAll(spans)
        return linkedMapOf("trace_id" to traceId, "spans" to all)
    }

    companion object {
        const val MAX_SPANS = 500

        // The kinds the ingestion API accepts; anything else would fail validation for the whole trace.
        private val KINDS = setOf("controller", "service", "database", "redis", "http", "job", "other")
        private val RANDOM = SecureRandom()
        private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        fun randomHex(bytes: Int): String {
            val raw = ByteArray(bytes).also { RANDOM.nextBytes(it) }
            return raw.joinToString("") { "%02x".format(it) }
        }
    }
}

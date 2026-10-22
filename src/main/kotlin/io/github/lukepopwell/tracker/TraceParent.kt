package io.github.lukepopwell.tracker

import java.security.SecureRandom

/**
 * Reads and writes the W3C Trace Context `traceparent` header (https://www.w3.org/TR/trace-context/),
 * the vendor-neutral format for carrying one trace across service boundaries:
 * `00-<32 hex trace id>-<16 hex parent span id>-<2 hex flags>`. Mirrors
 * `gems/forge_ops_tracker`'s own `TraceParent`: [parse] lets [ForgeOpsTracker.trace] continue a
 * caller's trace, and [build] is what [ForgeOpsTracker.httpSpan] sends to the next service along.
 *
 * Strict on the way in, the posture the spec asks receivers to take: a malformed value, uppercase
 * hex, the reserved version `ff`, or an all-zero trace or parent id all mean "no usable header"
 * ([parse] returns `null` and a fresh trace starts), never half-trusted. A future version is still
 * accepted when its first four fields have version 00's shape; version 00 itself must have exactly
 * four.
 */
object TraceParent {
    const val HEADER = "traceparent"

    /** A usable incoming header's two ids: the caller's trace, and the caller's span that this work runs under. */
    data class Context(val traceId: String, val parentSpanId: String)

    private val PATTERN = Regex("([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})(-.*)?")
    private val INVALID_TRACE_ID = "0".repeat(32)
    private val INVALID_SPAN_ID = "0".repeat(16)

    // Always "01" (sampled) on the way out: whether a trace is sent is only decided once it's over
    // (see Configuration.traceCaptureThreshold), long after this header has gone out, so "this may
    // be recorded" is the only honest answer. The next service makes its own decision either way.
    private const val SAMPLED_FLAGS = "01"

    private val RANDOM = SecureRandom()

    fun parse(value: String?): Context? {
        val match = PATTERN.matchEntire(value?.trim() ?: return null) ?: return null
        val (version, traceId, parentId) = match.destructured
        val rest = match.groupValues[5]
        if (version == "ff") return null
        if (version == "00" && rest.isNotEmpty()) return null
        if (traceId == INVALID_TRACE_ID || parentId == INVALID_SPAN_ID) return null
        return Context(traceId, parentId)
    }

    fun build(traceId: String, spanId: String): String = "00-$traceId-$spanId-$SAMPLED_FLAGS"

    /** 32 lowercase hex characters, never all zeros (the spec's one invalid value). */
    fun generateTraceId(): String = randomNonZeroHex(16)

    /** 16 lowercase hex characters, never all zeros. */
    fun generateSpanId(): String = randomNonZeroHex(8)

    private fun randomNonZeroHex(bytes: Int): String {
        val raw = ByteArray(bytes)
        do RANDOM.nextBytes(raw) while (raw.all { it == 0.toByte() })
        return raw.joinToString("") { "%02x".format(it) }
    }
}

package io.github.lukepopwell.tracker

import java.time.Instant

/**
 * Builds the `POST /api/v1/changes` body for [ForgeOpsTracker.recordChange]. Separate from the
 * facade so the payload shape can be tested without a network round trip.
 */
object Change {
    /** The kinds the changes endpoint accepts; anything else is sent as `"other"` rather than rejected. */
    val KINDS: Set<String> = setOf("feature_flag", "config", "migration", "dependency", "infrastructure", "other")

    /** The server's own limit on a title; a longer one is truncated here instead of rejected there. */
    const val MAX_TITLE_LENGTH: Int = 200

    fun normalizeKind(kind: String?): String = if (kind in KINDS) kind!! else "other"

    /**
     * `null` for a change with no title, the one field the server can't do without. A [url] that
     * isn't http(s), and [details] holding a NaN or infinite number, are left out rather than sent,
     * since the server would reject the whole change.
     */
    fun payload(
        configuration: Configuration,
        kind: String?,
        title: String?,
        details: Map<String, Any?>? = null,
        environment: String? = null,
        service: String? = null,
        actor: String? = null,
        url: String? = null,
        id: String? = null,
        occurredAt: Instant? = null,
    ): Map<String, Any?>? {
        val trimmed = title?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        val payload = linkedMapOf<String, Any?>(
            "kind" to normalizeKind(kind),
            "title" to trimmed.take(MAX_TITLE_LENGTH),
            "environment" to (environment ?: configuration.environment),
            "occurred_at" to (occurredAt ?: Instant.now()).toString(),
        )
        if (details != null && allFinite(details)) payload["details"] = details
        if (!service.isNullOrBlank()) payload["service"] = service
        if (!actor.isNullOrBlank()) payload["actor"] = actor
        if (url != null && (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))) {
            payload["url"] = url
        }
        if (!id.isNullOrBlank()) payload["id"] = id
        return payload
    }

    // A NaN or infinite number anywhere in details would encode as invalid JSON and make the server
    // reject the whole change, so details holding one are left out instead.
    private fun allFinite(value: Any?): Boolean = when (value) {
        is Double -> value.isFinite()
        is Float -> value.isFinite()
        is Map<*, *> -> value.values.all(::allFinite)
        is List<*> -> value.all(::allFinite)
        else -> true
    }
}

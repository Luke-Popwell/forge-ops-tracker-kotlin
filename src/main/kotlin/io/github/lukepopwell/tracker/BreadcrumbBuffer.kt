package io.github.lukepopwell.tracker

import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * A bounded, in-order trail of what happened right before an error, held one-per-thread via
 * [ForgeOpsTracker]'s own `ThreadLocal<BreadcrumbBuffer>`, the same isolation boundary
 * `currentUser` there already uses (the correct choice for a typical servlet container's
 * one-thread-per-request model, the same reasoning `sdks/java`'s own `BreadcrumbBuffer` and
 * `currentUser` fields both document): so, like that Java class, a single instance is only ever
 * touched by the one thread that owns it, and needs no synchronization of its own.
 *
 * This client has no web framework integration of its own yet (no `integrations/` package, unlike
 * `sdks/java`'s own Servlet filter), confirmed by reading every source file first, so there's no
 * automatic breadcrumb source and no filter to start a fresh trail per request on its own: a host
 * app is expected to call [ForgeOpsTracker.clearBreadcrumbs] itself at the start of each one, the
 * same place it would already be calling [ForgeOpsTracker.setUser] from.
 *
 * Entries are plain `Map<String, Any?>`, not a typed data class: matches [EventBuilder]'s own
 * "everything here is a dynamically-shaped map" convention (see its own `build` method), not
 * `sdks/go`'s or `sdks/rust`'s typed `Breadcrumb` struct.
 */
class BreadcrumbBuffer(private val configuration: Configuration) {
    private val timestampFormat = DateTimeFormatter.ISO_INSTANT
    private val entries = mutableListOf<Map<String, Any?>>()

    fun add(message: String, category: String, level: String, data: Map<String, Any?>) {
        // Read fresh on every add, not captured once at construction: a config change made
        // through ForgeOpsTracker.configuration() should take effect on whatever's added next,
        // the same reasoning sdks/java's own BreadcrumbBuffer#add documents for its own re-read
        // of Configuration#getMaxBreadcrumbs.
        // Strict privacy never sends breadcrumbs, so none are kept either: a message is free text.
        val maxSize = maxOf(configuration.maxBreadcrumbs, 0)
        if (maxSize == 0 || configuration.strictPrivacy) return

        entries.add(
            linkedMapOf(
                "category" to category,
                "message" to message,
                "level" to level,
                "timestamp" to timestampFormat.format(Instant.now()),
                "data" to data,
            ),
        )
        while (entries.size > maxSize) {
            entries.removeAt(0)
        }
    }

    /** A copy, not the live list: the same "never hand out mutable internal state" contract every other client's own equivalent already holds to. */
    fun all(): List<Map<String, Any?>> = entries.toList()
}

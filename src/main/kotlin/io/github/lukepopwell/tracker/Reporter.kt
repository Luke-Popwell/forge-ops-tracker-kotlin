package io.github.lukepopwell.tracker

import java.time.Duration

/**
 * Orchestrates one capture: enabled check, build, enqueue. An error reporter that itself throws
 * while reporting an error is the worst possible failure mode, so every path here is wrapped to
 * guarantee this never propagates an exception back into the caller: the same guarantee every
 * other client in this repo (including `sdks/java`) makes in its own equivalent class.
 */
open class Reporter(
    private val configuration: Configuration,
    private val eventBuilder: EventBuilder = EventBuilder(configuration),
    private val deliveryQueue: DeliveryQueue = DeliveryQueue(configuration),
) {
    open fun report(
        error: Throwable?,
        context: Map<String, Any?>? = null,
        user: Map<String, Any?>? = null,
        breadcrumbs: List<Map<String, Any?>> = emptyList(),
        traceId: String? = null,
    ) {
        try {
            if (!configuration.isEnabled || error == null) return
            deliveryQueue.push(eventBuilder.build(error, context, user, breadcrumbs, traceId))
        } catch (e: Exception) {
            configuration.log("report failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Sends what is still queued within [timeout]; see [DeliveryQueue.drain]. */
    open fun flush(timeout: Duration = DeliveryQueue.DRAIN_TIMEOUT): Boolean = deliveryQueue.drain(timeout)
}

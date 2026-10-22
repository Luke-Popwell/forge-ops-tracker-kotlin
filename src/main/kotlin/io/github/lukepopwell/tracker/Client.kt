package io.github.lukepopwell.tracker

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Delivers one payload over HTTP via [HttpClient] (built into the JDK since 11: no HTTP library
 * dependency needed, same reasoning as `net/http` in the Ruby gem and `sdks/java`'s own identical
 * choice). Every failure mode (DNS, connection, timeout, TLS, a non-2xx response) is caught
 * here and turned into a `false` return rather than a thrown exception, since a broken or
 * unreachable tracker must never be able to break the host app.
 */
open class Client(
    private val configuration: Configuration,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(configuration.connectTimeout)
        .build(),
) {
    // Set by post when the server rejected what this thread just sent; see [outcome].
    private val rejected = ThreadLocal<Boolean>()

    open fun deliver(payload: Map<String, Any?>): Boolean = post(configuration.ingestionUri, payload)

    /**
     * Same delivery contract as [deliver], against the DSN's performance_samples endpoint (see
     * [Configuration.performanceSamplesUri]). `samples` is wrapped as `{"samples": [...]}`, the
     * shape `Api::V1::PerformanceSamplesController` expects.
     */
    open fun deliverPerformanceSamples(samples: List<Map<String, Any?>>): Boolean =
        post(configuration.performanceSamplesUri, mapOf("samples" to samples))

    /** Delivers a batch of individual [ForgeOpsTracker.captureMetric] entries as `{"metrics": [...]}`. */
    open fun deliverMetrics(entries: List<Map<String, Any?>>): Boolean =
        post(configuration.customMetricsUri, mapOf("metrics" to entries))

    /** Delivers a batch of infrastructure readings the same way. */
    open fun deliverInfrastructureMetrics(entries: List<Map<String, Any?>>): Boolean =
        post(configuration.infrastructureMetricsUri, mapOf("metrics" to entries))

    /** Delivers one finished trace, `{"trace_id": ..., "spans": [...]}`, to the DSN's spans endpoint. */
    open fun deliverSpans(trace: Map<String, Any?>): Boolean = post(configuration.spansUri, trace)

    /**
     * Delivers one [ForgeOpsTracker.recordChange] change to the DSN's changes endpoint. A 403 (a plan
     * without change tracking) is just a `false` like any other rejection.
     */
    open fun deliverChange(change: Map<String, Any?>): Boolean = post(configuration.changesUri, change)

    /**
     * Runs one of the `deliver` functions above and says how it ended, telling a payload the server
     * rejected apart from one that only failed for now. Goes through the public Boolean function
     * rather than around it, so a subclass that overrides one still decides the outcome: its
     * `false` is a failure worth retrying unless the request it made was rejected.
     */
    internal fun outcome(delivery: () -> Boolean): DeliveryResult {
        rejected.remove()
        try {
            if (delivery()) return DeliveryResult.DELIVERED
            return if (rejected.get() != null) DeliveryResult.REJECTED else DeliveryResult.FAILED
        } finally {
            rejected.remove()
        }
    }

    private fun post(uri: java.net.URI?, payload: Map<String, Any?>): Boolean {
        uri ?: return false
        return try {
            val request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer ${configuration.apiKey}")
                .header("Content-Type", "application/json")
                .timeout(configuration.readTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(Json.encode(payload)))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
            val result = DeliveryResult.ofStatus(response.statusCode())
            if (result == DeliveryResult.REJECTED) {
                rejected.set(true)
                configuration.log("delivery rejected: HTTP ${response.statusCode()}")
            } else if (result == DeliveryResult.FAILED) {
                configuration.log("delivery failed: HTTP ${response.statusCode()}")
            }
            result == DeliveryResult.DELIVERED
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            configuration.log("delivery failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        } catch (e: Exception) {
            configuration.log("delivery failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }
}

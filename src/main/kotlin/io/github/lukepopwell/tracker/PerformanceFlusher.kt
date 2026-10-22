package io.github.lukepopwell.tracker

import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Times work in-process, bucketed by transaction name (see [ForgeOpsTracker.timeTransaction]),
 * and periodically flushes each distinct bucket as one small aggregate report, rather than one
 * network call per timed call. Ported from `sdks/java`'s own `PerformanceFlusher` (itself ported
 * from `gems/forge_ops_tracker`'s), with one real fix over both: see [flush]'s own comment on why
 * it subtracts what it delivered instead of clearing the buckets outright.
 *
 * A daemon [ScheduledExecutorService] started lazily on first record, plus a JVM shutdown hook
 * that flushes whatever is left on a normal exit: unlike Rust or Go, the JVM has a real
 * equivalent of the Ruby gem's `at_exit`, so the last partial window isn't dropped.
 */
class PerformanceFlusher(
    private val configuration: Configuration,
    private val client: Client,
) {
    private class Bucket(
        var count: Long = 0,
        var durationSumMs: Double = 0.0,
        var maxDurationMs: Double = 0.0,
        val histogram: MutableMap<String, Long> = LinkedHashMap(),
    )

    private val lock = Any()
    private val buckets = LinkedHashMap<String, Bucket>()
    private var periodStartedAt: Instant = Instant.now()
    private var failedFlushes = 0

    @Volatile
    private var executor: ScheduledExecutorService? = null
    private val shutdownHookRegistered = AtomicBoolean()

    fun record(transactionName: String, durationMs: Double) {
        ensureScheduled()
        // Under strict privacy a raw path (the fallback when no route matched) loses its ids.
        val name = if (configuration.strictPrivacy) StrictPrivacy.spanName(transactionName) else transactionName
        synchronized(lock) {
            val bucket = buckets.getOrPut(name) { Bucket() }
            bucket.count++
            bucket.durationSumMs += durationMs
            if (durationMs > bucket.maxDurationMs) bucket.maxDurationMs = durationMs
            // The distribution count/sum/max can't reconstruct: see HistogramBucketer for why the
            // server approximates a percentile from these bucket counts.
            bucket.histogram.merge(HistogramBucketer.bucketFor(durationMs), 1L, Long::plus)
        }
    }

    /**
     * Snapshots the buffered buckets and delivers them as one batch. A failed delivery keeps every
     * bucket where it is, so the next flush's batch just grows instead of losing what was already
     * tallied: there's no other copy of this data anywhere. Two limits on that: a batch the server
     * rejected would be rejected again however often it is re-sent, so it is dropped at once, and
     * so is a batch whose delivery has failed [DeliveryResult.MAX_FAILED_FLUSHES] flushes in a row.
     *
     * Only exactly what this snapshot delivered (or gave up on) is removed afterward, subtracted from whatever is
     * in each bucket by then, never the whole map cleared outright. [record] can run concurrently
     * with this (that's what the lock is for), and delivery here happens with the lock released, so
     * a [record] call for a transaction already in the snapshot, or a brand-new one, can land in
     * the exact window between the snapshot and delivery succeeding. Clearing the map afterward, as
     * if delivery had covered everything now in it, would silently discard that concurrently
     * recorded data forever. `sdks/java`'s and `gems/forge_ops_tracker`'s flushers both still clear
     * (`buckets.clear()`/`@buckets = {}`), unfixed: the same bug `sdks/go` found and fixed, pinned
     * here by a deterministic test rather than assumed.
     */
    fun flush() {
        val snapshot: Map<String, Bucket>
        val samples: List<Map<String, Any?>>
        val periodEndedAt: Instant
        synchronized(lock) {
            if (buckets.isEmpty()) return
            periodEndedAt = Instant.now()
            snapshot = buckets.mapValues { (_, b) -> Bucket(b.count, b.durationSumMs, b.maxDurationMs, LinkedHashMap(b.histogram)) }
            samples = snapshot.map { (name, bucket) ->
                linkedMapOf(
                    "transaction_name" to name,
                    "environment" to configuration.environment,
                    "release" to configuration.release,
                    "period_started_at" to periodStartedAt.toString(),
                    "period_ended_at" to periodEndedAt.toString(),
                    "request_count" to bucket.count,
                    "duration_sum_ms" to bucket.durationSumMs,
                    "max_duration_ms" to bucket.maxDurationMs,
                    "histogram" to LinkedHashMap(bucket.histogram),
                )
            }
        }

        val result = client.outcome { client.deliverPerformanceSamples(samples) }

        synchronized(lock) {
            if (result == DeliveryResult.FAILED && ++failedFlushes < DeliveryResult.MAX_FAILED_FLUSHES) return
            failedFlushes = 0
            for ((name, sent) in snapshot) {
                val current = buckets[name] ?: continue
                current.count -= sent.count
                current.durationSumMs = maxOf(0.0, current.durationSumMs - sent.durationSumMs)
                for ((label, count) in sent.histogram) {
                    val remaining = (current.histogram[label] ?: 0L) - count
                    if (remaining > 0) current.histogram[label] = remaining else current.histogram.remove(label)
                }
                if (current.count <= 0) buckets.remove(name)
                // maxDurationMs is deliberately left as whatever is currently on the bucket, sent
                // or not: unlike count/durationSumMs, a max can't be correctly "subtracted" back
                // out (the true max of what's left is anything at or below it, not knowable from
                // the two numbers alone), and leaving it never overstates the next period's own
                // max, only potentially understates how far back it was actually set.
            }
            periodStartedAt = periodEndedAt
        }
        if (result != DeliveryResult.DELIVERED) {
            configuration.log("dropped performance samples for ${snapshot.size} transactions: ${result.dropReason()}")
        }
    }

    /** Test-only: `(count, durationSumMs, maxDurationMs)` per transaction, as currently tallied. */
    internal fun tallies(): Map<String, Triple<Long, Double, Double>> = synchronized(lock) {
        buckets.mapValues { (_, b) -> Triple(b.count, b.durationSumMs, b.maxDurationMs) }
    }

    /** Test-only: stops the flush thread so it can't outlive the test that started it. */
    internal fun shutdown() {
        executor?.shutdownNow()
    }

    private fun ensureScheduled() {
        if (executor != null) return
        synchronized(this) {
            if (executor != null) return
            val scheduled = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "forge-ops-tracker-performance-flusher").apply { isDaemon = true }
            }
            val intervalMillis = maxOf(1L, configuration.performanceFlushInterval.toMillis())
            scheduled.scheduleWithFixedDelay(::flushSafely, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS)
            executor = scheduled
            registerShutdownHook()
        }
    }

    private fun flushSafely() {
        try {
            flush()
        } catch (e: RuntimeException) {
            // One bad flush must not kill every flush after it: a scheduled task that throws is
            // cancelled by ScheduledExecutorService, so this is what keeps the schedule alive.
            configuration.log("performance flush error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun registerShutdownHook() {
        if (!shutdownHookRegistered.compareAndSet(false, true)) return
        Runtime.getRuntime().addShutdownHook(Thread(::flushSafely, "forge-ops-tracker-performance-flusher-shutdown"))
    }
}

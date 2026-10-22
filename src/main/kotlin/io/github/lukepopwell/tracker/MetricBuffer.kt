package io.github.lukepopwell.tracker

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Collects individual [ForgeOpsTracker.captureMetric]/[ForgeOpsTracker.captureInfrastructureMetric]
 * calls in-process and periodically flushes them as one batch, rather than one network call per
 * capture. Unlike [PerformanceFlusher] this keeps a *list* of individually meaningful entries
 * instead of summing them into buckets: a customer's own signup or payment is exactly the kind of
 * thing they will want a genuinely accurate count/sum of later, so the server stores one row per
 * entry as-is. Ported from `sdks/java`'s own `MetricBuffer` (itself from `gems/forge_ops_tracker`'s
 * two near-identical buffer classes: one class instantiated twice here, told which delivery function
 * and flush interval to use).
 *
 * Three deliberate differences from the Ruby buffers:
 *
 * - A flush snapshots the first N entries and, on success, removes exactly those N, instead of
 *   resetting the whole list, so an entry recorded while the request is in flight (the lock is
 *   released around the network call) is kept for the next flush rather than lost.
 * - The buffer is capped at [MAX_ENTRIES], and once full further entries are dropped until a flush
 *   empties it: an endpoint that is down fails every flush, and an uncapped buffer would then keep
 *   growing until the batch is given up on. Dropping the newest rather than the oldest keeps the
 *   entries a flush is delivering at the front of the list, which is what makes removing exactly
 *   them afterward exact.
 * - A NaN or infinite value is dropped at record time: it is not valid JSON, and one bad entry would
 *   make the server reject the whole batch behind it.
 *
 * The same daemon [ScheduledExecutorService] and JVM shutdown hook [PerformanceFlusher] uses: the
 * shutdown hook is what makes a short-lived cron program that captures a few readings and returns
 * from `main` still deliver them.
 */
class MetricBuffer internal constructor(
    private val configuration: Configuration,
    private val interval: () -> Duration,
    private val deliver: (List<Map<String, Any?>>) -> DeliveryResult,
) {
    /**
     * [deliver] takes a batch and returns whether delivery succeeded; `false` is a failure worth
     * retrying. The primary constructor is the same buffer for a `deliver` that can also say the
     * server rejected the batch; it is internal, and its arguments are in a different order only so
     * the two stay distinct on the JVM, where both function types erase to the same class.
     */
    constructor(
        configuration: Configuration,
        deliver: (List<Map<String, Any?>>) -> Boolean,
        interval: () -> Duration,
    ) : this(configuration, interval, { batch -> if (deliver(batch)) DeliveryResult.DELIVERED else DeliveryResult.FAILED })

    private val lock = Any()
    private val entries = ArrayList<Map<String, Any?>>()
    private var failedFlushes = 0

    @Volatile
    private var executor: ScheduledExecutorService? = null
    private val shutdownHookRegistered = AtomicBoolean()

    /** Adds one entry (everything but `recorded_at`, which is stamped here); returns whether it was kept. */
    fun record(entry: Map<String, Any?>): Boolean {
        val value = entry["value"]
        if (value !is Number || !value.toDouble().isFinite()) {
            configuration.log("dropped a metric with a non-finite value")
            return false
        }

        ensureScheduled()
        synchronized(lock) {
            if (entries.size >= MAX_ENTRIES) {
                configuration.log("metric buffer full, dropping a metric")
                return false
            }
            entries.add(entry + ("recorded_at" to TIMESTAMP.format(Instant.now())))
            return true
        }
    }

    /**
     * Delivers everything buffered so far as one batch. A failed delivery keeps every entry, so the
     * next flush's batch just grows, unless the server rejected the batch (it would reject it again)
     * or this is the [DeliveryResult.MAX_FAILED_FLUSHES]th flush in a row to fail: then the batch is
     * dropped, which is also what makes room in a full buffer again.
     */
    fun flush() {
        val snapshot: List<Map<String, Any?>>
        synchronized(lock) {
            if (entries.isEmpty()) return
            snapshot = ArrayList(entries)
        }

        val result = deliver(snapshot)

        synchronized(lock) {
            if (result == DeliveryResult.FAILED && ++failedFlushes < DeliveryResult.MAX_FAILED_FLUSHES) return
            failedFlushes = 0
            // Exactly the entries just delivered (or given up on): anything recorded while the
            // request was in flight sits after them and stays for the next flush.
            entries.subList(0, minOf(snapshot.size, entries.size)).clear()
        }
        if (result != DeliveryResult.DELIVERED) {
            configuration.log("dropped ${snapshot.size} metrics: ${result.dropReason()}")
        }
    }

    /** Test-only: how many entries are currently buffered. */
    internal fun size(): Int = synchronized(lock) { entries.size }

    /** Test-only: stops the schedule and drops what is buffered, so it can't outlive the test that started it. */
    internal fun shutdown() {
        executor?.shutdownNow()
        synchronized(lock) { entries.clear() }
    }

    private fun ensureScheduled() {
        if (executor != null) return
        synchronized(this) {
            if (executor != null) return
            val scheduled = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "forge-ops-tracker-metric-flusher").apply { isDaemon = true }
            }
            val intervalMillis = maxOf(1L, interval().toMillis())
            scheduled.scheduleWithFixedDelay(::flushSafely, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS)
            executor = scheduled
            if (shutdownHookRegistered.compareAndSet(false, true)) {
                Runtime.getRuntime().addShutdownHook(Thread(::flushSafely, "forge-ops-tracker-metric-flusher-shutdown"))
            }
        }
    }

    private fun flushSafely() {
        try {
            flush()
        } catch (e: RuntimeException) {
            configuration.log("metric flush error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    companion object {
        const val MAX_ENTRIES = 1000
        private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
    }
}

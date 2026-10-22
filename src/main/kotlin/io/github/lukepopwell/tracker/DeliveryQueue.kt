package io.github.lukepopwell.tracker

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A bounded [Channel] drained by a single coroutine, so delivery never blocks the request/call
 * site that caught the error. `sdks/java`'s own `DeliveryQueue` uses a raw daemon [Thread] +
 * `BlockingQueue` instead: functionally the same shape, but coroutines are the idiomatic Kotlin
 * way to express "one background worker, bounded backpressure," the same reason this client
 * exists as its own port rather than just calling into the Java one (see README.md).
 *
 * The worker coroutine is started lazily, on first [push], same as every other client in this
 * repo with a background delivery worker: there's no reason to spend a thread on a host app
 * that never actually reports anything. [Dispatchers.IO] threads are daemons that die with the
 * JVM, so the first push also registers a shutdown hook that sends whatever is still queued
 * before the JVM exits (see [drain]).
 */
open class DeliveryQueue(
    private val configuration: Configuration,
    private val client: Client = Client(configuration),
) {
    private val channel = Channel<Map<String, Any?>>(capacity = configuration.queueSize.coerceAtLeast(1))
    // Dispatchers.IO.limitedParallelism(1) rather than a single-threaded Executor built by hand:
    // still exactly one worker at a time (matching the Java client's own single daemon thread),
    // just expressed as a coroutine dispatcher.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val startLock = ReentrantLock()
    private var started = false
    private val shutdownHookRegistered = AtomicBoolean()

    // Payloads accepted but not yet delivered: still queued, or being delivered right now.
    private val unfinished = AtomicInteger()

    /**
     * Enqueues a payload, dropping it silently (never blocking the caller) if the queue is
     * already full: a burst of exceptions must never apply backpressure to the host app.
     */
    open fun push(payload: Map<String, Any?>): Boolean {
        ensureWorkerStarted()
        unfinished.incrementAndGet()
        val result = channel.trySend(payload)
        if (!result.isSuccess) {
            unfinished.decrementAndGet()
            configuration.log("delivery queue full, dropping event")
        }
        return result.isSuccess
    }

    /**
     * Sends whatever is still queued, and lets a delivery already under way finish, within
     * [timeout]; returns whether everything accepted so far has gone out. Delivers on the calling
     * thread rather than waiting on the worker. The shutdown hook calls this with [DRAIN_TIMEOUT];
     * without it a short-lived program (a CLI `main`, a cron job, a crash reported by the
     * uncaught-exception handler) lost every error it reported in its last moments, since the
     * worker's daemon thread dies with the JVM before it gets to them.
     */
    open fun drain(timeout: Duration = DRAIN_TIMEOUT): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        try {
            while (System.nanoTime() - deadline < 0) {
                val payload = channel.tryReceive().getOrNull() ?: break
                deliverAndFinish(payload)
            }
            while (unfinished.get() > 0 && System.nanoTime() - deadline < 0) {
                Thread.sleep(10)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            configuration.log("drain failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        return unfinished.get() == 0
    }

    private fun ensureWorkerStarted() {
        registerShutdownHook()
        if (started) return
        startLock.withLock {
            if (started) return
            started = true
            scope.launch {
                for (payload in channel) {
                    try {
                        deliverAndFinish(payload)
                    } catch (e: Exception) {
                        // Per-item, not wrapping the whole loop: one bad delivery must not kill
                        // the worker for every event after it.
                        configuration.log("delivery worker error: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }
        }
    }

    private fun deliverAndFinish(payload: Map<String, Any?>) {
        try {
            client.deliver(payload)
        } finally {
            unfinished.decrementAndGet()
        }
    }

    // Registered on the first push, not at construction, so a program that never reports anything
    // never waits on exit at all.
    private fun registerShutdownHook() {
        if (!shutdownHookRegistered.compareAndSet(false, true)) return
        try {
            Runtime.getRuntime().addShutdownHook(Thread({ drain() }, "forge-ops-tracker-delivery-shutdown"))
        } catch (e: IllegalStateException) {
            // Pushed from inside another shutdown hook: too late to add one, but the worker keeps
            // delivering while the other hooks run.
            configuration.log("delivery shutdown hook not registered: JVM is already shutting down")
        }
    }

    companion object {
        /**
         * The most a normal JVM exit waits for queued events to go out (see [drain]): long enough
         * for one delivery at the default connect + read timeouts, short enough that an
         * unreachable ForgeOps never holds up a deploy or a cron run for long.
         */
        val DRAIN_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}

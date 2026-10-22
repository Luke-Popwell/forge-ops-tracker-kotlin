package io.github.lukepopwell.tracker

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Same shape as [DeliveryQueue], delivering [ForgeOpsTracker.recordChange] changes to the `/changes`
 * endpoint. Like it, the first push registers a shutdown hook that sends whatever is still queued
 * before the JVM exits (see [drain]).
 */
open class ChangeQueue(
    private val configuration: Configuration,
    private val client: Client = Client(configuration),
) {
    private val channel = Channel<Map<String, Any?>>(capacity = configuration.queueSize.coerceAtLeast(1))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val startLock = ReentrantLock()
    private var started = false
    private val shutdownHookRegistered = AtomicBoolean()

    // Changes accepted but not yet delivered: still queued, or being delivered right now.
    private val unfinished = AtomicInteger()

    open fun push(change: Map<String, Any?>): Boolean {
        ensureWorkerStarted()
        unfinished.incrementAndGet()
        val result = channel.trySend(change)
        if (!result.isSuccess) {
            unfinished.decrementAndGet()
            configuration.log("change queue full, dropping change")
        }
        return result.isSuccess
    }

    /**
     * Sends whatever is still queued, and lets a delivery already under way finish, within
     * [timeout]; returns whether everything accepted so far has gone out. Delivers on the calling
     * thread rather than waiting on the worker. The shutdown hook calls this with
     * [DeliveryQueue.DRAIN_TIMEOUT]; without it a short-lived program (a deploy script, a cron job
     * that flips a flag) lost every change it recorded in its last moments, since the worker's
     * daemon thread dies with the JVM before it gets to them.
     */
    open fun drain(timeout: Duration = DeliveryQueue.DRAIN_TIMEOUT): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        try {
            while (System.nanoTime() - deadline < 0) {
                val change = channel.tryReceive().getOrNull() ?: break
                deliverAndFinish(change)
            }
            while (unfinished.get() > 0 && System.nanoTime() - deadline < 0) {
                Thread.sleep(10)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            configuration.log("change drain failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        return unfinished.get() == 0
    }

    fun shutdown() {
        scope.cancel()
    }

    private fun ensureWorkerStarted() {
        registerShutdownHook()
        if (started) return
        startLock.withLock {
            if (started) return
            started = true
            scope.launch {
                for (change in channel) {
                    try {
                        deliverAndFinish(change)
                    } catch (e: Exception) {
                        configuration.log("change worker error: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }
        }
    }

    private fun deliverAndFinish(change: Map<String, Any?>) {
        try {
            client.deliverChange(change)
        } finally {
            unfinished.decrementAndGet()
        }
    }

    // Registered on the first push, not at construction, so a program that never records a change
    // never waits on exit at all. The JVM starts every shutdown hook at once and runs them
    // concurrently, so this hook and [DeliveryQueue]'s spend the same 5 seconds side by side: a
    // program exiting with both errors and changes still queued waits at most 5 seconds, not 10.
    private fun registerShutdownHook() {
        if (!shutdownHookRegistered.compareAndSet(false, true)) return
        try {
            Runtime.getRuntime().addShutdownHook(Thread({ drain() }, "forge-ops-tracker-change-shutdown"))
        } catch (e: IllegalStateException) {
            // Pushed from inside another shutdown hook: too late to add one, but the worker keeps
            // delivering while the other hooks run.
            configuration.log("change shutdown hook not registered: JVM is already shutting down")
        }
    }
}

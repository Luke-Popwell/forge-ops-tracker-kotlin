package io.github.lukepopwell.tracker

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Same shape as [DeliveryQueue], delivering finished traces to the `/spans` endpoint. */
open class SpanQueue(
    private val configuration: Configuration,
    private val client: Client = Client(configuration),
) {
    private val channel = Channel<Map<String, Any?>>(capacity = configuration.queueSize.coerceAtLeast(1))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val startLock = ReentrantLock()
    private var started = false

    open fun push(trace: Map<String, Any?>): Boolean {
        ensureWorkerStarted()
        val result = channel.trySend(trace)
        if (!result.isSuccess) {
            configuration.log("span queue full, dropping trace")
        }
        return result.isSuccess
    }

    fun shutdown() {
        scope.cancel()
    }

    private fun ensureWorkerStarted() {
        if (started) return
        startLock.withLock {
            if (started) return
            started = true
            scope.launch {
                for (trace in channel) {
                    try {
                        client.deliverSpans(trace)
                    } catch (e: Exception) {
                        configuration.log("span worker error: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }
        }
    }
}

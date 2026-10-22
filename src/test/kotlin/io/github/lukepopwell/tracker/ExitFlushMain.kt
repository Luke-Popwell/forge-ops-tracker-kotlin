package io.github.lukepopwell.tracker

import java.io.File
import kotlin.system.exitProcess

/**
 * The child JVM [ExitFlushTest] launches: reports one error (or records one change) the way a
 * short CLI program does, then ends the way `args[0]` names. Delivery goes through a slow fake
 * client that appends each message (or change title) to the file `args[1]`, so anything the JVM
 * exits on before delivering never reaches the file.
 */
object ExitFlushMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args[0]
        val out = File(args[1])
        if (mode == "setup-page") {
            // Exactly what the setup page shows: FORGE_OPS_DSN in the environment (set by
            // ExitFlushTest) and a bare init(). Called twice to show the "not sending" warning
            // still prints only once.
            ForgeOpsTracker.init()
            ForgeOpsTracker.init()
        } else {
            ForgeOpsTracker.init {
                it.dsn = "https://key@example.com/api/v1/events"
                it.environment = "production"
            }
        }
        val config = ForgeOpsTracker.configuration()
        ForgeOpsTracker::class.java.getDeclaredField("reporter").apply {
            isAccessible = true
            set(null, Reporter(config, deliveryQueue = DeliveryQueue(config, FileClient(config, out))))
        }
        ForgeOpsTracker::class.java.getDeclaredField("changeQueue").apply {
            isAccessible = true
            set(null, ChangeQueue(config, FileClient(config, out)))
        }

        when (mode) {
            "setup-page" -> ForgeOpsTracker.captureException(RuntimeException("first test error"))
            "return" -> ForgeOpsTracker.captureException(RuntimeException("reported just before exit"))
            "exit" -> {
                ForgeOpsTracker.captureException(RuntimeException("reported before exitProcess"))
                exitProcess(3)
            }
            "uncaught" -> throw IllegalStateException("crashed main")
            "flush-then-halt" -> {
                ForgeOpsTracker.captureException(RuntimeException("flushed before halt"))
                ForgeOpsTracker.flush()
                Runtime.getRuntime().halt(0)
            }
            "change-return" -> ForgeOpsTracker.recordChange("feature_flag", "recorded just before exit")
            "change-flush-then-halt" -> {
                ForgeOpsTracker.captureException(RuntimeException("flushed before halt"))
                ForgeOpsTracker.recordChange("feature_flag", "change flushed before halt")
                ForgeOpsTracker.flush()
                Runtime.getRuntime().halt(0)
            }
            "halt" -> {
                ForgeOpsTracker.captureException(RuntimeException("lost to halt"))
                Runtime.getRuntime().halt(0)
            }
            else -> throw IllegalArgumentException(mode)
        }
    }

    // Slow enough that a JVM exiting right after the report always outruns the worker coroutine.
    private class FileClient(configuration: Configuration, private val out: File) : Client(configuration) {
        override fun deliver(payload: Map<String, Any?>): Boolean {
            Thread.sleep(300)
            out.appendText("${payload["message"]}\n")
            return true
        }

        override fun deliverChange(change: Map<String, Any?>): Boolean {
            Thread.sleep(300)
            out.appendText("${change["title"]}\n")
            return true
        }
    }
}

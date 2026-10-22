package io.github.lukepopwell.tracker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs [ExitFlushMain] in a real child JVM, since only a JVM that actually exits shows whether an
 * error reported (or a change recorded) in its last moments still goes out.
 */
class ExitFlushTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `sends an error reported just before main returns`() {
        assertEquals(0 to "reported just before exit\n", run("return"))
    }

    @Test
    fun `sends an error reported just before exitProcess`() {
        assertEquals(3 to "reported before exitProcess\n", run("exit"))
    }

    @Test
    fun `sends the uncaught exception that ended main`() {
        assertEquals(1 to "crashed main\n", run("uncaught"))
    }

    @Test
    fun `flush sends before halt, which skips shutdown hooks`() {
        assertEquals(0 to "flushed before halt\n", run("flush-then-halt"))
    }

    @Test
    fun `sends a change recorded just before main returns`() {
        assertEquals(0 to "recorded just before exit\n", run("change-return"))
    }

    @Test
    fun `flush sends queued changes along with errors before halt`() {
        val (status, delivered) = run("change-flush-then-halt")
        // The two queues deliver side by side, so either may land in the file first.
        assertEquals(0 to listOf("change flushed before halt", "flushed before halt"), status to delivered.lines().filter { it.isNotEmpty() }.sorted())
    }

    @Test
    fun `halt without flush loses the error`() {
        // The control for the tests above: the fake client is slow enough that the error never
        // goes out unless something waits for it.
        assertEquals(0 to "", run("halt"))
    }

    @Test
    fun `a DSN alone sends the first error`() {
        // Following the setup page exactly: a DSN and nothing else, so the environment defaults
        // to production and the error goes out with no warning.
        val result = runWithOutput("setup-page", mapOf("FORGE_OPS_DSN" to "https://key@example.com/api/v1/events"))
        assertEquals(0 to "first test error\n", result.first to result.second)
        assertFalse(result.third.contains("Not sending"), result.third)
    }

    @Test
    fun `a development environment sends nothing and says so once`() {
        val result = runWithOutput(
            "setup-page",
            mapOf("FORGE_OPS_DSN" to "https://key@example.com/api/v1/events", "FORGE_OPS_ENVIRONMENT" to "development"),
        )
        assertEquals(0 to "", result.first to result.second)
        val warning = "[ForgeOps] Not sending: this environment is \"development\", and only production, staging " +
            "are enabled. Set FORGE_OPS_ENVIRONMENT=production (or add \"development\" to the enabled environments) " +
            "to send from here."
        assertTrue(result.third.contains(warning), result.third)
        assertEquals(1, Regex("Not sending").findAll(result.third).count(), result.third)
    }

    private fun run(mode: String): Pair<Int, String> = runWithOutput(mode).let { it.first to it.second }

    /**
     * Exit status, delivered messages, and the child's combined stdout/stderr. [env] is added to a
     * child environment with every `FORGE_OPS_*` variable removed, so whatever the machine running
     * the tests has set can't change what the child does.
     */
    private fun runWithOutput(mode: String, env: Map<String, String> = emptyMap()): Triple<Int, String, String> {
        val out = File(tempDir, "delivered.txt").apply { createNewFile() }
        val output = File(tempDir, "output.txt")
        val java = File(System.getProperty("java.home"), "bin/java").path
        val builder = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), ExitFlushMain::class.java.name, mode, out.path)
            .redirectErrorStream(true)
            .redirectOutput(output)
        builder.environment().keys.removeIf { it.startsWith("FORGE_OPS_") }
        builder.environment().putAll(env)
        val process = builder.start()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "child JVM did not exit")
        return Triple(process.exitValue(), out.readText(), output.readText())
    }
}

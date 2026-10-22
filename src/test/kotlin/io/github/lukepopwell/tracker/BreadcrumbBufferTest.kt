package io.github.lukepopwell.tracker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BreadcrumbBufferTest {
    @Test
    fun `returns entries in insertion order each carrying the given fields plus a timestamp`() {
        val config = Configuration().apply { maxBreadcrumbs = 3 }
        val buffer = BreadcrumbBuffer(config)

        buffer.add("User Load", "query", "info", mapOf("duration_ms" to 1.2))

        val entry = buffer.all()[0]
        assertEquals("query", entry["category"])
        assertEquals("User Load", entry["message"])
        assertEquals("info", entry["level"])
        assertEquals(mapOf("duration_ms" to 1.2), entry["data"])
        assertTrue(entry["timestamp"].toString().endsWith("Z"))
    }

    @Test
    fun `drops the oldest entry once maxBreadcrumbs is exceeded`() {
        val config = Configuration().apply { maxBreadcrumbs = 3 }
        val buffer = BreadcrumbBuffer(config)

        for (n in 0 until 4) {
            buffer.add("entry $n", "custom", "info", emptyMap())
        }

        val messages = buffer.all().map { it["message"] }
        assertEquals(listOf("entry 1", "entry 2", "entry 3"), messages)
    }

    @Test
    fun `re-reads maxBreadcrumbs on every add, not just at construction`() {
        val config = Configuration().apply { maxBreadcrumbs = 2 }
        val buffer = BreadcrumbBuffer(config)

        buffer.add("one", "custom", "info", emptyMap())
        buffer.add("two", "custom", "info", emptyMap())
        config.maxBreadcrumbs = 1
        buffer.add("three", "custom", "info", emptyMap())

        val messages = buffer.all().map { it["message"] }
        assertEquals(listOf("three"), messages)
    }

    @Test
    fun `adds nothing at all when maxBreadcrumbs is zero`() {
        val config = Configuration().apply { maxBreadcrumbs = 0 }
        val buffer = BreadcrumbBuffer(config)

        buffer.add("one", "custom", "info", emptyMap())

        assertEquals(emptyList<Map<String, Any?>>(), buffer.all())
    }

    @Test
    fun `adds nothing when maxBreadcrumbs is negative`() {
        val config = Configuration().apply { maxBreadcrumbs = -5 }
        val buffer = BreadcrumbBuffer(config)

        buffer.add("one", "custom", "info", emptyMap())

        assertEquals(emptyList<Map<String, Any?>>(), buffer.all())
    }

    @Test
    fun `all returns a copy, not the internal list`() {
        val config = Configuration()
        val buffer = BreadcrumbBuffer(config)
        buffer.add("one", "custom", "info", emptyMap())

        val snapshot = buffer.all().toMutableList()
        snapshot.add(mapOf("category" to "custom", "message" to "mutated externally"))

        assertEquals(1, buffer.all().size)
    }
}

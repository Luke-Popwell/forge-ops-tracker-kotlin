package io.github.lukepopwell.tracker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JsonTest {
    @Test
    fun `encodes primitives`() {
        assertEquals("null", Json.encode(null))
        assertEquals("true", Json.encode(true))
        assertEquals("42", Json.encode(42))
        assertEquals("\"hi\"", Json.encode("hi"))
    }

    @Test
    fun `escapes special characters in strings`() {
        assertEquals("\"line1\\nline2\"", Json.encode("line1\nline2"))
        assertEquals("\"quote: \\\"hi\\\"\"", Json.encode("quote: \"hi\""))
        assertEquals("\"back\\\\slash\"", Json.encode("back\\slash"))
    }

    @Test
    fun `encodes maps and lists`() {
        assertEquals("{\"a\":1,\"b\":[1,2,3]}", Json.encode(linkedMapOf("a" to 1, "b" to listOf(1, 2, 3))))
    }

    @Test
    fun `stringifies unrecognized values rather than failing`() {
        val id = java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        assertEquals("\"$id\"", Json.encode(id))
    }
}

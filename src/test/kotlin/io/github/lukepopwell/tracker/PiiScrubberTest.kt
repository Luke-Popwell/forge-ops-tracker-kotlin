package io.github.lukepopwell.tracker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PiiScrubberTest {
    @Test
    fun `scrubs an email address`() {
        assertEquals("contact [EMAIL FILTERED] for help", PiiScrubber.scrub("contact user@example.com for help"))
    }

    @Test
    fun `scrubs a formatted credit card`() {
        assertNotEquals("charged card 4242-4242-4242-4242 successfully", PiiScrubber.scrub("charged card 4242-4242-4242-4242 successfully"))
    }

    @Test
    fun `leaves an ordinary long numeric id alone`() {
        val text = "order id 1234567890123456"
        assertEquals(text, PiiScrubber.scrub(text))
    }

    @Test
    fun `scrubs a formatted ssn`() {
        assertEquals("ssn on file: [SSN FILTERED]", PiiScrubber.scrub("ssn on file: 123-45-6789"))
    }

    @Test
    fun `scrubs known api key and token formats`() {
        val cases = listOf(
            "Authorization: Bearer abc123DEF.456-xyz",
            "aws key AKIAABCDEFGHIJKLMNOP in use",
            "stripe key sk_live_abcdefghijklmnop",
            "github token ghp_abcdefghijklmnopqrstuvwxyz0123456789",
            "jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dQw4w9WgXcQ",
        )
        for (text in cases) {
            assertNotEquals(text, PiiScrubber.scrub(text), "expected $text to be redacted")
        }
    }

    @Test
    fun `redacts whole value under sensitive key regardless of type or casing`() {
        assertEquals(PiiScrubber.REDACTED, PiiScrubber.scrub(12345, "apiKey"))
        assertEquals(PiiScrubber.REDACTED, PiiScrubber.scrub("hunter2", "X-Api-Key"))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `recurses into maps and lists`() {
        val input = mapOf(
            "password" to "hunter2",
            "note" to "email me at user@example.com",
            "items" to listOf(mapOf("token" to "abc"), "visit user@example.com"),
        )

        val scrubbed = PiiScrubber.scrub(input) as Map<String, Any?>

        assertEquals(PiiScrubber.REDACTED, scrubbed["password"])
        assertEquals("email me at [EMAIL FILTERED]", scrubbed["note"])

        val items = scrubbed["items"] as List<Any?>
        assertEquals(PiiScrubber.REDACTED, (items[0] as Map<String, Any?>)["token"])
        assertEquals("visit [EMAIL FILTERED]", items[1])
    }

    @Test
    fun `sensitive key check ignores case and punctuation`() {
        for (key in listOf("API_KEY", "Api-Key", "apiKey", "X-Api-Key")) {
            assertEquals(PiiScrubber.REDACTED, PiiScrubber.scrub("value", key), "expected $key to be treated as sensitive")
        }
        assertEquals("bob", PiiScrubber.scrub("bob", "username"))
    }

    // pii_scrubbing_vectors.json is a byte-identical copy of sdks/pii_scrubbing_vectors.json,
    // generated from the server's own PiiScrubber: see sdks/PII_SCRUBBING.md.
    @Suppress("UNCHECKED_CAST")
    private fun vectors(kind: String): List<Map<String, Any?>> {
        val json = javaClass.getResourceAsStream("/pii_scrubbing_vectors.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        return (StrictPrivacyTest.JsonReader(json).read() as Map<String, Any?>)[kind] as List<Map<String, Any?>>
    }

    @Test
    fun `scrubs card numbers and ibans in every vector exactly as the server does`() {
        val vectors = vectors("strings")
        assertTrue(vectors.isNotEmpty())
        for (vector in vectors) {
            assertEquals(vector["scrubbed"], PiiScrubber.scrub(vector["input"]), vector["input"] as String)
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `redacts bank detail keys in every vector exactly as the server does`() {
        val vectors = vectors("keys")
        assertTrue(vectors.isNotEmpty())
        for (vector in vectors) {
            val key = vector["key"] as String
            val scrubbed = PiiScrubber.scrub(mapOf(key to "x")) as Map<String, Any?>
            assertEquals(if (vector["redacted"] == true) PiiScrubber.REDACTED else "x", scrubbed[key], key)
        }
    }
}

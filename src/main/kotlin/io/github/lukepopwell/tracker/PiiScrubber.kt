package io.github.lukepopwell.tracker

/**
 * Redacts likely-sensitive content out of a payload before it ever leaves this process: the
 * same key list and regex patterns as `sdks/java`'s own `PiiScrubber` (itself ported from the
 * Ruby gem's), kept as a separate, dependency-free implementation here for the same reason: this
 * client has to work standalone in any host app, regardless of what's reporting into it.
 *
 * ForgeOps itself scrubs again on arrival regardless: this is a second, earlier layer, not the
 * only one. Can be disabled via `Configuration.scrubPii = false`, but "on" is the only sane
 * default.
 */
object PiiScrubber {
    const val REDACTED = "[FILTERED]"

    private val SENSITIVE_KEYS = setOf(
        "password", "passwd", "pwd",
        "secret", "apisecret", "clientsecret", "secretkey",
        "token", "accesstoken", "refreshtoken", "apikey", "apitoken", "authorization", "authtoken",
        "bearer", "sessiontoken", "csrftoken",
        "creditcard", "cardnumber", "cardnum", "cvv", "cvv2", "cvc",
        "ssn", "socialsecuritynumber", "socialsecurity",
        "privatekey",
    )

    private val PATTERNS: List<Pair<String, Regex>> = listOf(
        "EMAIL" to Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"),
        "SSN" to Regex("\\b\\d{3}-\\d{2}-\\d{4}\\b"),
        "CREDIT CARD" to Regex("\\b\\d{4}[ -]\\d{4}[ -]\\d{4}[ -]\\d{1,4}\\b"),
        "BEARER TOKEN" to Regex("\\bBearer\\s+[A-Za-z0-9\\-._~+/]+=*", RegexOption.IGNORE_CASE),
        "JWT" to Regex("\\bey[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b"),
        "AWS KEY" to Regex("\\bAKIA[0-9A-Z]{16}\\b"),
        "STRIPE KEY" to Regex("\\b[sr]k_(?:live|test)_[A-Za-z0-9]{10,}\\b"),
        "GITHUB TOKEN" to Regex("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"),
    )

    fun scrub(value: Any?, key: String? = null): Any? {
        if (value != null && isSensitiveKey(key)) {
            return REDACTED
        }

        return when (value) {
            is Map<*, *> -> value.entries.associate { (k, v) ->
                val stringKey = k.toString()
                stringKey to scrub(v, stringKey)
            }
            is List<*> -> value.map { scrub(it, key) }
            is String -> scrubString(value)
            else -> value
        }
    }

    private fun isSensitiveKey(key: String?): Boolean {
        if (key.isNullOrEmpty()) return false
        val normalized = key.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
        return SENSITIVE_KEYS.any { normalized.contains(it) }
    }

    private fun scrubString(input: String): String {
        var result = input
        for ((label, pattern) in PATTERNS) {
            result = pattern.replace(result, "[$label FILTERED]")
        }
        return result
    }
}

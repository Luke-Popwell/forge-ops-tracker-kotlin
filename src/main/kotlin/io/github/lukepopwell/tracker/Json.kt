package io.github.lukepopwell.tracker

/**
 * A minimal, dependency-free JSON encoder for exactly the payload shapes this client produces:
 * nested `Map`/`List` of `String`/`Number`/`Boolean`/`null`. Mirrors `sdks/java`'s own `Json`:
 * see that class's own comment: the JDK (and by extension Kotlin, which compiles to it) is the
 * one standard library among this repo's clients with no built-in JSON support, so this is a
 * genuine reason to write ~40 lines here, not a missing dependency to add. It only needs to
 * encode, never parse: this client never reads JSON back.
 */
object Json {
    fun encode(value: Any?): String = buildString { write(value, this) }

    private fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is Map<*, *> -> writeMap(value, out)
            is List<*> -> writeList(value, out)
            is String -> writeString(value, out)
            is Boolean, is Number -> out.append(value)
            // Anything else (a caller-supplied context object we don't recognize) is stringified
            // rather than dropped or thrown on: best-effort, never a reason to fail delivery.
            else -> writeString(value.toString(), out)
        }
    }

    private fun writeMap(map: Map<*, *>, out: StringBuilder) {
        out.append('{')
        map.entries.forEachIndexed { i, (key, value) ->
            if (i > 0) out.append(',')
            writeString(key.toString(), out)
            out.append(':')
            write(value, out)
        }
        out.append('}')
    }

    private fun writeList(list: List<*>, out: StringBuilder) {
        out.append('[')
        list.forEachIndexed { i, item ->
            if (i > 0) out.append(',')
            write(item, out)
        }
        out.append(']')
    }

    private fun writeString(value: String, out: StringBuilder) {
        out.append('"')
        for (c in value) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c.code < 0x20 -> out.append("\\u%04x".format(c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}

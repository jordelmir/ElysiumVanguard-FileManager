package com.elysium.vanguard.core.server

/**
 * PHASE 2.3 — Hand-rolled JSON encoder for the small subset of payloads we emit.
 *
 * Why not Gson/Moshi/kotlinx-serialization:
 *   - The transfer server emits maybe 4 different shapes. Pulling in a serialization
 *     library to escape 5 fields each is overkill.
 *   - We control exactly what gets serialized, which keeps the wire format tight and
 *     avoids accidental data leaks via reflective discovery.
 *
 * Scope: maps, lists, strings, numbers, booleans, null. That's the whole transfer
 * protocol. Anything outside that scope throws [JsonException].
 *
 * The encoder escapes the JSON-mandatory characters (\, ", control chars up to 0x1F).
 * Forward slashes are NOT escaped (RFC 8259 §7 allows either, and unescaped is shorter).
 */
object Json {

    fun encode(value: Any?): String = buildString { appendValue(this, value) }

    private fun appendValue(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(if (value) "true" else "false")
            is Number -> out.append(formatNumber(value))
            is String -> appendString(out, value)
            is Map<*, *> -> appendObject(out, value)
            is Iterable<*> -> appendArray(out, value)
            is Array<*> -> appendArray(out, value.asList())
            else -> throw JsonException("Unsupported JSON value type: ${value::class.simpleName}")
        }
    }

    private fun formatNumber(n: Number): String = when (n) {
        is Double, is Float -> {
            val d = n.toDouble()
            if (d.isFinite()) d.toString() else throw JsonException("Non-finite numbers not allowed: $d")
        }
        else -> n.toString()
    }

    private fun appendString(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when (c) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000c' -> out.append("\\f")
                else -> if (c.code < 0x20) {
                    out.append("\\u").append(String.format("%04x", c.code))
                } else {
                    out.append(c)
                }
            }
        }
        out.append('"')
    }

    private fun appendObject(out: StringBuilder, map: Map<*, *>) {
        out.append('{')
        var first = true
        for ((k, v) in map) {
            if (!first) out.append(',')
            first = false
            val key = k?.toString() ?: throw JsonException("JSON keys must not be null")
            appendString(out, key)
            out.append(':')
            appendValue(out, v)
        }
        out.append('}')
    }

    private fun appendArray(out: StringBuilder, items: Iterable<*>) {
        out.append('[')
        var first = true
        for (v in items) {
            if (!first) out.append(',')
            first = false
            appendValue(out, v)
        }
        out.append(']')
    }

    /**
     * Parse a JSON document into the standard boxing types: maps
     * for `{…}`, lists for `[…]`, [String]/[Long]/[Double]/
     * [Boolean]/`null` for scalars. We only need this to read the
     * envelopes the server emits (and the CRDT sync round in
     * particular). Throws [JsonException] on malformed input.
     */
    fun decode(text: String): Any? {
        val p = Parser(text)
        p.skipWhitespace()
        val v = p.readValue()
        p.skipWhitespace()
        if (!p.atEnd()) throw JsonException("trailing content at index ${p.index}")
        return v
    }

    private class Parser(private val text: String) {
        var index = 0

        fun atEnd(): Boolean = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun readValue(): Any? {
            skipWhitespace()
            if (atEnd()) throw JsonException("unexpected end of input")
            return when (val c = text[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't', 'f' -> readBoolean()
                'n' -> readNull()
                '-', in '0'..'9' -> readNumber()
                else -> throw JsonException("unexpected char '$c' at index $index")
            }
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { index++; return result }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                val v = readValue()
                result[key] = v
                skipWhitespace()
                when (val c = peek()) {
                    ',' -> index++
                    '}' -> { index++; return result }
                    else -> throw JsonException("expected ',' or '}' at index $index, got '$c'")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') { index++; return result }
            while (true) {
                val v = readValue()
                result += v
                skipWhitespace()
                when (val c = peek()) {
                    ',' -> index++
                    ']' -> { index++; return result }
                    else -> throw JsonException("expected ',' or ']' at index $index, got '$c'")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (index < text.length) {
                when (val c = text[index]) {
                    '"' -> { index++; return sb.toString() }
                    '\\' -> {
                        if (index + 1 >= text.length) throw JsonException("bad escape at end")
                        when (val esc = text[index + 1]) {
                            '"', '\\', '/' -> sb.append(esc)
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'u' -> {
                                if (index + 5 >= text.length) throw JsonException("bad unicode escape")
                                val code = text.substring(index + 2, index + 6).toInt(16)
                                sb.append(code.toChar())
                                index += 4
                            }
                            else -> throw JsonException("invalid escape \\$esc")
                        }
                        index += 2
                    }
                    else -> {
                        sb.append(c)
                        index++
                    }
                }
            }
            throw JsonException("unterminated string")
        }

        private fun readNumber(): Any {
            val start = index
            if (peek() == '-') index++
            while (!atEnd() && (text[index].isDigit() || text[index] in ".eE+-")) index++
            val raw = text.substring(start, index)
            return raw.toLongOrNull() ?: raw.toDouble()
        }

        private fun readBoolean(): Boolean {
            return when {
                text.startsWith("true", index) -> { index += 4; true }
                text.startsWith("false", index) -> { index += 5; false }
                else -> throw JsonException("expected boolean at index $index")
            }
        }

        private fun readNull(): Any? {
            if (!text.startsWith("null", index)) throw JsonException("expected null at index $index")
            index += 4
            return null
        }

        private fun peek(): Char =
            if (atEnd()) '\u0000' else text[index]

        private fun expect(c: Char) {
            if (atEnd() || text[index] != c)
                throw JsonException("expected '$c' at index $index")
            index++
        }
    }
}

class JsonException(message: String) : RuntimeException(message)
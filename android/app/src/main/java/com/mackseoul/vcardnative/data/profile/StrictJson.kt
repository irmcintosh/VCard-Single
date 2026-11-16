package com.mackseoul.vcardnative.data.profile

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal object StrictJson {
    const val MAX_BYTES = 524_288

    fun decode(bytes: ByteArray, maxDepth: Int = 24, allowArrays: Boolean = false): Any? {
        require(maxDepth in 1..24)
        require(bytes.size <= MAX_BYTES) { "JSON is too large." }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return Parser(text, maxDepth, allowArrays).parse()
    }

    fun encode(value: Any?): ByteArray = stringify(value).toByteArray(Charsets.UTF_8).also {
        require(it.size <= MAX_BYTES)
    }

    private fun stringify(value: Any?): String = when (value) {
        null -> "null"
        is Boolean, is Long, is Int -> value.toString()
        is String -> buildString {
            append('"')
            for (c in value) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") {
            require(it.key is String)
            stringify(it.key) + ":" + stringify(it.value)
        }
        is List<*> -> value.joinToString(",", "[", "]") { stringify(it) }
        else -> error("Unsupported JSON value.")
    }

    private class Parser(val text: String, val maxDepth: Int, val allowArrays: Boolean) {
        var position = 0
        var nodes = 0
        fun parse(): Any? {
            val result = value(0)
            whitespace()
            require(position == text.length)
            return result
        }

        fun whitespace() {
            while (position < text.length && text[position] in " \r\n\t") position++
        }

        fun value(depth: Int): Any? {
            require(depth <= maxDepth && ++nodes <= 4096)
            whitespace()
            require(position < text.length)
            return when (text[position]) {
                '{' -> obj(depth + 1)
                '[' -> array(depth + 1)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                '-', in '0'..'9' -> number()
                else -> throw IllegalArgumentException("Unsupported JSON token.")
            }
        }

        fun literal(token: String, result: Any?): Any? {
            require(text.startsWith(token, position))
            position += token.length
            return result
        }

        fun number(): Long {
            val start = position
            if (text[position] == '-') position++
            require(position < text.length && text[position] in '0'..'9')
            if (text[position] == '0') position++ else {
                while (position < text.length && text[position] in '0'..'9') position++
            }
            // The storage schema has integer fields only; fractional/exponent tokens fail closed.
            return text.substring(start, position).toLong()
        }

        fun obj(depth: Int): Map<String, Any?> {
            require(depth <= maxDepth)
            position++
            whitespace()
            val result = linkedMapOf<String, Any?>()
            if (take('}')) return result
            while (true) {
                whitespace()
                require(position < text.length && text[position] == '"')
                val key = string()
                require(!result.containsKey(key)) { "Duplicate JSON key." }
                whitespace()
                require(take(':'))
                result[key] = value(depth)
                whitespace()
                if (take('}')) return result
                require(take(','))
            }

        }

        fun array(depth: Int): List<Any?> {
            require(allowArrays)
            require(depth <= maxDepth)
            position++
            whitespace()
            val result = mutableListOf<Any?>()
            if (take(']')) return result
            while (true) {
                result.add(value(depth))
                whitespace()
                if (take(']')) return result
                require(take(','))
            }
        }

        fun take(c: Char): Boolean {
            if (position < text.length && text[position] == c) {
                position++
                return true
            }
            return false
        }

        fun string(): String {
            require(take('"'))
            val result = StringBuilder()
            var closed = false
            while (position < text.length) {
                val c = text[position++]
                if (c == '"') {
                    closed = true
                    break
                }
                require(c.code >= 32)
                if (c != '\\') result.append(c) else {
                    require(position < text.length)
                    when (val escape = text[position++]) {
                        '"', '\\', '/' -> result.append(escape)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000C')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            require(position + 4 <= text.length)
                            val digits = text.substring(position, position + 4)
                            require(digits.all { it.digitToIntOrNull(16) != null })
                            result.append(digits.toInt(16).toChar())
                            position += 4
                        }
                        else -> throw IllegalArgumentException("Invalid JSON escape.")
                    }
                }
            }
            require(closed)
            var i = 0
            while (i < result.length) {
                val c = result[i++]
                if (c.isHighSurrogate()) require(i < result.length && result[i++].isLowSurrogate())
                else require(!c.isLowSurrogate())
            }
            return result.toString()
        }
    }
}

internal fun Any?.jsonObject(vararg keys: String): Map<String, Any?> {
    require(this is Map<*, *> && this.keys == keys.toSet()) { "Invalid JSON object fields." }
    @Suppress("UNCHECKED_CAST")
    return this as Map<String, Any?>
}

internal fun Map<String, Any?>.string(key: String): String = this[key] as? String
    ?: throw IllegalArgumentException("Invalid string.")
internal fun Map<String, Any?>.long(key: String): Long = this[key] as? Long
    ?: throw IllegalArgumentException("Invalid integer.")

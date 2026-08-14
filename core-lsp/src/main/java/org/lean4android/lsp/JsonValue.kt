package org.lean4android.lsp

import java.math.BigDecimal

sealed interface JsonValue {
    data class ObjectValue(val fields: Map<String, JsonValue>) : JsonValue
    data class ArrayValue(val values: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class NumberValue(val source: String) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object NullValue : JsonValue

    fun render(): String = when (this) {
        is ObjectValue -> fields.entries.joinToString(",", "{", "}") { (key, value) -> "${key.jsonString()}:${value.render()}" }
        is ArrayValue -> values.joinToString(",", "[", "]") { it.render() }
        is StringValue -> value.jsonString()
        is NumberValue -> source
        is BooleanValue -> value.toString()
        NullValue -> "null"
    }
}

class JsonParseException(message: String) : IllegalArgumentException(message)

object JsonValueParser {
    fun parse(source: String): JsonValue = Parser(source).parse()

    private class Parser(private val source: String) {
        private var offset = 0

        fun parse(): JsonValue {
            skipWhitespace()
            val value = value()
            skipWhitespace()
            if (offset != source.length) fail("Trailing JSON content")
            return value
        }

        private fun value(): JsonValue {
            if (offset >= source.length) fail("Expected JSON value")
            return when (source[offset]) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> JsonValue.StringValue(string())
                't' -> literal("true", JsonValue.BooleanValue(true))
                'f' -> literal("false", JsonValue.BooleanValue(false))
                'n' -> literal("null", JsonValue.NullValue)
                '-', in '0'..'9' -> number()
                else -> fail("Unexpected JSON token")
            }
        }

        private fun objectValue(): JsonValue.ObjectValue {
            offset++
            skipWhitespace()
            val fields = linkedMapOf<String, JsonValue>()
            if (take('}')) return JsonValue.ObjectValue(fields)
            while (true) {
                if (offset >= source.length || source[offset] != '"') fail("Expected object key")
                val key = string()
                if (fields.containsKey(key)) fail("Duplicate object key: $key")
                skipWhitespace()
                expect(':')
                skipWhitespace()
                fields[key] = value()
                skipWhitespace()
                if (take('}')) return JsonValue.ObjectValue(fields)
                expect(',')
                skipWhitespace()
            }
        }

        private fun arrayValue(): JsonValue.ArrayValue {
            offset++
            skipWhitespace()
            val values = mutableListOf<JsonValue>()
            if (take(']')) return JsonValue.ArrayValue(values)
            while (true) {
                values += value()
                skipWhitespace()
                if (take(']')) return JsonValue.ArrayValue(values)
                expect(',')
                skipWhitespace()
            }
        }

        private fun string(): String {
            expect('"')
            val result = StringBuilder()
            while (offset < source.length) {
                val character = source[offset++]
                when {
                    character == '"' -> return result.toString()
                    character == '\\' -> result.append(escape())
                    character.code < 0x20 -> fail("Unescaped control character")
                    else -> result.append(character)
                }
            }
            fail("Unterminated string")
        }

        private fun escape(): Char {
            if (offset >= source.length) fail("Unterminated escape")
            return when (val escaped = source[offset++]) {
                '"', '\\', '/' -> escaped
                'b' -> '\b'
                'f' -> '\u000c'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (offset + 4 > source.length) fail("Truncated Unicode escape")
                    val hex = source.substring(offset, offset + 4)
                    offset += 4
                    hex.toIntOrNull(16)?.toChar() ?: fail("Invalid Unicode escape")
                }
                else -> fail("Invalid escape")
            }
        }

        private fun number(): JsonValue.NumberValue {
            val start = offset
            if (take('-') && offset >= source.length) fail("Invalid number")
            if (take('0')) {
                if (offset < source.length && source[offset].isDigit()) fail("Leading zero in number")
            } else {
                digits(required = true)
            }
            if (take('.')) digits(required = true)
            if (offset < source.length && source[offset] in "eE") {
                offset++
                if (offset < source.length && source[offset] in "+-") offset++
                digits(required = true)
            }
            val value = source.substring(start, offset)
            runCatching { BigDecimal(value) }.getOrElse { fail("Invalid number") }
            return JsonValue.NumberValue(value)
        }

        private fun digits(required: Boolean) {
            val start = offset
            while (offset < source.length && source[offset].isDigit()) offset++
            if (required && start == offset) fail("Expected digit")
        }

        private fun <T : JsonValue> literal(text: String, value: T): T {
            if (!source.startsWith(text, offset)) fail("Invalid literal")
            offset += text.length
            return value
        }

        private fun skipWhitespace() {
            while (offset < source.length && source[offset] in " \t\r\n") offset++
        }

        private fun take(character: Char): Boolean =
            if (offset < source.length && source[offset] == character) { offset++; true } else false

        private fun expect(character: Char) {
            if (!take(character)) fail("Expected '$character'")
        }

        private fun fail(message: String): Nothing = throw JsonParseException("$message at byte/char $offset")
    }
}

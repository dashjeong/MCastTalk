package app.mcasttalk.windows.host

private val jsonNumberPattern =
    Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

sealed interface JsonScalar {
    data class StringValue(val value: String) : JsonScalar

    data class NumberValue(val raw: String) : JsonScalar

    data class BooleanValue(val value: Boolean) : JsonScalar

    data object NullValue : JsonScalar
}

class FlatJsonObject internal constructor(
    private val values: Map<String, JsonScalar>,
) {
    fun contains(name: String): Boolean = values.containsKey(name)

    fun requiredString(name: String): String =
        optionalString(name)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("$name is required")

    fun optionalString(name: String): String? =
        when (val value = values[name]) {
            null, JsonScalar.NullValue -> null
            is JsonScalar.StringValue -> value.value
            else -> throw IllegalArgumentException("$name must be a string")
        }

    fun requiredInt(name: String): Int {
        val value = values[name] as? JsonScalar.NumberValue
            ?: throw IllegalArgumentException("$name must be an integer")
        require(!value.raw.contains('.') && !value.raw.contains('e', true)) {
            "$name must be an integer"
        }
        return value.raw.toIntOrNull()
            ?: throw IllegalArgumentException("$name is outside the integer range")
    }

    fun optionalBoolean(name: String): Boolean? = when (val value = values[name]) {
        null, JsonScalar.NullValue -> null
        is JsonScalar.BooleanValue -> value.value
        else -> throw IllegalArgumentException("$name must be a boolean")
    }

    fun requiredBoolean(name: String): Boolean =
        optionalBoolean(name) ?: throw IllegalArgumentException("$name is required")
}

fun parseFlatJsonObject(
    text: String,
    maxChars: Int = MAX_CONTROL_MESSAGE_CHARS,
): FlatJsonObject {
    require(text.length <= maxChars) { "JSON object is too large" }
    return FlatJsonParser(text).parse()
}

fun encodeJson(value: Any?): String =
    when (value) {
        null -> "null"
        is String -> "\"" + escapeJsonString(value) + "\""
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(
            prefix = "{",
            postfix = "}",
            separator = ",",
        ) { entry ->
            val key = entry.key as? String
                ?: throw IllegalArgumentException("JSON object keys must be strings")
            "\"" + escapeJsonString(key) + "\":" + encodeJson(entry.value)
        }
        is Iterable<*> -> value.joinToString(
            prefix = "[",
            postfix = "]",
            separator = ",",
        ) { item -> encodeJson(item) }
        else -> throw IllegalArgumentException(
            "Unsupported JSON value type: " + value::class.qualifiedName
        )
    }

private fun escapeJsonString(value: String): String =
    buildString(value.length + 8) {
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else ->
                    if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
            }
        }
    }

private class FlatJsonParser(
    private val text: String,
) {
    private var index = 0

    fun parse(): FlatJsonObject {
        skipWhitespace()
        expect('{')
        skipWhitespace()
        val values = linkedMapOf<String, JsonScalar>()
        if (peek() == '}') {
            index++
            finish()
            return FlatJsonObject(values)
        }
        while (true) {
            skipWhitespace()
            val key = parseString()
            require(!values.containsKey(key)) { "Duplicate JSON key: $key" }
            skipWhitespace()
            expect(':')
            skipWhitespace()
            values[key] = parseScalar()
            skipWhitespace()
            when (val delimiter = next()) {
                ',' -> Unit
                '}' -> {
                    finish()
                    return FlatJsonObject(values)
                }
                else -> throw IllegalArgumentException(
                    "Expected ',' or '}' at position " + (index - 1) +
                        ", found '$delimiter'"
                )
            }
        }
    }

    private fun parseScalar(): JsonScalar =
        when (peek()) {
            '"' -> JsonScalar.StringValue(parseString())
            't' -> {
                expectLiteral("true")
                JsonScalar.BooleanValue(true)
            }
            'f' -> {
                expectLiteral("false")
                JsonScalar.BooleanValue(false)
            }
            'n' -> {
                expectLiteral("null")
                JsonScalar.NullValue
            }
            '{', '[' -> throw IllegalArgumentException(
                "Nested JSON values are not allowed in control messages"
            )
            else -> parseNumber()
        }

    private fun parseNumber(): JsonScalar.NumberValue {
        val start = index
        while (
            index < text.length &&
            text[index] !in charArrayOf(',', '}', ' ', '\t', '\r', '\n')
        ) {
            index++
        }
        val raw = text.substring(start, index)
        require(jsonNumberPattern.matches(raw)) {
            "Invalid JSON number at position $start"
        }
        return JsonScalar.NumberValue(raw)
    }

    private fun parseString(): String {
        expect('"')
        return buildString {
            while (index < text.length) {
                val character = next()
                when {
                    character == '"' -> return@buildString
                    character == '\\' -> append(parseEscape())
                    character.code < 0x20 -> throw IllegalArgumentException(
                        "Unescaped control character at position " + (index - 1)
                    )
                    else -> append(character)
                }
            }
            throw IllegalArgumentException("Unterminated JSON string")
        }
    }

    private fun parseEscape(): Char {
        val escape = next()
        return when (escape) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                require(index + 4 <= text.length) { "Incomplete unicode escape" }
                val hexadecimal = text.substring(index, index + 4)
                require(
                    hexadecimal.all {
                        it.isDigit() || it.lowercaseChar() in 'a'..'f'
                    }
                ) { "Invalid unicode escape" }
                index += 4
                hexadecimal.toInt(16).toChar()
            }
            else -> throw IllegalArgumentException("Invalid JSON escape: \\$escape")
        }
    }

    private fun expectLiteral(expected: String) {
        require(text.regionMatches(index, expected, 0, expected.length)) {
            "Expected $expected at position $index"
        }
        index += expected.length
    }

    private fun finish() {
        skipWhitespace()
        require(index == text.length) { "Unexpected trailing JSON content" }
    }

    private fun expect(expected: Char) {
        val actual = next()
        require(actual == expected) {
            "Expected '$expected' at position " + (index - 1) +
                ", found '$actual'"
        }
    }

    private fun next(): Char {
        require(index < text.length) { "Unexpected end of JSON" }
        return text[index++]
    }

    private fun peek(): Char {
        require(index < text.length) { "Unexpected end of JSON" }
        return text[index]
    }

    private fun skipWhitespace() {
        while (
            index < text.length &&
            text[index] in charArrayOf(' ', '\t', '\r', '\n')
        ) {
            index++
        }
    }
}

package app.guidecast.transmitter

internal fun parseStrictTranslationBatch(
    text: String,
    expectedTargets: List<String>
): Map<String, String> {
    require(expectedTargets.size in 1..8 && expectedTargets.all { it.matches(Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")) }) { "Invalid expected language targets" }
    if (text.length > 32000) {
        throw IllegalArgumentException("Input text exceeds maximum allowed length")
    }

    val expectedSet = HashSet<String>(expectedTargets.size)
    for (target in expectedTargets) {
        if (!expectedSet.add(target)) {
            throw IllegalArgumentException("Duplicate expected target key")
        }
    }

    val parser = StrictJsonParser(text, expectedSet)
    val parsedMap = parser.parseObject()

    if (parsedMap.size != expectedSet.size) {
        throw IllegalArgumentException("Key count mismatch with expected targets")
    }

    val result = LinkedHashMap<String, String>(expectedTargets.size)
    for (target in expectedTargets) {
        val value = parsedMap[target] ?: throw IllegalArgumentException("Missing expected target key")
        result[target] = value
    }

    return result
}

private class StrictJsonParser(
    private val text: String,
    private val expectedSet: Set<String>
) {
    private var cursor = 0
    private val length = text.length

    fun parseObject(): Map<String, String> {
        skipWhitespace()
        if (cursor >= length || text[cursor] != '{') {
            throw IllegalArgumentException("Invalid JSON syntax: expected object start")
        }
        cursor++

        val map = HashMap<String, String>()

        skipWhitespace()
        if (cursor < length && text[cursor] == '}') {
            cursor++
            skipWhitespace()
            if (cursor != length) {
                throw IllegalArgumentException("Invalid JSON syntax: trailing data after object")
            }
            return map
        }

        while (true) {
            skipWhitespace()
            if (cursor >= length || text[cursor] != '"') {
                throw IllegalArgumentException("Invalid JSON syntax: expected string key")
            }

            val key = parseString()

            skipWhitespace()
            if (cursor >= length || text[cursor] != ':') {
                throw IllegalArgumentException("Invalid JSON syntax: expected colon separator")
            }
            cursor++

            skipWhitespace()
            if (cursor >= length || text[cursor] != '"') {
                throw IllegalArgumentException("Invalid JSON syntax: expected string value")
            }

            val value = parseString(normalizeWhitespace = true)

            if (value.isBlank()) {
                throw IllegalArgumentException("Blank value not allowed")
            }
            if (value.length > 8000) {
                throw IllegalArgumentException("Value length exceeds limit")
            }

            if (!expectedSet.contains(key)) {
                throw IllegalArgumentException("Unexpected key in payload")
            }
            if (map.containsKey(key)) {
                throw IllegalArgumentException("Duplicate key detected")
            }
            map[key] = value

            skipWhitespace()
            if (cursor >= length) {
                throw IllegalArgumentException("Invalid JSON syntax: unterminated object")
            }

            val ch = text[cursor]
            if (ch == ',') {
                cursor++
                skipWhitespace()
                if (cursor < length && text[cursor] == '}') {
                    throw IllegalArgumentException("Invalid JSON syntax: trailing comma not allowed")
                }
            } else if (ch == '}') {
                cursor++
                break
            } else {
                throw IllegalArgumentException("Invalid JSON syntax: expected comma or closing brace")
            }
        }

        skipWhitespace()
        if (cursor != length) {
            throw IllegalArgumentException("Invalid JSON syntax: trailing data after object")
        }

        return map
    }

    private fun parseString(normalizeWhitespace: Boolean = false): String {
        cursor++ // consume opening quote
        val sb = StringBuilder()

        while (cursor < length) {
            val c = text[cursor]

            if (c == '"') {
                cursor++ // consume closing quote
                return sb.toString()
            }

            if (c == '\\') {
                cursor++
                if (cursor >= length) {
                    throw IllegalArgumentException("Invalid JSON syntax: unterminated escape sequence")
                }
                val esc = text[cursor++]
                when (esc) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'n', 'r', 't' -> {
                        if (!normalizeWhitespace) throw IllegalArgumentException("Control character escape not allowed")
                        sb.append(' ')
                    }
                    'b', 'f' -> {
                        throw IllegalArgumentException("Control character escape not allowed")
                    }
                    'u' -> {
                        val code = parseHex4()
                        if (code in 0xD800..0xDBFF) {
                            if (cursor + 2 <= length && text[cursor] == '\\' && text[cursor + 1] == 'u') {
                                cursor += 2
                                val lowCode = parseHex4()
                                if (lowCode in 0xDC00..0xDFFF) {
                                    sb.append(code.toChar())
                                    sb.append(lowCode.toChar())
                                } else {
                                    throw IllegalArgumentException("Invalid surrogate pair")
                                }
                            } else {
                                throw IllegalArgumentException("Invalid surrogate pair")
                            }
                        } else if (code in 0xDC00..0xDFFF) {
                            throw IllegalArgumentException("Unpaired surrogate")
                        } else {
                            if (normalizeWhitespace && code in listOf(9, 10, 13)) {
                                sb.append(' ')
                            } else if (Character.isISOControl(code)) {
                                throw IllegalArgumentException("Decoded control character not allowed")
                            } else sb.append(code.toChar())
                        }
                    }
                    else -> throw IllegalArgumentException("Invalid or malformed escape sequence")
                }
            } else if (c in '\uD800'..'\uDBFF') {
                cursor++
                if (cursor < length && text[cursor] in '\uDC00'..'\uDFFF') {
                    val lowChar = text[cursor++]
                    sb.append(c)
                    sb.append(lowChar)
                } else {
                    throw IllegalArgumentException("Unpaired surrogate")
                }
            } else if (c in '\uDC00'..'\uDFFF') {
                throw IllegalArgumentException("Unpaired surrogate")
            } else {
                cursor++
                if (Character.isISOControl(c)) {
                    throw IllegalArgumentException("Control character in string literal")
                }
                sb.append(c)
            }

            if (sb.length > 8000) {
                throw IllegalArgumentException("String exceeds maximum allowed length")
            }
        }

        throw IllegalArgumentException("Invalid JSON syntax: unterminated string")
    }

    private fun parseHex4(): Int {
        if (cursor + 4 > length) {
            throw IllegalArgumentException("Invalid JSON syntax: truncated unicode escape")
        }
        var value = 0
        for (i in 0 until 4) {
            val ch = text[cursor++]
            val digit = when (ch) {
                in '0'..'9' -> ch - '0'
                in 'a'..'f' -> ch - 'a' + 10
                in 'A'..'F' -> ch - 'A' + 10
                else -> throw IllegalArgumentException("Invalid hex digit in unicode escape")
            }
            value = (value shl 4) or digit
        }
        return value
    }

    private fun skipWhitespace() {
        while (cursor < length) {
            val c = text[cursor]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                cursor++
            } else {
                break
            }
        }
    }
}

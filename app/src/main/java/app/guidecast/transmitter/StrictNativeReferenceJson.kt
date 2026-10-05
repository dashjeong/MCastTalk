package app.guidecast.transmitter

import org.json.JSONArray
import org.json.JSONObject

internal fun validNativeReferenceField(field: String, value: String): Boolean =
    validContextUnicode(value) && !containsNativeContextCredentialLikeText(value) &&
        value.none { it.code == 127 || (it.code < 32 && (field != "text" || it !in "\n\r\t")) }

/** Validate every decoded field and serialize only that data; trailing input is never forwarded. */
internal fun strictNativeReferenceJson(raw: String): String = NativeReferenceReader(raw).read()

private class NativeReferenceReader(private val raw: String) {
    private var cursor = 0
    private fun fail(): Nothing = throw IllegalArgumentException("Invalid native reference data")
    private fun whitespace() { while (cursor < raw.length && raw[cursor] in " \t\r\n") cursor++ }
    private fun take(char: Char): Boolean {
        whitespace()
        return if (cursor < raw.length && raw[cursor] == char) { cursor++; true } else false
    }
    private fun expect(char: Char) { if (!take(char)) fail() }
    private fun string(): String {
        expect('"')
        val result = StringBuilder()
        while (cursor < raw.length) {
            val char = raw[cursor++]
            if (char == '"') return result.toString().also { if (!validContextUnicode(it)) fail() }
            if (char.code < 32) fail()
            if (char != '\\') { result.append(char); continue }
            if (cursor == raw.length) fail()
            result.append(when (val escaped = raw[cursor++]) {
                '"', '\\', '/' -> escaped
                'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                'u' -> {
                    if (raw.length - cursor < 4) fail()
                    var code = 0
                    repeat(4) { code = code * 16 + (raw[cursor++].digitToIntOrNull(16) ?: fail()) }
                    code.toChar()
                }
                else -> fail()
            })
        }
        fail()
    }
    private fun row(): JSONObject {
        expect('{')
        val fields = linkedMapOf<String, String>()
        while (true) {
            val key = string()
            if (key !in setOf("title", "kind", "text") || key in fields) fail()
            expect(':')
            val value = string()
            if (!validNativeReferenceField(key, value)) fail()
            fields[key] = value
            if (take('}')) break
            expect(',')
        }
        if (fields.keys != setOf("title", "kind", "text") || fields["text"].isNullOrBlank()) fail()
        return JSONObject().also { objectValue -> listOf("title", "kind", "text").forEach { objectValue.put(it, fields.getValue(it)) } }
    }
    fun read(): String {
        if (raw.length > MAX_NATIVE_REFERENCE_CHARS) fail()
        expect('{')
        if (string() != "references") fail()
        expect(':'); expect('[')
        val rows = JSONArray()
        while (true) {
            if (rows.length() >= MAX_NATIVE_REFERENCE_ENTRIES) fail()
            rows.put(row())
            if (take(']')) break
            expect(',')
        }
        expect('}'); whitespace()
        if (cursor != raw.length) fail()
        return JSONObject().put("references", rows).toString().also {
            if (it.length > MAX_NATIVE_REFERENCE_CHARS) fail()
        }
    }
}

package app.guidecast.transmitter

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringWriter
import java.nio.charset.CodingErrorAction

/** Local user-selected files only. Strict streaming JSON avoids deeply nested parser input. */
internal object SpeechCorrectionArchive {
    const val MAX_BYTES = 8 * 1024 * 1024
    fun encode(entries: List<SpeechCorrectionEntry>): String {
        require(entries.size <= 500)
        val out = StringWriter()
        JsonWriter(out).use { writer ->
            writer.beginObject().name("schema").value(1L).name("entries").beginArray()
            entries.forEach { row ->
                writer.beginObject()
                    .name("language").value(row.languageTag)
                    .name("recognized").value(row.recognizedText)
                    .name("corrected").value(row.correctedText)
                    .name("hint").value(row.hint)
                    .name("enabled").value(row.enabled)
                    .endObject()
            }
            writer.endArray().endObject()
        }
        return out.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }
    }

    fun decode(input: InputStream, profile: String): List<SpeechCorrectionDraft> {
        val limited = object : java.io.FilterInputStream(input) {
            var bytes = 0
            private fun count(n: Int) { if (n > 0) { bytes += n; require(bytes <= MAX_BYTES) } }
            override fun read(): Int = super.read().also { if (it >= 0) count(1) }
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                `in`.read(b, off, minOf(len, MAX_BYTES - bytes + 1)).also(::count)
        }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return JsonReader(InputStreamReader(limited, decoder)).use { reader ->
            reader.isLenient = false
            val top = mutableSetOf<String>()
            var schema = 0
            val rows = ArrayList<SpeechCorrectionDraft>()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                require(top.add(name))
                when (name) {
                    "schema" -> { require(reader.peek() == JsonToken.NUMBER); schema = reader.nextInt() }
                    "entries" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            require(rows.size < 500)
                            reader.beginObject()
                            val fields = mutableSetOf<String>()
                            var language: String? = null
                            var original: String? = null
                            var corrected: String? = null
                            var hint: String? = null
                            var enabled: Boolean? = null
                            fun string(): String {
                                require(reader.peek() == JsonToken.STRING)
                                return reader.nextString().also { require(it.length <= 4_000) }
                            }
                            while (reader.hasNext()) {
                                val field = reader.nextName()
                                require(fields.add(field))
                                when (field) {
                                    "language" -> language = string()
                                    "recognized" -> original = string()
                                    "corrected" -> corrected = string()
                                    "hint" -> if (reader.peek() == JsonToken.NULL) reader.nextNull() else hint = string()
                                    "enabled" -> { require(reader.peek() == JsonToken.BOOLEAN); enabled = reader.nextBoolean() }
                                    else -> error("unsupported_field")
                                }
                            }
                            reader.endObject()
                            require(fields == setOf("language", "recognized", "corrected", "hint", "enabled"))
                            rows += SpeechCorrectionDraft(profile, requireNotNull(language),
                                requireNotNull(original), requireNotNull(corrected), hint, requireNotNull(enabled), null)
                        }
                        reader.endArray()
                    }
                    else -> error("unsupported_schema")
                }
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT && schema == 1 && top.size == 2 && rows.isNotEmpty())
            // The repository validates NFC, codepoints, duplicate keys and aggregate bounds
            // atomically before replacing the explicitly confirmed profile.
            SpeechCorrectionValidation.batch(profile, rows)
        }
    }
}

package app.guidecast.transmitter

import org.json.JSONObject

internal fun voiceNoteStoredCharacters(line: VoiceNoteLine): Long =
    line.original.length.toLong() + line.originalTranscript.length + line.translation.length + line.speaker.length +
        line.translations.values.sumOf { it.text.length.toLong() + it.sourceFingerprint.length + it.model.length }

internal fun putVoiceNoteProcessingMetadata(row: JSONObject, line: VoiceNoteLine): JSONObject = row.apply {
    put("originalTranscript", line.originalTranscript)
    put("translations", JSONObject().apply { line.translations.forEach { (language, value) ->
        put(language, JSONObject().put("text", value.text).put("sourceFingerprint", value.sourceFingerprint)
            .put("model", value.model).put("manuallyEdited", value.manuallyEdited).put("fromOriginal", value.fromOriginal))
    } })
}

/** Additive metadata: old notes/backups retain their existing text without guessing lost history. */
internal fun readVoiceNoteProcessingMetadata(row: JSONObject, line: VoiceNoteLine): VoiceNoteLine {
    val original = if (row.has("originalTranscript")) row.getString("originalTranscript") else line.original
    require(original.length <= 65_536)
    require(original.none { it == '\u0000' || it.code < 32 && it !in "\n\r\t" })
    val variants = if (!row.has("translations")) emptyMap() else {
        val values = row.getJSONObject("translations")
        require(values.length() <= VOICE_NOTE_LANGUAGES.size)
        values.keys().asSequence().associateWith { language ->
            require(language in VOICE_NOTE_LANGUAGES)
            val value = values.getJSONObject(language)
            val text = value.getString("text")
            val fingerprint = value.getString("sourceFingerprint")
            val model = value.optString("model")
            require(text.length in 1..65_536 && fingerprint.matches(Regex("[a-f0-9]{64}")) && model.length <= 160)
            require(text.none { it == '\u0000' || it.code < 32 && it !in "\n\r\t" })
            require(model.none { it.code < 32 })
            VoiceNoteTranslatedText(text, fingerprint, model, value.optBoolean("manuallyEdited"), value.optBoolean("fromOriginal"))
        }
    }
    return line.copy(originalTranscript = original, translations = variants)
}

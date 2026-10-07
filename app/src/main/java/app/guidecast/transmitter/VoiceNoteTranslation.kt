package app.guidecast.transmitter

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal fun FileTranscriptionResult.voiceNoteLines(manualLanguage: String? = null): List<VoiceNoteLine> = segments.map {
    // A file-level language inferred from other segments is not evidence for an untagged segment.
    VoiceNoteLine(it.startMs, it.endMs, it.text, it.languageTag ?: manualLanguage, timingEstimated = it.timingEstimated)
}

/** Resume only unfinished lines; durable batches bound metadata writes for long recordings. */
internal suspend fun translateVoiceNote(
    note: VoiceNote,
    target: String,
    save: suspend (VoiceNote) -> Unit,
    modelLabel: String = "",
    fromOriginal: Boolean = false,
    force: Boolean = false,
    translate: suspend (List<VoiceNoteLine>, suspend (Int, String) -> Unit) -> Unit,
): VoiceNote {
    require(target in VOICE_NOTE_LANGUAGES)
    var updated = selectVoiceNoteTranslation(note, target)
    val replacingBasis = updated.lines.any { line ->
        line.translation.isNotBlank() && (line.translations[target]?.fromOriginal ?: false) != fromOriginal
    }
    val replacingLanguage = target != note.targetLanguage || force || replacingBasis
    updated = updated.copy(lines = updated.lines.map { line ->
        val variant = line.translations[target]
        val source = if (fromOriginal) line.originalTranscript else line.original
        val current = variant != null && variant.fromOriginal == fromOriginal &&
            variant.sourceFingerprint == voiceNoteSourceFingerprint(source)
        when {
            force -> line.copy(translation = "")
            current && line.translation.isBlank() -> line.copy(translation = variant!!.text)
            variant != null && !current -> line.copy(translation = "")
            variant == null && fromOriginal && line.originalTranscript != line.original -> line.copy(translation = "")
            else -> line
        }
    })
    val pending = updated.lines.indices.filter { updated.lines[it].translation.isBlank() }
    if (pending.isEmpty()) {
        if (updated != note) save(updated)
        return updated
    }
    // A language replacement is a transaction: keep the existing translations until
    // the entire replacement succeeds. Same-language retries still checkpoint progress.
    if (!replacingLanguage) save(updated)
    var dirty = false
    var sinceSave = 0
    try {
        translate(pending.map { updated.lines[it] }) { index, text ->
            currentCoroutineContext().ensureActive()
            require(index in pending.indices && text.isNotBlank() && text.length <= 65_536)
            val ordinal = pending[index]
            updated = updated.copy(lines = updated.lines.mapIndexed { i, line ->
                if (i == ordinal) line.copy(translation = text, translations = line.translations +
                    (target to VoiceNoteTranslatedText(text, voiceNoteSourceFingerprint(
                        if (fromOriginal) line.originalTranscript else line.original), modelLabel, fromOriginal = fromOriginal))) else line
            })
            dirty = true
            if (!replacingLanguage && ++sinceSave >= 8) { save(updated); dirty = false; sinceSave = 0 }
        }
        check(updated.lines.all { it.translation.isNotBlank() })
        dirty = true
        save(updated)
        dirty = false
        return updated
    } finally {
        // Preserve completed work even if the user leaves, cancels, or the next language fails.
        if (dirty && !replacingLanguage) withContext(NonCancellable) { save(updated) }
    }
}

/** Changing the visible language never removes another language or the recognition transcript. */
internal fun selectVoiceNoteTranslation(note: VoiceNote, target: String): VoiceNote {
    require(target in VOICE_NOTE_LANGUAGES)
    if (target == note.targetLanguage) return note
    return note.copy(targetLanguage = target, lines = note.lines.map { old ->
        val line = old.archiveTranslation(note.targetLanguage)
        line.copy(translation = line.translations[target]?.takeIf { line.translationIsCurrent(target) }?.text.orEmpty())
    })
}

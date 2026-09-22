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
    translate: suspend (List<VoiceNoteLine>, suspend (Int, String) -> Unit) -> Unit,
): VoiceNote {
    require(target in VOICE_NOTE_LANGUAGES)
    val replacingLanguage = target != note.targetLanguage
    var updated = if (!replacingLanguage) note else note.copy(targetLanguage = target,
        lines = note.lines.map { it.copy(translation = "") })
    val pending = updated.lines.indices.filter { updated.lines[it].translation.isBlank() }
    if (pending.isEmpty()) return updated
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
                if (i == ordinal) line.copy(translation = text) else line
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

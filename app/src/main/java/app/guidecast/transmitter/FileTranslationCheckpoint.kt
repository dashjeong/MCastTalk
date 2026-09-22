package app.guidecast.transmitter

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Completed sentences survive cancellation; a complete older translation survives refresh failure. */
internal suspend fun translateFileTargetWithCheckpoints(
    entry: FileLibraryEntry,
    target: String,
    mode: FileTranslationEngine,
    save: suspend (FileLibraryEntry) -> Unit,
    translate: suspend (FileLibraryEntry, suspend (Int, String) -> Unit) -> FileScriptTranslation,
): FileLibraryEntry {
    val previous = entry.translations[target]
    val resume = previous?.takeIf {
        entry.translationModes[target] == mode && it.size == entry.segments.size && it.any(String::isBlank)
    }
    val lines = (resume ?: List(entry.segments.size) { "" }).toMutableList()
    val mayCheckpoint = previous == null || resume != null
    val pending = lines.indices.filter { lines[it].isBlank() }
    val failurePrefix = "$target 번역을 완료하지 못했습니다."
    fun snapshot(engine: FileTranslationEngine, notes: List<String>) = entry.copy(
        translations = entry.translations + (target to lines.toList()),
        translationModes = entry.translationModes + (target to engine),
        qualityNotes = (entry.qualityNotes.filterNot { it.startsWith(failurePrefix) } + notes).distinct(),
    )
    var dirty = false
    var sinceSave = 0
    suspend fun checkpoint() {
        save(snapshot(mode, listOf("$failurePrefix 완료된 문장은 보관했습니다. 다시 선택하면 이어서 번역합니다.")))
        dirty = false
        sinceSave = 0
    }
    try {
        val result = translate(entry.copy(segments = pending.map { entry.segments[it] },
            translations = emptyMap(), translationModes = emptyMap())) { index, text ->
            currentCoroutineContext().ensureActive()
            require(index in pending.indices && text.isNotBlank())
            lines[pending[index]] = text
            dirty = true
            if (mayCheckpoint && ++sinceSave >= 8) checkpoint()
        }
        check(lines.all(String::isNotBlank)) { "완료되지 않은 번역 문장이 있습니다." }
        val completed = snapshot(result.engine, result.notes)
        save(completed)
        dirty = false
        return completed
    } finally {
        if (dirty && mayCheckpoint) withContext(NonCancellable) { checkpoint() }
    }
}

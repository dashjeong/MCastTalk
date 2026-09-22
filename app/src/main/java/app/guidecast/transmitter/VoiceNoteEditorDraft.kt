package app.guidecast.transmitter

import androidx.compose.runtime.mutableStateOf

/** Retained only in the ViewModel: rotation preserves drafts without putting long private text in a Bundle. */
internal class VoiceNoteEditorDraft {
    var noteId: String? = null
    val speakerIndex = mutableStateOf<Int?>(null)
    val speaker = mutableStateOf("")
    val renameTitle = mutableStateOf<String?>(null)
    val editIndex = mutableStateOf<Int?>(null)
    val editOriginal = mutableStateOf("")
    val editTranslation = mutableStateOf("")
    val translationEdited = mutableStateOf(false)
    val editAttempted = mutableStateOf(false)
    val renameAttempted = mutableStateOf(false)
    val speakerAttempted = mutableStateOf(false)

    fun select(id: String?) {
        if (id == noteId) return
        noteId = id
        speakerIndex.value = null; speaker.value = ""; renameTitle.value = null; editIndex.value = null
        editOriginal.value = ""; editTranslation.value = ""; translationEdited.value = false
        editAttempted.value = false; renameAttempted.value = false; speakerAttempted.value = false
    }
}

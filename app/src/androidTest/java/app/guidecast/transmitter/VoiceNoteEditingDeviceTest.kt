package app.guidecast.transmitter

import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class VoiceNoteEditingDeviceTest {
    @Test fun correctionsResumeWithoutAsrAndDeletingAnotherNoteKeepsCurrentWork() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = VoiceNoteRepository(File(context.filesDir, "voice-notes"))
        val initial = repository.create("합성 편집 시험", "en-US", "en-US")
        val neighbor = repository.create("삭제할 합성 노트", "en-US", "en-US")
        val exportFile = File(context.cacheDir, "synthetic-note-export-${initial.id}.txt")
        repository.save(initial.copy(interrupted = false, durationMs = 2_000, lines = listOf(
            VoiceNoteLine(0, 1_000, "misspelled", "en-US", "stale", "Guide"),
            VoiceNoteLine(1_000, 2_000, "Keep", "en-US", "User translation", "Guest", edited = true))))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        try {
            val model = withContext(Dispatchers.Main) { ViewModelProvider(activity)[VoiceNoteViewModel::class.java] }
            withTimeout(35_000) { model.state.first { !it.unavailable } }
            withContext(Dispatchers.Main) { model.open(initial.id) }
            withTimeout(10_000) { model.state.first { it.selected?.id == initial.id && !it.busy } }
            val blockedWrite = File(context.filesDir, "voice-notes/${initial.id}.json.new")
            assertTrue(blockedWrite.mkdir())
            var savedCallback = false
            try {
                withContext(Dispatchers.Main) { model.editLine(0, "Unsaved correction", "", false) { savedCallback = true } }
                withTimeout(10_000) { model.state.first { !it.busy && it.message != null } }
                assertFalse("Failed persistence must not close the editor", savedCallback)
                withContext(Dispatchers.Main) { model.rename("Unsaved title") { savedCallback = true } }
                withTimeout(10_000) { model.state.first { !it.busy && it.message != null } }
                assertFalse("Failed title persistence must retain the draft", savedCallback)
                withContext(Dispatchers.Main) { model.setSpeaker(0, "Unsaved speaker") { savedCallback = true } }
                withTimeout(10_000) { model.state.first { !it.busy && it.message != null } }
                assertFalse("Failed speaker persistence must retain the draft", savedCallback)
            } finally { assertTrue(blockedWrite.delete()) }
            assertEquals("misspelled", repository.load(initial.id).lines.first().original)
            assertEquals(initial.title, repository.load(initial.id).title)
            assertEquals("Guide", repository.load(initial.id).lines.first().speaker)
            withContext(Dispatchers.Main) { model.editLine(0, "Corrected", "stale", false) }
            withTimeout(10_000) { model.state.first { it.selected?.lines?.first()?.original == "Corrected" && !it.busy } }
            assertEquals("", repository.load(initial.id).lines.first().translation)
            // Same-language path exercises the real translator and persistence without a network/model dependency.
            withContext(Dispatchers.Main) { model.translateRemaining("en-US") }
            withTimeout(20_000) { model.state.first { it.selected?.lines?.first()?.translation == "Corrected" && !it.busy } }
            val persisted = repository.load(initial.id)
            assertEquals("User translation", persisted.lines[1].translation)
            assertEquals("Guide", persisted.lines[0].speaker)
            assertTrue(persisted.lines[0].edited)
            withContext(Dispatchers.Main) { model.rename("새 회의 제목") }
            withTimeout(10_000) { model.state.first { it.selected?.title == "새 회의 제목" && !it.busy } }
            assertEquals("새 회의 제목", repository.load(initial.id).title)
            withContext(Dispatchers.Main) {
                assertTrue(model.prepareExport("txt", VoiceNoteExportOptions(translations = false)))
                model.rename("파일 선택 중 변경한 제목")
            }
            withTimeout(10_000) { model.state.first { it.selected?.title == "파일 선택 중 변경한 제목" && !it.busy } }
            withContext(Dispatchers.Main) { model.exportPrepared(android.net.Uri.fromFile(exportFile), "txt") }
            withTimeout(10_000) { model.state.first { !it.busy && it.message?.contains("TXT 파일을 저장") == true } }
            assertTrue(exportFile.readText().startsWith("새 회의 제목\n"))
            assertFalse(exportFile.readText().contains("User translation"))
            assertEquals("파일 선택 중 변경한 제목", repository.load(initial.id).title)
            withContext(Dispatchers.Main) { model.delete(neighbor.id) }
            withTimeout(10_000) { model.state.first { it.library.none { n -> n.id == neighbor.id } && !it.busy } }
            assertEquals(initial.id, model.state.value.selected?.id)
            val exported = java.io.StringWriter()
            writeVoiceNoteExport(exported, persisted, "txt", VoiceNoteExportOptions())
            assertTrue(exported.toString().contains("[Guide] Corrected"))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            repository.delete(initial.id)
            repository.delete(neighbor.id)
            exportFile.delete()
        }
    }
}

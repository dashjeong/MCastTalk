package app.guidecast.transmitter

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class VoiceNoteRepositoryDeviceTest {
    @Test fun noteSurvivesReopenAndDeleteRemovesOnlyItsAudio() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "note-test-${UUID.randomUUID()}")
        try {
            val repository = VoiceNoteRepository(root)
            val note = repository.create("합성 시험", null, "ko-KR")
            val neighbor = repository.create("유지할 노트", "en-US", "ko-KR")
            VoiceNoteWav(repository.audio(note.id)).use { it.append(ByteArray(32_000) { 1 }, 32_000) }
            val recovered = repository.recover(note.id)
            assertEquals(1_000L, recovered.durationMs)
            assertFalse(recovered.interrupted)
            repository.save(recovered.copy(lines = listOf(VoiceNoteLine(0, 1_000, "Hello", "en-US", "안녕", "화자 1"))))
            val reopened = VoiceNoteRepository(root)
            assertEquals("화자 1", reopened.load(note.id).lines.single().speaker)
            assertTrue(reopened.list().all { it.lines.isEmpty() })
            reopened.delete(note.id)
            assertFalse(reopened.audio(note.id).exists())
            assertEquals(neighbor.id, reopened.list().single().id)
            assertThrows(IllegalArgumentException::class.java) { reopened.audio("../outside") }
        } finally { root.deleteRecursively() }
    }
}

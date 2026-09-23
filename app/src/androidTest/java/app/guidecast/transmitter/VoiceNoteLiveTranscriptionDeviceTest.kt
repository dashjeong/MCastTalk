package app.guidecast.transmitter

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.stream.PcmAudioFrame
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

/** Real Korean STT and WAV persistence; digital PCM does not prove physical microphone acoustics. */
class VoiceNoteLiveTranscriptionDeviceTest {
    @Test fun preparedKoreanRecognitionWritesLiveNoteBeforeRecordingEnds() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as GuideCastApplication
        val pcm = instrumentation.context.assets.open("fixtures/fleurs-ko-1959.pcm").use { it.readBytes() }
        assertEquals(224_640, pcm.size)
        assertEquals("b35aa5acf7ff72a4ec1b68415957ac9e7fe89268312cc8f5e5325a88acea841f",
            MessageDigest.getInstance("SHA-256").digest(pcm).joinToString("") { "%02x".format(it) })
        val repository = VoiceNoteRepository(File(app.cacheDir, "synthetic-live-note-${System.nanoTime()}"))
        var note = repository.create("합성 PCM 실시간 노트 시험", "ko-KR", "en-US")
        val duration = AtomicLong(0)
        val ended = AtomicBoolean(false)
        var visibleBeforeEnd = false
        var savedBeforeEnd = false
        try {
            withTimeout(180_000) {
                withPreparedVoiceNoteRecognition(app, "ko-KR") { engine ->
                    val startedAt = SystemClock.elapsedRealtimeNanos()
                    VoiceNoteWav(repository.audio(note.id)).use { wav ->
                        val inputEnded = CompletableDeferred<Unit>()
                        val frames = flow {
                            var offset = 0
                            while (offset < pcm.size) {
                                val bytes = pcm.copyOfRange(offset, minOf(offset + 640, pcm.size))
                                wav.append(bytes, bytes.size)
                                duration.set(wav.byteCount / 32)
                                emit(PcmAudioFrame(bytes, SystemClock.elapsedRealtimeNanos()))
                                offset += bytes.size
                                delay(20)
                            }
                            repeat(250) {
                                val silence = ByteArray(640)
                                wav.append(silence, silence.size)
                                duration.set(wav.byteCount / 32)
                                emit(PcmAudioFrame(silence, SystemClock.elapsedRealtimeNanos()))
                                delay(20)
                            }
                            ended.set(true)
                            inputEnded.complete(Unit)
                        }
                        coroutineScope {
                            val recognizer = launch {
                                collectVoiceNoteLiveTranscript(engine, frames, "ko-KR", startedAt, duration::get) { snapshot ->
                                    if (!ended.get() && (snapshot.partial.isNotBlank() || snapshot.lines.isNotEmpty())) visibleBeforeEnd = true
                                    if (snapshot.lines.isNotEmpty()) {
                                        note = note.copy(lines = snapshot.lines, durationMs = duration.get())
                                        repository.save(note)
                                        if (!ended.get()) savedBeforeEnd = repository.load(note.id).lines.isNotEmpty()
                                    }
                                }
                            }
                            try {
                                inputEnded.await()
                                // Live engines remain subscribed at EOF. Match the note controller's
                                // explicit stop and bounded final drain instead of requiring file semantics.
                                withTimeoutOrNull(8_000) { recognizer.join() }
                            } finally {
                                recognizer.cancelAndJoin()
                            }
                        }
                    }
                }
            }
            note = note.copy(interrupted = false, durationMs = duration.get())
            repository.save(note)
            val reloaded = repository.load(note.id)
            assertTrue("Text must arrive while PCM recording is still running", visibleBeforeEnd)
            assertTrue("A committed line must be saved before recording ends", savedBeforeEnd)
            val transcript = reloaded.lines.joinToString(" ") { it.original }
            assertTrue("Actual Korean recognition must match the fixture's meaning-bearing words",
                listOf("관계자", "조언", "표지판", "안전", "경고", "주의").count { it in transcript } >= 3)
            assertEquals(duration.get() * 32 + 44, repository.audio(note.id).length())
            assertTrue(reloaded.lines.all { it.startMs >= 0 && it.endMs <= reloaded.durationMs })
        } finally {
            repository.delete(note.id)
            repository.audio(note.id).parentFile?.delete()
        }
    }
}

package app.guidecast.transmitter

import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class VoiceNotePlaybackDeviceTest {
    @Test fun segmentPlaybackStartsAtRequestedPositionAndBackgroundStopsIt() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as GuideCastApplication
        val repository = VoiceNoteRepository(File(app.filesDir, "voice-notes"))
        val note = repository.create("합성 재생 회귀", "en-US", "ko-KR")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        try {
            val pcm = ByteArray(16_000 * 2 * 8)
            for (i in 0 until pcm.size / 2) {
                val sample = (kotlin.math.sin(i * 2.0 * Math.PI * 440 / 16_000) * 4_000).toInt()
                pcm[i * 2] = sample.toByte(); pcm[i * 2 + 1] = (sample shr 8).toByte()
            }
            VoiceNoteWav(repository.audio(note.id)).use { it.append(pcm, pcm.size) }
            repository.recover(note.id)
            val model = withContext(Dispatchers.Main) { ViewModelProvider(activity)[VoiceNoteViewModel::class.java] }
            withTimeout(35_000) { model.state.first { !it.unavailable } }
            withContext(Dispatchers.Main) { model.open(note.id) }
            withTimeout(10_000) { model.state.first { it.selected?.id == note.id && !it.busy } }
            withContext(Dispatchers.Main) { model.playFrom(2_000) }
            val playing = withTimeout(10_000) { model.state.first { it.playback.isPlaying && it.playback.positionMs >= 1_900 } }
            assertTrue(playing.playback.positionMs < 5_000)
            withContext(Dispatchers.Main) { model.playPause(); model.playFrom(4_000) }
            withTimeout(10_000) { model.state.first { it.playback.isPlaying && it.playback.positionMs >= 3_900 } }
            withContext(Dispatchers.Main) { model.pauseForBackground() }
            delay(500)
            assertFalse(model.state.value.playback.isPlaying)
            assertFalse(app.localVoiceNoteWorkActive.value)
            withContext(Dispatchers.Main) { model.setPlaybackSpeed(1.25f); model.skipPlayback(-10_000) }
            withTimeout(5_000) { model.state.first { it.playback.positionMs == 0L } }
            assertEquals(1.25f, model.state.value.playback.speed)
            assertFalse(model.state.value.playback.isPlaying)
            withContext(Dispatchers.Main) { model.skipPlayback(10_000) }
            withTimeout(5_000) { model.state.first { it.playback.positionMs >= 7_900 } }
            assertTrue(model.state.value.playback.positionMs <= model.state.value.playback.durationMs)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            repository.delete(note.id)
        }
    }
}

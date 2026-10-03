package app.guidecast.transmitter

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.audio.AudioCaptureConfig
import app.guidecast.core.audio.MicrophoneFocusStatus
import app.guidecast.core.audio.pcmS16LeSignalStats
import java.io.File
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** No recordings or speech text leave capture. This checks API acceptance and PCM, not isolation. */
class MicrophoneFocusDeviceTest {
    @Test fun focusedAndDefaultMicrophoneKeepValidContinuousPcm(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as GuideCastApplication
        check(app.broadcastRuntime.state.value.inputPhase == InputPhase.IDLE) {
            "Stop the input before testing; this fixture must not interrupt an operator session."
        }
        val manager = context.getSystemService(AudioManager::class.java)
        val microphone = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .first { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        val rows = JSONArray()
        for (enabled in listOf(false, true)) {
            var frameCount = 0
            var sampleCount = 0L
            var nonSilentFrames = 0
            var previousTimestamp = -1L
            var observedFocus = MicrophoneFocusStatus.OFF
            val started = SystemClock.elapsedRealtime()
            withTimeout(15_000) {
                app.audioCaptureEngine.frames(microphone.id, AudioCaptureConfig(nearSpeakerFocus = enabled))
                    .takeWhile { SystemClock.elapsedRealtime() - started < 2_000 }
                    .collect { frame ->
                        assertEquals(16_000, frame.sampleRateHz)
                        assertEquals("S16 mono sample must be complete", 0, frame.bytes.size % 2)
                        assertTrue(frame.bytes.isNotEmpty())
                        assertTrue("Capture frame order must advance", frame.capturedAtElapsedRealtimeNanos > previousTimestamp)
                        previousTimestamp = frame.capturedAtElapsedRealtimeNanos
                        frameCount++
                        sampleCount += frame.bytes.size / 2
                        if (frame.bytes.pcmS16LeSignalStats().rms > 0.002f) nonSilentFrames++
                        observedFocus = app.audioCaptureEngine.processingStatus.value.microphoneFocusStatus
                    }
            }
            assertTrue("Recording must continue when focus requests are rejected", frameCount >= 2)
            if (enabled) assertTrue(observedFocus != MicrophoneFocusStatus.OFF)
            else assertEquals(MicrophoneFocusStatus.OFF, observedFocus)
            rows.put(JSONObject().put("focusEnabled", enabled).put("requestStatus", observedFocus.name)
                .put("sampleRateHz", 16_000).put("encoding", "pcm_s16le").put("channels", 1)
                .put("frames", frameCount).put("samples", sampleCount).put("nonSilentFrames", nonSilentFrames)
                .put("elapsedMs", SystemClock.elapsedRealtime() - started))
        }
        val report = JSONObject().put("model", Build.MODEL).put("api", Build.VERSION.SDK_INT)
            .put("acousticSpeakerIsolationVerified", false).put("rawAudioSaved", false).put("rows", rows)
        val output = File(context.getExternalFilesDir(null), "benchmark/microphone-focus-${System.currentTimeMillis()}.json")
        output.parentFile?.mkdirs()
        output.writeText(report.toString(2))
    }
}

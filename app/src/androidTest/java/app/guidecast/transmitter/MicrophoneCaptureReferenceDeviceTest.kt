package app.guidecast.transmitter

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Independent capture diagnostic, not a product journey or input-integrity pass.
 * Host must disable the real microphone and inject only the
 * pinned public fixture on a dedicated emulator. Host waveform comparison is required.
 */
class MicrophoneCaptureReferenceDeviceTest {
    @Suppress("MissingPermission")
    @Test fun capturePublicFixtureToMemoryWithoutRecognitionOrPeriodicDiskWrites() {
        assumeTrue("Requires the dedicated public-fixture host injector",
            InstrumentationRegistry.getArguments().getString("publicReferenceFixture") == "fleurs-ko")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("app.guidecast.transmitter.alpha", context.packageName)
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        val fixture = instrumentation.context.assets.open("fixtures/fleurs-ko-1959.pcm").use { it.readBytes() }
        val fixtureSha = "b35aa5acf7ff72a4ec1b68415957ac9e7fe89268312cc8f5e5325a88acea841f"
        assertEquals(fixtureSha, sha256(fixture))
        val output = requireNotNull(context.getExternalFilesDir(null))
        val marker = File(output, "reference-microphone-ready.txt")
        marker.delete()
        val report = JSONObject().put("fixtureSha256", fixtureSha).put("sdk", Build.VERSION.SDK_INT)
            .put("inputIntegrityVerified", false).put("physicalMicrophoneClaim", false)
            .put("executionCompleted", false).put("sampleRate", 16_000).put("channels", 1).put("encoding", "PCM_S16LE")
        var recorder: AudioRecord? = null
        var foregroundActivity: Activity? = null
        var lifecycleCallback: ActivityLifecycleCallback? = null
        val foregroundLost = AtomicBoolean(false)
        val memory = ByteArray(1_024_000) // Fixed cap: twice the expected 16-second capture.
        val buffer = ByteArray(3_200)
        var bytes = 0
        var calls = 0L
        var emptyReads = 0L
        var maxGap = 0L
        var maxReadTime = 0L
        var timestampCount = 0
        var timestampFailures = 0
        var backwardsTimestamps = 0
        var silencedObservations = 0
        var firstFrame = 0L
        var lastFrame = 0L
        var firstTimestampNanos = 0L
        var lastTimestampNanos = 0L
        val timestamp = AudioTimestamp()
        try {
            // am instrument may restart the process after a host-side am start. Establish
            // foreground here, without opening the recording UI or starting an STT service.
            val activity = instrumentation.startActivitySync(Intent()
                .setClassName(context, "app.guidecast.transmitter.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            foregroundActivity = activity
            instrumentation.waitForIdleSync()
            var initialStage: Stage? = null
            instrumentation.runOnMainSync {
                val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                initialStage = monitor.getLifecycleStageOf(activity)
                lifecycleCallback = ActivityLifecycleCallback { changed, stage ->
                    if (changed === activity && stage != Stage.RESUMED) foregroundLost.set(true)
                }
                monitor.addLifecycleCallback(requireNotNull(lifecycleCallback))
            }
            assertEquals("Reference capture requires a resumed MainActivity", Stage.RESUMED, initialStage)
            report.put("foregroundVerifiedAtStart", true)
            val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            assertTrue("AudioRecord format unavailable", minimum > 0)
            val requested = maxOf(minimum, 16_000)
            val capture = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(requested).build()
            recorder = capture
            assertEquals(AudioRecord.STATE_INITIALIZED, capture.state)
            report.put("minimumBufferBytes", minimum).put("requestedBufferBytes", requested)
                .put("actualBufferFrames", capture.bufferSizeInFrames).put("readBufferBytes", buffer.size)
            capture.startRecording()
            assertEquals(AudioRecord.RECORDSTATE_RECORDING, capture.recordingState)
            val start = SystemClock.elapsedRealtimeNanos()
            val end = start + 16_000_000_000L
            var previousRead = start
            var nextTimestamp = start
            marker.writeText("ready")
            while (SystemClock.elapsedRealtimeNanos() < end) {
                check(!foregroundLost.get()) { "Reference capture lost foreground activity" }
                val before = SystemClock.elapsedRealtimeNanos()
                maxGap = maxOf(maxGap, before - previousRead)
                previousRead = before
                val count = capture.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                maxReadTime = maxOf(maxReadTime, SystemClock.elapsedRealtimeNanos() - before)
                calls++
                check(count >= 0 && count % 2 == 0) { "AudioRecord returned invalid byte count: $count" }
                if (count > 0) {
                    check(bytes + count <= memory.size) { "Bounded reference capture exceeded its memory limit" }
                    buffer.copyInto(memory, bytes, 0, count)
                    bytes += count
                } else { emptyReads++; SystemClock.sleep(10) }
                if (before >= nextTimestamp) {
                    if (capture.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                        if (timestampCount == 0) { firstFrame = timestamp.framePosition; firstTimestampNanos = timestamp.nanoTime }
                        else if (timestamp.framePosition < lastFrame || timestamp.nanoTime < lastTimestampNanos) backwardsTimestamps++
                        lastFrame = timestamp.framePosition; lastTimestampNanos = timestamp.nanoTime; timestampCount++
                    } else timestampFailures++
                    if (capture.activeRecordingConfiguration?.isClientSilenced == true) silencedObservations++
                    nextTimestamp = before + 250_000_000L
                }
            }
            report.put("captureWallMs", (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0)
            assertTrue("AudioRecord returned no frames", bytes > 0)
            assertFalse("MainActivity must remain resumed throughout capture", foregroundLost.get())
            report.put("executionCompleted", true).put("status", "CAPTURED_AWAITING_HOST_WAVEFORM_VERIFICATION")
        } catch (error: Throwable) {
            report.put("status", "CAPTURE_EXECUTION_FAILED").put("failureType", error.javaClass.simpleName)
            throw error
        } finally {
            runCatching { recorder?.stop() }; runCatching { recorder?.release() }; marker.delete()
            report.put("foregroundLostDuringCapture", foregroundLost.get())
            instrumentation.runOnMainSync {
                lifecycleCallback?.let { ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(it) }
                foregroundActivity?.finish()
            }
            var squares = 0.0
            var nonzero = 0
            val samples = ByteBuffer.wrap(memory, 0, bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (offset in 0 until bytes step 2) {
                val sample = samples.getShort(offset).toInt()
                squares += sample.toDouble() * sample
                if (sample != 0) nonzero++
            }
            val wav = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + bytes); put("WAVEfmt ".toByteArray()); putInt(16)
                putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(bytes); put(memory, 0, bytes)
            }.array()
            File(output, "reference-microphone-recording.wav").writeBytes(wav)
            report.put("wavSha256", sha256(wav)).put("readCalls", calls).put("zeroByteReads", emptyReads)
                .put("readGapMaxMs", maxGap / 1_000_000.0).put("readDurationMaxMs", maxReadTime / 1_000_000.0)
                .put("capturedFrames", bytes / 2).put("capturedDurationMs", bytes / 32.0).put("nonzeroSamples", nonzero)
                .put("rms", if (bytes == 0) 0.0 else sqrt(squares / (bytes / 2)) / 32768.0).put("allZeroPcm", nonzero == 0)
                .put("timestampSuccessCount", timestampCount).put("timestampFailureCount", timestampFailures)
                .put("timestampFrameDelta", lastFrame - firstFrame).put("timestampSpanMs", (lastTimestampNanos - firstTimestampNanos) / 1_000_000.0)
                .put("backwardsTimestampCount", backwardsTimestamps).put("silencedObservations", silencedObservations)
            File(output, "reference-microphone-result.json").writeText(report.toString(2))
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

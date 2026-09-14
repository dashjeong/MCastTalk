package app.guidecast.transmitter

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

class FileAudioPlaybackDeviceTest {
    @Test fun actualPlayerPauseSeekSpeedRepeatAndReplacementMatchVisibleState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val state = AtomicReference(FileAudioPlaybackState())
        val file = File(instrumentation.targetContext.cacheDir, "synthetic-player-regression.wav")
        file.writeBytes(syntheticWave())
        var player: FileAudioPlayback? = null
        var activity: ActivityScenario<MainActivity>? = null
        fun onMain(block: (FileAudioPlayback) -> Unit) = instrumentation.runOnMainSync { block(requireNotNull(player)) }
        fun waitFor(expected: String, condition: (FileAudioPlaybackState) -> Boolean) =
            waitUntil(expected, { state.get().toString() }) { condition(state.get()) }
        try {
            // API 35+ grants audio focus only to a top app or foreground service. The file
            // player is Activity-scoped, so reproduce its actual foreground screen contract.
            val scenario = ActivityScenario.launch(MainActivity::class.java).also { activity = it }
            waitUntil("Foreground activity with window focus", { state.get().toString() }) {
                var foreground = false
                scenario.onActivity { foreground = !it.isFinishing && !it.isDestroyed && it.hasWindowFocus() }
                foreground
            }
            instrumentation.runOnMainSync { player = FileAudioPlayback(instrumentation.targetContext, scope, state::set) }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> onMain { it.prepare(descriptor) } }
            waitFor("Prepared audio") { it.prepared }
            onMain { it.setSpeed(1.5f); it.setRepeat(true); it.play() }
            waitFor("Playing audio beyond 250 ms") { it.isPlaying && it.positionMs > 250 }
            assertEquals(1.5f, state.get().speed)
            assertTrue(state.get().repeat)
            onMain { it.pause() }
            assertFalse(state.get().isPlaying)
            val paused = state.get().positionMs
            SystemClock.sleep(200)
            assertEquals(paused, state.get().positionMs)
            onMain { it.seek(2_000) }
            waitFor("Seek position near 2000 ms") { kotlin.math.abs(it.positionMs - 2_000) < 100 }
            onMain { it.stop() }
            waitFor("Stopped at position zero") { it.positionMs == 0L }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> onMain { it.prepare(descriptor) } }
            waitFor("Replacement audio prepared") { it.prepared }
            assertEquals(1f, state.get().speed)
            assertFalse(state.get().repeat)
            assertFalse(state.get().isPlaying)
            assertNull(state.get().error)
        } finally {
            instrumentation.runOnMainSync { player?.close() }
            activity?.close()
            scope.cancel()
            file.delete()
        }
    }

    private fun waitUntil(expected: String, details: () -> String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(30)
        assertTrue("$expected; actual: ${details()}", condition())
    }
    private fun syntheticWave(): ByteArray {
        val count = 16_000 * 4
        return ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + count * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(count * 2)
            repeat(count) { putShort((sin(2 * PI * 440 * it / 16_000) * 3_000).toInt().toShort()) }
        }.array()
    }
}

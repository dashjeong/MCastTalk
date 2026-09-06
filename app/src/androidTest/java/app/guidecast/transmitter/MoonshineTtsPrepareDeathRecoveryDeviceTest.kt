package app.guidecast.transmitter

import android.app.ActivityManager
import android.os.Build
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.moonshine.voice.ModelCache
import ai.moonshine.voice.ModelSpec
import app.guidecast.core.audio.pcmS16LeSignalStats
import app.guidecast.provider.moonshine.tts.MoonshineSpeechSynthesisProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator-only regression for Android reclaiming the isolated worker during model repair. */
@RunWith(AndroidJUnit4::class)
class MoonshineTtsPrepareDeathRecoveryDeviceTest {
    @Test
    fun workerDeathDuringModelRepairReconnectsToReadyAndPlayablePcm() = runBlocking {
        assumeTrue(
            "This test deliberately kills a private worker and must not run on a physical phone.",
            isEmulator(),
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelSpec = ModelSpec.tts(MOONSHINE_LANGUAGE, MOONSHINE_VOICE)
        val modelDirectory = ModelCache.directoryFor(context, modelSpec, null)
        val victim = File(modelDirectory, REPAIR_ASSET)

        // Seed the exact official model before making one large file incomplete. This keeps the
        // test scoped to retry/resume rather than conflating it with first-install provisioning.
        MoonshineSpeechSynthesisProvider(context).use { seedProvider ->
            withTimeout(PREPARE_TIMEOUT_MILLIS) { seedProvider.prepare(listOf(LANGUAGE_TAG)) }
            seedProvider.releaseNativeResources()
        }
        check(victim.isFile && victim.length() == REPAIR_ASSET_BYTES) {
            "Moonshine repair fixture is unavailable: $victim"
        }

        val backup = File(victim.parentFile, "${victim.name}.${UUID.randomUUID()}.test-backup")
        val marker = File(modelDirectory, READY_MARKER)
        val markerBytes = marker.takeIf(File::isFile)?.readBytes()
        val partial = File(victim.absolutePath + ".part")
        Files.move(victim.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE)
        Files.deleteIfExists(marker.toPath())
        Files.deleteIfExists(partial.toPath())

        val provider = MoonshineSpeechSynthesisProvider(context)
        try {
            val preparation = async {
                withTimeout(PREPARE_TIMEOUT_MILLIS) { provider.prepare(listOf(LANGUAGE_TAG)) }
            }
            val interrupted = withTimeout<InterruptedDownload>(DOWNLOAD_OBSERVATION_TIMEOUT_MILLIS) {
                var observed: InterruptedDownload? = null
                while (observed == null) {
                    val pid = ttsWorkerPid(context)
                    val partialBytes = partial.takeIf(File::isFile)?.length() ?: 0L
                    if (pid != null && partialBytes > 0L && partialBytes < REPAIR_ASSET_BYTES) {
                        observed = InterruptedDownload(pid, partialBytes)
                    } else {
                        delay(10L)
                    }
                }
                checkNotNull(observed)
            }

            Process.sendSignal(interrupted.workerPid, Process.SIGNAL_KILL)
            val replacementPid = withTimeout<Int>(WORKER_RECONNECT_TIMEOUT_MILLIS) {
                var observed: Int? = null
                while (observed == null) {
                    val pid = ttsWorkerPid(context)
                    if (pid != null && pid != interrupted.workerPid) {
                        observed = pid
                    } else {
                        delay(25L)
                    }
                }
                checkNotNull(observed)
            }

            preparation.await()
            assertTrue("재연결 뒤 Moonshine READY가 복구되지 않았습니다.", provider.isReady(LANGUAGE_TAG))
            assertTrue("TTS worker PID가 교체되지 않았습니다.", replacementPid != interrupted.workerPid)
            assertTrue("종료 전에 incomplete partial을 관찰하지 못했습니다.", interrupted.partialBytes > 0L)

            val output = ByteArrayOutputStream()
            withTimeout(SYNTHESIS_TIMEOUT_MILLIS) {
                provider.engineFor(LANGUAGE_TAG)
                    .synthesize("Welcome to the DMZ peace walk.", LANGUAGE_TAG)
                    .collect { output.write(it.bytes) }
            }
            val stats = output.toByteArray().pcmS16LeSignalStats()
            assertTrue("재연결 뒤 TTS PCM이 비었습니다: $stats", stats.sampleCount > 12_000)
            assertTrue("재연결 뒤 TTS가 무음입니다: $stats", stats.rms > 0.003f)
            assertTrue("재연결 뒤 TTS 피크가 너무 낮습니다: $stats", stats.peak > 0.015f)
        } finally {
            provider.close()
            Files.deleteIfExists(partial.toPath())
            Files.deleteIfExists(victim.toPath())
            if (backup.isFile) {
                Files.move(
                    backup.toPath(),
                    victim.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            if (markerBytes == null) {
                Files.deleteIfExists(marker.toPath())
            } else {
                marker.writeBytes(markerBytes)
            }
        }
    }

    private fun ttsWorkerPid(context: android.content.Context): Int? =
        context.getSystemService(ActivityManager::class.java)
            .runningAppProcesses
            ?.firstOrNull { process ->
                process.processName == "${context.packageName}:tts_en"
            }
            ?.pid

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("sdk_gphone", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
            Build.HARDWARE.contains("goldfish", ignoreCase = true)

    private data class InterruptedDownload(
        val workerPid: Int,
        val partialBytes: Long,
    )

    private companion object {
        const val LANGUAGE_TAG = "en"
        const val MOONSHINE_LANGUAGE = "en-us"
        const val MOONSHINE_VOICE = "kokoro_af_heart"
        const val REPAIR_ASSET = "kokoro/decoder.model.ort"
        const val REPAIR_ASSET_BYTES = 39_257_640L
        const val READY_MARKER = ".guidecast-integrity-v1"
        const val PREPARE_TIMEOUT_MILLIS = 10L * 60 * 1_000
        const val DOWNLOAD_OBSERVATION_TIMEOUT_MILLIS = 2L * 60 * 1_000
        const val WORKER_RECONNECT_TIMEOUT_MILLIS = 30_000L
        const val SYNTHESIS_TIMEOUT_MILLIS = 2L * 60 * 1_000
    }
}

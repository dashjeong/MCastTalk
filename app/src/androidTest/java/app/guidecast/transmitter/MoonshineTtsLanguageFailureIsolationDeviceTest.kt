package app.guidecast.transmitter

import android.app.ActivityManager
import android.os.Build
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.audio.pcmS16LeSignalStats
import app.guidecast.provider.moonshine.tts.MoonshineSpeechSynthesisProvider
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator-only proof that a native English failure cannot terminate Japanese speech. */
@RunWith(AndroidJUnit4::class)
class MoonshineTtsLanguageFailureIsolationDeviceTest {
    @Test
    fun killingEnglishWorkerLeavesJapaneseWorkerAndPcmAlive() = runBlocking {
        assumeTrue(
            "This test deliberately kills a private worker and must not run on a physical phone.",
            isEmulator(),
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = MoonshineSpeechSynthesisProvider(context)
        try {
            withTimeout(PREPARE_TIMEOUT_MILLIS) {
                coroutineScope {
                    listOf("en", "ja").map { language ->
                        async { provider.prepare(listOf(language)) }
                    }.awaitAll()
                }
            }
            assertPlayable(provider, "ja", "平和の道へようこそ。")
            val englishPid = requireNotNull(workerPid(context, "tts_en"))
            val japanesePid = requireNotNull(workerPid(context, "tts_ja"))
            assertNotEquals("언어별 TTS가 같은 프로세스를 사용합니다.", englishPid, japanesePid)

            Process.sendSignal(englishPid, Process.SIGNAL_KILL)
            withTimeout(15_000L) {
                while (workerPid(context, "tts_en") == englishPid) delay(50L)
            }

            assertEquals(
                "영어 작업자 장애가 일본어 작업자를 교체했습니다.",
                japanesePid,
                workerPid(context, "tts_ja"),
            )
            assertPlayable(provider, "ja", "日本語の放送はそのまま続きます。")
        } finally {
            provider.close()
        }
    }

    private suspend fun assertPlayable(
        provider: MoonshineSpeechSynthesisProvider,
        languageTag: String,
        text: String,
    ) {
        val output = ByteArrayOutputStream()
        withTimeout(SYNTHESIS_TIMEOUT_MILLIS) {
            provider.engineFor(languageTag).synthesize(text, languageTag).collect {
                output.write(it.bytes)
            }
        }
        val stats = output.toByteArray().pcmS16LeSignalStats()
        assertTrue("$languageTag TTS PCM이 비었습니다: $stats", stats.sampleCount > 12_000)
        assertTrue("$languageTag TTS가 무음입니다: $stats", stats.rms > 0.003f)
        assertTrue("$languageTag TTS 피크가 너무 낮습니다: $stats", stats.peak > 0.015f)
    }

    private fun workerPid(context: android.content.Context, suffix: String): Int? =
        context.getSystemService(ActivityManager::class.java)
            .runningAppProcesses
            ?.firstOrNull { it.processName == "${context.packageName}:$suffix" }
            ?.pid

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("sdk_gphone", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
            Build.HARDWARE.contains("goldfish", ignoreCase = true)

    private companion object {
        const val PREPARE_TIMEOUT_MILLIS = 20L * 60 * 1_000
        const val SYNTHESIS_TIMEOUT_MILLIS = 2L * 60 * 1_000
    }
}

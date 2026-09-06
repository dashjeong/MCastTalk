package app.guidecast.transmitter

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.audio.pcmS16LeSignalStats
import app.guidecast.provider.android.tts.AndroidOfflineSpeechSynthesisProvider
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Proves that the already-published translation can become audible through the device fallback. */
@RunWith(AndroidJUnit4::class)
class AndroidOfflineTtsDeviceIntegrationTest {
    @Test
    fun englishTranslationProducesNonSilentFallbackPcm() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installedTtsServices = context.packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
            0,
        )
        assumeTrue(
            "이 AOSP 가상 단말에는 시스템 TTS 엔진이 없습니다.",
            installedTtsServices.isNotEmpty(),
        )
        val provider = AndroidOfflineSpeechSynthesisProvider(context, outputSampleRateHz = 24_000)
        try {
            withTimeout(30_000L) { provider.prepare(listOf("en")) }
            val pcm = ByteArrayOutputStream()
            withTimeout(30_000L) {
                provider.engineFor("en")
                    .synthesize("Welcome. The tour will begin now.", "en")
                    .collect { pcm.write(it.bytes) }
            }
            val bytes = pcm.toByteArray()
            val stats = bytes.pcmS16LeSignalStats()
            assertTrue("Galaxy/Android fallback PCM is too short: ${bytes.size}", bytes.size > 8_000)
            assertTrue("Galaxy/Android fallback PCM is silent: $stats", stats.rms > 0.002f)
            assertTrue("Galaxy/Android fallback PCM peak is too low: $stats", stats.peak > 0.01f)
        } finally {
            provider.close()
        }
    }
}

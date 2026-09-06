package app.guidecast.transmitter

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.stream.AudioStreamRegistry
import app.guidecast.core.translation.*
import app.guidecast.provider.gemma.translation.GemmaModelVariant
import app.guidecast.provider.moonshine.tts.MoonshineSpeechSynthesisProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Needs the real verified STANDARD Gemma and prepared English voice; no model download or fake inference. */
@RunWith(AndroidJUnit4::class)
class GlossaryGemmaDeviceTest {
    @Test fun publicTermReachesRealGemmaAndProducesAudibleCommittedTts(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as GuideCastApplication
        val gemma = app.gemmaTranslationProvider
        val speech = MoonshineSpeechSynthesisProvider(context)
        val streams = AudioStreamRegistry()
        var running: RunningTranslationPipeline? = null
        try {
            gemma.selectModel(GemmaModelVariant.STANDARD)
            gemma.modelManager.refresh()
            assertTrue(gemma.modelManager.modelFile.isFile)
            assertTrue(speech.isReady("en"))
            gemma.warmup("en")
            val terms = app.glossary.matching("우리는 1100고지에 도착했습니다.", "ko-KR", "en")
            assertTrue(terms.any { it.preferredTerm == "1100goji Highland" })
            val source = MutableSharedFlow<RecognizedUtterance>()
            val caption = CompletableDeferred<String>()
            val completedPcm = CompletableDeferred<SynthesizedPcmStats>()
            running = TranslationBroadcastPipeline(streams, gemma, speech,
                translationTimeoutMillis = 15_000, firstAudioTimeoutMillis = 30_000,
                glossaryTerms = app.glossary::matching,
                observer = object : TranslationPipelineObserver {
                    override fun onTranslationCompleted(utterance: RecognizedUtterance, target: TranslationTarget, translatedText: String, elapsedMillis: Long) {
                        Log.i("GuideCastGlossary", "translationMs=$elapsedMillis text=$translatedText")
                        caption.complete(translatedText)
                    }
                    override fun onSynthesisAudioCompleted(utterance: RecognizedUtterance, target: TranslationTarget, elapsedMillis: Long, pcm: SynthesizedPcmStats) {
                        Log.i("GuideCastGlossary", "ttsMs=$elapsedMillis bytes=${pcm.byteCount} rms=${pcm.rms}")
                        completedPcm.complete(pcm)
                    }
                }).start(this, source, listOf(TranslationTarget("en", "English", "en", 24_000)), "ko-KR")
            yield()
            source.emit(RecognizedUtterance(1, "우리는 1100고지에 도착했습니다.", "ko-KR", true, System.nanoTime()))
            val translated = withTimeout(30_000) { caption.await() }
            assertTrue("Gemma did not use the public term: $translated", translated.contains("1100goji Highland", ignoreCase = true))
            val stats = withTimeout(60_000) { completedPcm.await() }
            assertTrue(stats.isNonSilent())
            assertTrue(stats.clippingRatio < .005f)
        } finally {
            running?.close()
            speech.close()
            gemma.resetEngineSafely()
        }
    }
}

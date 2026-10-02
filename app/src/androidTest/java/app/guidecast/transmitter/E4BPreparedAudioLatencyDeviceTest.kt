package app.guidecast.transmitter

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.stream.AudioStreamRegistry
import app.guidecast.core.translation.*
import app.guidecast.provider.gemma.translation.GemmaModelVariant
import app.guidecast.provider.moonshine.tts.MoonshineSpeechSynthesisProvider
import app.guidecast.provider.android.tts.AndroidOfflineSpeechSynthesisProvider
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real final text -> first audible native PCM; not microphone, WebSocket, or speaker playback. */
class E4BPreparedAudioLatencyDeviceTest {
    @Test fun recordsTwentyPreparedEnglishFirstAudiblePcmSamples() = runPreparedTest(false)
    @Test fun recordsTwentyPreparedAndroidEnglishFirstAudiblePcmSamples() = runPreparedTest(true)

    private fun runPreparedTest(androidVoice: Boolean) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as GuideCastApplication
        val gemma = app.gemmaTranslationProvider
        val original = gemma.modelManager.selectedVariant
        val lease = requireNotNull(app.acquireTranslationBackendUseIf({ true }))
        val moonshine = if (!androidVoice) MoonshineSpeechSynthesisProvider(context) else null
        val android = if (androidVoice) AndroidOfflineSpeechSynthesisProvider(context, outputSampleRateHz = 24_000) else null
        val speech: SpeechSynthesisEngineProvider = moonshine ?: requireNotNull(android)
        val rows = JSONArray()
        val result = JSONObject().put("model", "e4b_it").put("deviceModel", Build.MODEL)
            .put("api", Build.VERSION.SDK_INT).put("target", "en").put("samplesPlanned", 20)
            .put("ttsEngine", if (androidVoice) "installed-android-offline" else "moonshine")
            .put("uniqueSourcePhrases", 5).put("repeatedSourcePhrases", true).put("sessionMemoryEnabled", true)
            .put("microphoneMeasured", false).put("webSocketMeasured", false)
            .put("speakerPlaybackMeasured", false).put("rows", rows)
        val output = File(context.getExternalFilesDir(null), "benchmark/e4b-prepared-native-pcm-${System.currentTimeMillis()}.json")
        output.parentFile?.mkdirs()
        fun save() = output.writeText(result.toString(2))
        var pipeline: RunningTranslationPipeline? = null
        try {
            // Do not download a missing model merely to make a prepared-model test look ready.
            if (moonshine != null) assertTrue("Verified English voice must already be installed", moonshine.isReady("en"))
            withTimeout(120_000) {
                gemma.applyVerifiedModel(GemmaModelVariant.E4B_IT)
                if (moonshine != null) moonshine.prepare(listOf("en")) else requireNotNull(android).prepare(listOf("en"))
                speech.engineFor("en").synthesize("The audio test is ready.", "en").collect {}
            }
            val source = MutableSharedFlow<RecognizedUtterance>()
            var row = JSONObject()
            var finished = CompletableDeferred<Unit>()
            var startedAt = 0L
            val streams = AudioStreamRegistry()
            pipeline = TranslationBroadcastPipeline(streams, gemma, speech,
                translationTimeoutMillis = 15_000, firstAudioTimeoutMillis = 30_000,
                observer = object : TranslationPipelineObserver {
                    override fun onTranslationCompleted(utterance: RecognizedUtterance, target: TranslationTarget, translatedText: String, elapsedMillis: Long) {
                        row.put("translationMs", elapsedMillis)
                    }
                    override fun onSynthesisAudioStarted(utterance: RecognizedUtterance, target: TranslationTarget, elapsedMillis: Long) {
                        row.put("ttsFirstAudibleMs", elapsedMillis)
                        row.put("finalToFirstAudiblePcmMs", (System.nanoTime() - startedAt) / 1_000_000)
                        save()
                    }
                    override fun onSynthesisAudioCompleted(utterance: RecognizedUtterance, target: TranslationTarget, elapsedMillis: Long, pcm: SynthesizedPcmStats) {
                        row.put("nonSilent", pcm.isNonSilent()).put("pcmBytes", pcm.byteCount)
                        row.put("clippingRatio", pcm.clippingRatio).put("state", "COMPLETED")
                        save(); finished.complete(Unit)
                    }
                }).start(this, source, listOf(TranslationTarget("en", "English", "en", 24_000)), "ko")
            yield()
            val phrases = listOf("회의를 시작하겠습니다.", "자료를 확인해 주세요.", "질문이 있으면 말씀해 주세요.",
                "잠시 쉬었다가 계속하겠습니다.", "오늘 참석해 주셔서 감사합니다.")
            repeat(20) { index ->
                row = JSONObject().put("id", index).put("state", "RUNNING")
                rows.put(row); save(); finished = CompletableDeferred()
                startedAt = System.nanoTime()
                source.emit(RecognizedUtterance(index.toLong(), phrases[index % phrases.size], "ko", true, startedAt))
                withTimeout(90_000) { finished.await() }
                assertTrue(row.getBoolean("nonSilent"))
                assertTrue(row.getDouble("clippingRatio") < .005)
            }
            val ordered = (0 until rows.length()).map { rows.getJSONObject(it).getLong("finalToFirstAudiblePcmMs") }.sorted()
            val p95 = ordered[18] // nearest-rank ceil(.95 * 20) - 1
            result.put("nativeFirstPcmP95Ms", p95).put("nativeTwoSecondGatePass", p95 <= 2_000)
            result.put("releaseWebGateProven", false); save()
            assertEquals(20, rows.length())
        } finally {
            pipeline?.close(); moonshine?.close(); android?.close()
            try { if (gemma.modelManager.selectedVariant != original) gemma.applyVerifiedModel(original) }
            finally { lease.close(); save() }
        }
    }
}

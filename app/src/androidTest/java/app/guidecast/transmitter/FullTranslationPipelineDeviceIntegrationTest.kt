package app.guidecast.transmitter

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.audio.pcmS16LeSignalStats
import app.guidecast.core.stream.AudioStreamRegistry
import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.translation.RecognizedUtterance
import app.guidecast.core.translation.RunningTranslationPipeline
import app.guidecast.core.translation.SpeechRecognitionConfig
import app.guidecast.core.translation.TranslationBroadcastPipeline
import app.guidecast.core.translation.TranslationPipelineObserver
import app.guidecast.core.translation.TranslationTarget
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Product gate that the original tests were missing: STT, four translations and four TTS
 * engines must coexist in one process and deliver real, non-silent PCM for the same Korean
 * utterance. A test tone cannot satisfy this test.
 */
@RunWith(AndroidJUnit4::class)
class FullTranslationPipelineDeviceIntegrationTest {
    @Test
    fun koreanSpeechProducesScriptsAndFourPlayableTranslationChannels(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as GuideCastApplication
        val languages = linkedMapOf(
            "en" to "English",
            "ja" to "日本語",
            "zh" to "中文",
            "nl" to "Nederlands",
        )

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pcmBytes = runCatching {
            instrumentation.context.assets.open("fixtures/fleurs-ko-1959.pcm").use { it.readBytes() }
        }.recoverCatching {
            instrumentation.targetContext.assets.open("fixtures/fleurs-ko-1959.pcm").use { it.readBytes() }
        }.getOrNull()
        assertTrue("FLEURS 한국어 음원 피스처가 누락되었습니다.", pcmBytes != null)
        val nonNullBytes = requireNotNull(pcmBytes)
        val shorts = ShortArray(nonNullBytes.size / 2)
        ByteBuffer.wrap(nonNullBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        val sourcePcm = FloatArray(shorts.size) { shorts[it] / 32768f }
        assertTrue("한국어 시험 발화가 무음입니다.", sourcePcm.any { it != 0f })

        withTimeout(30 * 60 * 1_000L) {
            assertTrue(app.speechRecognitionEngine.prepareLanguage("ko-KR").isReady)
            app.translationProvider.modelManager.prepare(languages.keys)
            // The preceding UI test can retain only three languages. Claim this fixture's four
            // languages through the real settings boundary before native preparation, just as
            // an operator changing the selected channels does; raw prepare does not own selection.
            app.speechSynthesisProvider.prepareForSettings(
                languages.keys,
                warmMoonshineWithNativeAdmission = { language, warm ->
                    app.withProcessNativeColdLoadLease(ProcessNativeColdLoadKeys.speech(language), true) { warm() }
                },
                isAppPreparationCurrent = { true },
            )
            assertTrue("TTS preparation failed: ${app.speechSynthesisProvider.unavailableLanguageReasons.value}",
                app.speechSynthesisProvider.unavailableLanguageReasons.value.keys.none { it in languages })
        }

        val streams = AudioStreamRegistry(maxChannels = 4, maxListeners = 50)
        val pipelineScopeJob = SupervisorJob()
        val scope = CoroutineScope(pipelineScopeJob + Dispatchers.Default)
        val finalSource = CompletableDeferred<String>()
        val sourceEvents = CopyOnWriteArrayList<RecognizedUtterance>()
        val translated = ConcurrentHashMap<String, String>()
        val synthesized = ConcurrentHashMap.newKeySet<String>()
        val allSynthesized = CompletableDeferred<Unit>()
        val outputs = languages.keys.associateWith { ByteArrayOutputStream() }
        val subscriptions = mutableListOf<app.guidecast.core.stream.AudioListenerSubscription>()
        var runningPipeline: RunningTranslationPipeline? = null
        try {
            val recognized = app.speechRecognitionEngine.recognize(
                frames = flow {
                    val samplesPerFrame = 320
                    var offset = 0
                    while (offset < sourcePcm.size) {
                        val count = minOf(samplesPerFrame, sourcePcm.size - offset)
                        emit(sourcePcm.toPipelineTestFrame(offset, count))
                        offset += count
                        delay(20)
                    }
                    repeat(100) {
                        emit(ByteArray(samplesPerFrame * 2).asPipelineTestFrame())
                        delay(20)
                    }
                },
                config = SpeechRecognitionConfig(sourceLanguageTag = "ko-KR"),
            )
            runningPipeline = TranslationBroadcastPipeline(
                streams = streams,
                translationEngines = app.translationProvider,
                speechEngines = app.speechSynthesisProvider,
                observer = object : TranslationPipelineObserver {
                    override fun onSourceRecognized(utterance: RecognizedUtterance) {
                        sourceEvents += utterance
                        if (utterance.isFinal && !finalSource.isCompleted) {
                            finalSource.complete(utterance.text)
                        }
                    }

                    override fun onTranslationCompleted(
                        utterance: RecognizedUtterance,
                        target: TranslationTarget,
                        translatedText: String,
                        elapsedMillis: Long,
                    ) {
                        translated[target.channelId] = translatedText
                    }

                    override fun onSynthesisCompleted(
                        utterance: RecognizedUtterance,
                        target: TranslationTarget,
                        elapsedMillis: Long,
                    ) {
                        synthesized += target.channelId
                        if (synthesized.containsAll(languages.keys) && !allSynthesized.isCompleted) {
                            allSynthesized.complete(Unit)
                        }
                    }
                },
            ).start(
                scope = scope,
                utterances = recognized,
                targets = languages.map { (tag, label) ->
                    TranslationTarget(tag, label, tag, 24_000)
                },
            )

            val health = runningPipeline.health
            scope.launch {
                health.collect { channels ->
                    channels.firstOrNull { it.translationFailures > 0 || it.synthesisFailures > 0 }?.let {
                        allSynthesized.completeExceptionally(AssertionError("Channel ${it.channelId}: ${it.lastError}"))
                    }
                }
            }

            languages.keys.forEach { languageTag ->
                val subscription = streams.subscribe(languageTag)
                subscriptions += subscription
                scope.launch {
                    for (frame in subscription.frames) outputs.getValue(languageTag).write(frame.bytes)
                }
            }

            val sourceScript = withTimeout(3 * 60 * 1_000L) { finalSource.await() }
            assertFalse("한국어 완료 스크립트가 비어 있습니다.", sourceScript.isBlank())
            assertTrue(
                "완료 문장 전에 화면에 표시할 중간 인식 이벤트가 생성되지 않았습니다: $sourceEvents",
                sourceEvents.any { !it.isFinal && it.text.isNotBlank() },
            )
            withTimeout(10 * 60 * 1_000L) { allSynthesized.await() }
            assertTrue("네 언어 번역문이 모두 생성되지 않았습니다: $translated", translated.keys.containsAll(languages.keys))

            languages.keys.forEach { languageTag ->
                val pcm = outputs.getValue(languageTag).toByteArray()
                val stats = pcm.pcmS16LeSignalStats()
                assertTrue("$languageTag 통역 PCM이 너무 짧습니다: ${pcm.size}", pcm.size > 12_000)
                assertTrue("$languageTag 통역 PCM이 무음입니다: $stats", stats.rms > 0.003f)
                assertTrue("$languageTag 통역 PCM 피크가 너무 낮습니다: $stats", stats.peak > 0.015f)
            }
        } finally {
            runningPipeline?.close()
            subscriptions.forEach { it.close() }
            scope.cancel()
            // Instrumentation classes share the target Application and its native provider. A
            // cancelled native utterance can therefore otherwise bleed into the next class before
            // its non-cooperative worker call returns. Exercise the same bounded product release
            // path used when an operator stops a session, even when an assertion above fails.
            withContext(NonCancellable) {
                try {
                    withTimeout(10_000L) { pipelineScopeJob.join() }
                } finally {
                    app.speechSynthesisProvider.releaseNativeResources()
                }
            }
        }
    }
}

private fun resamplePipelineTestPcm(
    source: FloatArray,
    sourceRate: Int,
    targetRate: Int,
): FloatArray {
    if (sourceRate == targetRate) return source.copyOf()
    val outputSize = ((source.size.toLong() * targetRate) / sourceRate).toInt()
    val ratio = sourceRate.toDouble() / targetRate
    return FloatArray(outputSize) { index ->
        val position = index * ratio
        val left = floor(position).toInt().coerceIn(0, source.lastIndex)
        val right = (left + 1).coerceAtMost(source.lastIndex)
        val fraction = (position - left).toFloat()
        source[left] + (source[right] - source[left]) * fraction
    }
}

private fun FloatArray.toPipelineTestFrame(offset: Int, count: Int): PcmAudioFrame =
    ByteBuffer.allocate(count * 2)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply {
            for (index in offset until offset + count) {
                putShort(
                    (this@toPipelineTestFrame[index].coerceIn(-1f, 1f) * 32_767f)
                        .roundToInt()
                        .toShort(),
                )
            }
        }
        .array()
        .asPipelineTestFrame()

private fun ByteArray.asPipelineTestFrame() = PcmAudioFrame(
    bytes = this,
    capturedAtElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
)

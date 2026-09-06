package app.guidecast.core.translation

import app.guidecast.core.stream.AudioStreamRegistry
import app.guidecast.core.stream.PcmAudioFrame
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PriorityTranslationEngineProviderTest {
    @Test
    fun fiveLanguageWorkersDoNotSpendTheirTimeoutWaitingForTheSingleQualityRuntime() = runTest {
        val primaryCalls = AtomicInteger()
        val mlKitCalls = mutableListOf<String>()
        val translatedChannels = mutableMapOf<String, String>()
        val mlKit = TranslationEngineProvider { configuredTarget ->
            TextTranslationEngine { text, _, requestedTarget ->
                assertEquals(configuredTarget, requestedTarget)
                mlKitCalls += requestedTarget
                "mlkit:$requestedTarget:$text"
            }
        }
        val gemmaWithFailover = FailoverTranslationEngineProvider(
            primary = TranslationEngineProvider {
                TextTranslationEngine { _, _, _ ->
                    primaryCalls.incrementAndGet()
                    delay(Long.MAX_VALUE)
                    "unreachable"
                }
            },
            fallback = mlKit,
            primaryAttemptTimeoutMillis = 100,
        )
        val routed = PriorityTranslationEngineProvider(
            priorityLanguageTag = "en",
            priority = gemmaWithFailover,
            perLanguage = mlKit,
        )
        val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 1)
        val running = TranslationBroadcastPipeline(
            streams = AudioStreamRegistry(),
            translationEngines = routed,
            speechEngines = SpeechSynthesisEngineProvider {
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) =
                        flowOf(PcmAudioFrame(byteArrayOf(0, 0x20), 1L))
                }
            },
            observer = object : TranslationPipelineObserver {
                override fun onTranslationCompleted(
                    utterance: RecognizedUtterance,
                    target: TranslationTarget,
                    translatedText: String,
                    elapsedMillis: Long,
                ) {
                    translatedChannels[target.channelId] = translatedText
                }
            },
            translationTimeoutMillis = 500,
        ).start(
            scope = this,
            utterances = utterances,
            targets = listOf("en", "ja", "zh", "nl", "es").map { languageTag ->
                TranslationTarget(languageTag, languageTag, languageTag, 24_000)
            },
        )
        runCurrent()

        utterances.emit(RecognizedUtterance(1, "안녕하세요", "ko", true, 1L))
        runCurrent()

        assertEquals(setOf("ja", "zh", "nl", "es"), translatedChannels.keys)
        assertFalse("en" in translatedChannels)
        assertEquals(listOf("es", "ja", "nl", "zh"), mlKitCalls.sorted())

        advanceTimeBy(101)
        runCurrent()

        assertEquals(setOf("en", "ja", "zh", "nl", "es"), translatedChannels.keys)
        assertEquals(1, primaryCalls.get())
        assertEquals(listOf("en", "es", "ja", "nl", "zh"), mlKitCalls.sorted())
        assertTrue(running.health.value.all { it.lastCompletedSequence == 1L })
        assertTrue(running.health.value.all { it.droppedUtterances == 0L })
        running.close()
    }
}

package app.guidecast.transmitter

import app.guidecast.core.stream.AudioChannelDescriptor
import app.guidecast.core.stream.AudioStreamRegistry
import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.translation.ContextualTextTranslationEngine
import app.guidecast.core.translation.RecognizedUtterance
import app.guidecast.core.translation.SpeechSynthesisEngine
import app.guidecast.core.translation.SpeechSynthesisEngineProvider
import app.guidecast.core.translation.TextTranslationEngine
import app.guidecast.core.translation.TranslationBroadcastPipeline
import app.guidecast.core.translation.TranslationEngineProvider
import app.guidecast.core.translation.TranslationPipelineObserver
import app.guidecast.core.translation.TranslationTarget
import java.util.Locale
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraditionalChineseTranslationTest {

    @Test
    fun `convertToTraditionalChinese accurately converts simplified to traditional Chinese offline`() {
        assertEquals("歡迎光臨", convertToTraditionalChinese("欢迎光临"))
        assertEquals("簡體中文測試", convertToTraditionalChinese("简体中文测试"))
        assertEquals("臺灣", convertToTraditionalChinese("台湾"))

        // Phrase ambiguity regressions (disambiguating polysemous characters based on context)
        assertEquals("頭髮", convertToTraditionalChinese("头发"))
        assertEquals("發現", convertToTraditionalChinese("发现"))
        assertEquals("乾燥", convertToTraditionalChinese("干燥"))
        assertEquals("乾杯", convertToTraditionalChinese("干杯"))
        assertEquals("麵條", convertToTraditionalChinese("面条"))
        assertEquals("裡面", convertToTraditionalChinese("里面"))
    }

    @Test
    fun `simultaneous zh and zh-TW channels operate with distinct channelIds and translation isolation`() = runBlocking {
        val streams = AudioStreamRegistry(maxChannels = 5, maxListeners = 5)
        val descriptors = listOf(
            AudioChannelDescriptor("zh", "중국어(간체)", "zh", 24_000),
            AudioChannelDescriptor("zh-tw", "중국어(번체·대만)", "zh-TW", 24_000),
        )
        val externalSession = streams.configure(descriptors)
        val zhSubscription = externalSession.subscribe("zh")
        val zhTwSubscription = externalSession.subscribe("zh-tw")

        val baseProvider = TranslationEngineProvider { target ->
            TextTranslationEngine { text, _, _ ->
                // Simulate common simplified Chinese translation from ML Kit
                "欢迎光临"
            }
        }

        val translationProvider = TranslationEngineProvider { targetLanguageTag ->
            val baseEngine = baseProvider.engineFor(targetLanguageTag)
            if (targetLanguageTag.equals("zh-TW", ignoreCase = true)) {
                object : ContextualTextTranslationEngine {
                    override suspend fun translateWithContext(
                        text: String,
                        contextBefore: String?,
                        sourceLanguageTag: String,
                        targetLanguageTag: String,
                    ): String {
                        val simplified = baseEngine.translate(text, sourceLanguageTag, targetLanguageTag)
                        return convertToTraditionalChinese(simplified)
                    }

                    override suspend fun translate(
                        text: String,
                        sourceLanguageTag: String,
                        targetLanguageTag: String,
                    ): String = translateWithContext(text, null, sourceLanguageTag, targetLanguageTag)
                }
            } else {
                baseEngine
            }
        }

        val zhCompleted = kotlinx.coroutines.CompletableDeferred<String>()
        val zhTwCompleted = kotlinx.coroutines.CompletableDeferred<String>()
        val observer = object : TranslationPipelineObserver {
            override fun onTranslationCompleted(
                utterance: RecognizedUtterance,
                target: TranslationTarget,
                translatedText: String,
                elapsedMillis: Long,
            ) {
                if (target.channelId == "zh") zhCompleted.complete(translatedText)
                if (target.channelId == "zh-tw") zhTwCompleted.complete(translatedText)
            }
        }

        val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 1)
        val pipeline = TranslationBroadcastPipeline(
            streams = streams,
            translationEngines = translationProvider,
            speechEngines = SpeechSynthesisEngineProvider { target ->
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) = flow {
                        emit(PcmAudioFrame(ByteArray(320), 1L))
                    }
                }
            },
            observer = observer,
        ).start(
            scope = this,
            utterances = utterances,
            targets = listOf(
                TranslationTarget("zh", "중국어(간체)", "zh", 24_000),
                TranslationTarget("zh-tw", "중국어(번체·대만)", "zh-TW", 24_000),
            ),
            streamSession = externalSession,
        )
        yield()

        utterances.emit(RecognizedUtterance(1, "환영합니다", "ko", true, 1L))

        val zhResult = kotlinx.coroutines.withTimeout(5_000) { zhCompleted.await() }
        val zhTwResult = kotlinx.coroutines.withTimeout(5_000) { zhTwCompleted.await() }

        // Verify both channels received their respective translation completions
        assertEquals("欢迎光临", zhResult)
        assertEquals("歡迎光臨", zhTwResult)

        pipeline.close()
        externalSession.close()
    }

    @Test
    fun `AudioChannel ID validation passes for all translation language channelIds`() {
        val languageTags = listOf("en", "ja", "zh", "zh-TW", "vi", "nl", "es", "ar", "ko")
        val channelIdPattern = Regex("[a-z0-9][a-z0-9_-]{0,23}")

        languageTags.forEach { tag ->
            val channelId = tag.lowercase(Locale.ROOT)
            assertTrue("Channel ID $channelId must match ID_PATTERN", channelIdPattern.matches(channelId))
        }
    }
}

package app.guidecast.core.translation

import app.guidecast.core.stream.AudioStreamRegistry
import app.guidecast.core.stream.AudioChannelDescriptor
import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.stream.StreamPublishStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real staging and pipeline; deterministic translator/TTS doubles, not a native quality test. */
@OptIn(ExperimentalCoroutinesApi::class)
class SentenceStagingPipelineTest {
    @Test
    fun `assembler limit is reported without closing the independent original audio session`() = runTest {
        val streams = AudioStreamRegistry()
        val session = streams.configure(listOf(
            AudioChannelDescriptor("source", "Original", "ko", 16_000),
            AudioChannelDescriptor("en", "English", "en", 16_000),
        ))
        val assembler = ProviderTranscriptSemanticAssembler(maximumPendingProviderLines = 2)
        val source = flow {
            assembler.observeSpeechActivity(true, 0)
            repeat(3) { index ->
                assembler.accept(RecognizedUtterance(index.toLong(), "설명 중인 장소의", "ko", true,
                    index * 1_000_000L)).forEach { emit(it) }
            }
        }
        val running = TranslationBroadcastPipeline(streams,
            TranslationEngineProvider { TextTranslationEngine { text, _, _ -> text } },
            SpeechSynthesisEngineProvider { object : SpeechSynthesisEngine {
                override fun synthesize(text: String, languageTag: String) = flow<PcmAudioFrame> {
                    error("Incomplete text must not reach TTS")
                }
            } },
        ).start(this, source, listOf(TranslationTarget("en", "English", "en", 16_000)),
            sourceLanguageTag = "ko", streamSession = session)
        try {
            advanceUntilIdle()
            assertTrue(running.health.value.single().lastError.orEmpty().contains("보존할 수 없습니다"))
            assertEquals(StreamPublishStatus.PUBLISHED,
                session.tryPublish("source", PcmAudioFrame(byteArrayOf(0, 32, 0, -32), 1)).status)
        } finally {
            running.close()
            session.close()
        }
    }

    @Test
    fun `long utterance pause releases one unit to three language queues and keeps order`() = runTest {
        val assembler = ProviderTranscriptSemanticAssembler()
        val source = MutableSharedFlow<RecognizedUtterance>()
        val translated = mutableMapOf<String, MutableList<String>>()
        val spoken = mutableMapOf<String, MutableList<String>>()
        val languages = listOf("en", "ja", "zh")
        val running = TranslationBroadcastPipeline(
            AudioStreamRegistry(),
            TranslationEngineProvider { target -> TextTranslationEngine { text, _, _ ->
                translated.getOrPut(target) { mutableListOf() }.add(text)
                text
            } },
            SpeechSynthesisEngineProvider { object : SpeechSynthesisEngine {
                override fun synthesize(text: String, languageTag: String) = flow {
                    spoken.getOrPut(languageTag) { mutableListOf() }.add(text)
                    emit(PcmAudioFrame(ByteArray(960) { if (it % 4 == 1) 0x20 else 0 }, 1L))
                }
            } },
        ).start(this, source, languages.map { TranslationTarget(it, it, it, 16_000) }, "ko")
        try {
            runCurrent()
            suspend fun publish(events: List<RecognizedUtterance>) {
                events.forEach { source.emit(it) }
                advanceUntilIdle()
            }
            fun line(sequence: Long, text: String, ms: Long) = RecognizedUtterance(
                sequence, text, "ko", true, ms * 1_000_000, ms * 1_000_000,
            )
            fun observeQuiet(fromMillis: Long, throughMillis: Long) {
                var millis = fromMillis
                while (millis < throughMillis) {
                    assembler.observeSpeechActivity(false, millis * 1_000_000)
                    millis += 250L
                }
                assembler.observeSpeechActivity(false, throughMillis * 1_000_000)
            }
            assembler.observeSpeechActivity(true, 0)
            publish(assembler.accept(line(0, "이번 전시회는.", 100)))
            assembler.observeSpeechActivity(false, 200_000_000)
            observeQuiet(fromMillis = 450, throughMillis = 2_950)
            publish(assembler.tick(3_199_000_000))
            assertTrue(translated.isEmpty())
            assertTrue(spoken.isEmpty())
            assembler.observeSpeechActivity(false, 3_200_000_000)
            publish(assembler.tick(3_200_000_000))
            languages.forEach { language ->
                assertEquals(listOf("이번 전시회는"), translated[language])
                assertEquals(listOf("이번 전시회는"), spoken[language])
            }

            assembler.observeSpeechActivity(true, 3_300_000_000)
            publish(assembler.accept(line(1, "조선 후기의 역사를 소개합니다", 3_400)))
            assembler.observeSpeechActivity(false, 3_500_000_000)
            observeQuiet(fromMillis = 3_750, throughMillis = 4_700)
            publish(assembler.tick(4_700_000_000))

            assembler.observeSpeechActivity(true, 4_800_000_000)
            publish(assembler.accept(line(2, "이곳은 사진을 찍으면 안", 4_900)))
            publish(assembler.accept(line(3, "됩니다", 5_000)))
            assembler.observeSpeechActivity(false, 5_100_000_000)
            observeQuiet(fromMillis = 5_350, throughMillis = 6_300)
            publish(assembler.tick(6_300_000_000))
            publish(assembler.accept(line(3, "됩니다", 6_400))) // late duplicate
            publish(assembler.finish(6_500_000_000))

            val expected = listOf(
                "이번 전시회는",
                "조선 후기의 역사를 소개합니다",
                "이곳은 사진을 찍으면 안 됩니다",
            )
            languages.forEach { language ->
                assertEquals(language, expected, translated[language])
                assertEquals(language, expected, spoken[language])
            }
            assertTrue(running.health.value.all {
                it.droppedUtterances == 0L && it.translationFailures == 0L && it.synthesisFailures == 0L
            })
        } finally {
            running.close()
        }
    }
}

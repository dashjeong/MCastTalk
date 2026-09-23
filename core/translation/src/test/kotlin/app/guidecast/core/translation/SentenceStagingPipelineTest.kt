package app.guidecast.core.translation

import app.guidecast.core.stream.AudioStreamRegistry
import app.guidecast.core.stream.AudioChannelDescriptor
import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.stream.StreamPublishStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withTimeout
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
    fun `meaning corpus preserves conditions negation and quantities through translation and speech`() = runTest {
        // Authored regression corpus, not a score for native recognition or translation quality.
        val corpus = listOf(
            "이번 전시회는" to "조선 후기의 역사를 소개합니다",
            "비가 오면" to "실내에서 기다려 주세요",
            "표지판을 확인하고," to "안내에 따라 이동하세요",
            "이곳에 들어가면 안" to "됩니다",
            "출발 시각은 오후 세" to "시입니다",
            "입장 요금은 15" to "달러입니다",
        )
        val assembler = ProviderTranscriptSemanticAssembler()
        val source = MutableSharedFlow<RecognizedUtterance>()
        val inputs = mutableListOf<Pair<String, String?>>()
        val spoken = mutableListOf<String>()
        val expected = corpus.map { (head, tail) -> "${head.trimEnd(',')} $tail" }
        val translator = object : ContextualTextTranslationEngine {
            override suspend fun translateWithContext(
                text: String, contextBefore: String?, sourceLanguageTag: String, targetLanguageTag: String,
            ): String {
                inputs.add(text to contextBefore)
                return "translated:$text"
            }
        }
        val running = TranslationBroadcastPipeline(
            AudioStreamRegistry(), TranslationEngineProvider { translator },
            SpeechSynthesisEngineProvider { object : SpeechSynthesisEngine {
                override fun synthesize(text: String, languageTag: String) = flow {
                    spoken.add(text)
                    emit(PcmAudioFrame(ByteArray(640) { if (it % 4 == 1) 32 else 0 }, 1))
                }
            } },
        ).start(this, source, listOf(TranslationTarget("en", "English", "en", 16_000)), "ko")
        try {
            runCurrent()
            suspend fun publish(events: List<RecognizedUtterance>) {
                events.forEach { source.emit(it) }
                advanceUntilIdle()
            }
            corpus.forEachIndexed { index, (head, tail) ->
                val start = index * 20_000L
                fun line(id: Long, text: String, at: Long) = RecognizedUtterance(
                    id, text, "ko", true, at * 1_000_000, at * 1_000_000,
                )
                assembler.observeSpeechActivity(true, start * 1_000_000)
                publish(assembler.accept(line(index * 2L, head, start + 100)))
                // Sustained, freshly observed quiet; a stale VAD observation would not reproduce
                // the former three-second fragment-finalization bug.
                for (ms in 200L..8_000L step 200) {
                    assembler.observeSpeechActivity(false, (start + ms) * 1_000_000)
                    publish(assembler.tick((start + ms) * 1_000_000))
                }
                assertEquals("Unfinished corpus item $index must stay visible as preview only", index, inputs.size)
                assertEquals(index, spoken.size)
                assembler.observeSpeechActivity(true, (start + 8_100) * 1_000_000)
                publish(assembler.accept(line(index * 2L + 1, tail, start + 8_200)))
                for (ms in 8_400L..10_400L step 200) {
                    assembler.observeSpeechActivity(false, (start + ms) * 1_000_000)
                    publish(assembler.tick((start + ms) * 1_000_000))
                }
                assertEquals("Complete unit must commit without stopping input", index + 1, inputs.size)
                assertEquals(expected[index], inputs.last().first)
                if (index > 0) assertTrue(inputs.last().second.orEmpty().contains(expected[index - 1]))
            }
            publish(assembler.finish(130_000_000_000))
            assertEquals(expected, inputs.map { it.first })
            assertEquals(expected.map { "translated:$it" }, spoken)
            assertTrue(running.health.value.all { it.droppedUtterances == 0L })
        } finally { running.close() }
    }

    @Test
    fun `assembler capacity recovery continues translated speech and original audio on the same session`() = runTest {
        val streams = AudioStreamRegistry()
        val session = streams.configure(listOf(
            AudioChannelDescriptor("source", "Original", "ko", 16_000),
            AudioChannelDescriptor("en", "English", "en", 16_000),
        ))
        val assembler = ProviderTranscriptSemanticAssembler(maximumPendingProviderLines = 2)
        val spoken = mutableListOf<String>()
        val monitor = session.subscribeLocalMonitor("en")
        val generation = session.generation
        val source = flow {
            assembler.observeSpeechActivity(true, 0)
            repeat(3) { index ->
                assembler.accept(RecognizedUtterance(index.toLong(), "설명 중인 장소의", "ko", true,
                    index * 1_000_000L)).forEach { emit(it) }
            }
            assembler.finish(4_000_000L).forEach { emit(it) }
            assembler.accept(RecognizedUtterance(3, "재연결 후 안내입니다", "ko", true,
                5_000_000L)).forEach { emit(it) }
            assembler.finish(6_000_000L).forEach { emit(it) }
        }
        val running = TranslationBroadcastPipeline(streams,
            TranslationEngineProvider { TextTranslationEngine { text, _, _ -> text } },
            SpeechSynthesisEngineProvider { object : SpeechSynthesisEngine {
                override fun synthesize(text: String, languageTag: String) = flow<PcmAudioFrame> {
                    spoken += text
                    emit(PcmAudioFrame(ByteArray(640) { if (it % 4 == 1) 32 else 0 }, 1))
                }
            } },
        ).start(this, source, listOf(TranslationTarget("en", "English", "en", 16_000)),
            sourceLanguageTag = "ko", streamSession = session)
        try {
            advanceUntilIdle()
            assertEquals(listOf("설명 중인 장소의 설명 중인 장소의", "설명 중인 장소의", "재연결 후 안내입니다"), spoken)
            val audio = withTimeout(1_000L) { List(3) { monitor.frames.receive() } }
            assertTrue(audio.all { it.bytes.size == 640 && it.bytes.any { byte -> byte != 0.toByte() } })
            assertEquals(3, audio.map { it.utteranceSequence }.toSet().size)
            assertEquals(generation, streams.currentSession().generation)
            assertEquals(0L, running.health.value.single().synthesisFailures)
            assertEquals(0L, running.health.value.single().translationFailures)
            assertEquals(StreamPublishStatus.PUBLISHED,
                session.tryPublish("source", PcmAudioFrame(byteArrayOf(0, 32, 0, -32), 1)).status)
        } finally {
            running.close()
            monitor.close()
            session.close()
        }
    }

    @Test
    fun `long hesitation keeps subject with predicate across three language queues`() = runTest {
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
            assertTrue("A pause must not turn a subject into a sentence", translated.isEmpty())
            assertTrue(spoken.isEmpty())

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
                "이번 전시회는 조선 후기의 역사를 소개합니다",
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

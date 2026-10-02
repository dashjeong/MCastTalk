package app.guidecast.core.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Acoustic idle is measured from PCM; a wall-clock deadline is never a voice boundary. */
class IdleSentenceCompletionRegressionTest {
    @Test
    fun `idle diagnostic signals only an emitted cap final and carries no transcript data`() {
        val reasons = mutableListOf<TranscriptAssemblyRecovery>()
        val assembler = ProviderTranscriptSemanticAssembler(onRecovery = reasons::add)
        assembler.observeSpeechActivity(true, 0)
        assembler.accept(partial(0, "다음 장소에서는 우리가", 100))
        assembler.quiet(200, 8_199)
        assembler.tick(8_199L.ms)
        assertTrue(reasons.isEmpty())
        assembler.observeSpeechActivity(false, 8_200L.ms)
        assertTrue(assembler.tick(8_200L.ms).single().isFinal)
        assembler.tick(20_000L.ms)
        assembler.finish(20_100L.ms)
        assertEquals(listOf(TranscriptAssemblyRecovery.IDLE_SILENCE_LIMIT), reasons)
    }

    @Test
    fun `small cumulative ASR prefix corrections cannot replay the committed sentence`() {
        listOf(
            "회의 자료를 보내줘" to "회의자료를 보내줘",
            "오늘 회의 자료를 보내줘" to "내일 회의 자료를 보내줘",
            "오늘 회의 자료를 보내줘" to "오늘 중요한 회의 자료를 보내줘",
            "오늘 회의 자료를 보내줘" to "회의 자료를 보내줘",
        ).forEach { (original, revision) ->
            val assembler = ProviderTranscriptSemanticAssembler()
            assembler.observeSpeechActivity(true, 0)
            assembler.accept(partial(0, original, 100))
            assembler.quiet(200, 2_200)
            assertEquals(original, assembler.tick(2_200L.ms).single { it.isFinal }.text)
            assembler.observeSpeechActivity(true, 2_300L.ms)
            assembler.accept(partial(0, "$revision 다음 안건을 설명해", 2_400))
            assembler.accept(final(0, "$revision 다음 안건을 설명해", 2_500))
            assembler.quiet(2_600, 3_400)
            assertEquals(listOf("다음 안건을 설명해"),
                assembler.tick(3_400L.ms).filter { it.isFinal }.map { it.text })
            assertTrue(assembler.finish(3_500L.ms).isEmpty())
        }
    }

    @Test
    fun `unmatched new same-id sentence is retained rather than silently deleting its words`() {
        listOf("다음 안건을 설명해", "회의 자료를 검토해").forEach { nextText ->
            val assembler = ProviderTranscriptSemanticAssembler()
            assembler.observeSpeechActivity(true, 0)
            assembler.accept(partial(0, "회의 자료를 보내줘", 100))
            assembler.quiet(200, 2_200)
            assembler.tick(2_200L.ms)
            assembler.observeSpeechActivity(true, 2_300L.ms)
            assembler.accept(partial(0, nextText, 2_400))
            assembler.accept(final(0, nextText, 2_500))
            assembler.quiet(2_600, 3_400)
            assertEquals(nextText, assembler.tick(3_400L.ms).single { it.isFinal }.text)
        }
    }

    @Test
    fun `new imperative completions never split an attached auxiliary predicate`() {
        listOf(
            "자료를 설명해 드릴게요", "실내에서 기다려 주세요", "해 보니 생각보다 쉽더군요",
            "자료를 확인해 보세요", "그에게 말해 줬습니다",
        ).forEach { text ->
            val assembler = ProviderTranscriptSemanticAssembler()
            assembler.observeSpeechActivity(true, 0)
            assertFalse(text, assembler.accept(final(0, text, 100)).any { it.isFinal })
            assembler.quiet(200, 1_400)
            assertEquals(listOf(text), assembler.tick(1_400L.ms).filter { it.isFinal }.map { it.text })
            assertTrue(assembler.finish(1_500L.ms).isEmpty())
        }
    }
    @Test
    fun `product idle cap publishes unchanged unfinished tails once after eight measured seconds`() {
        listOf("다음 장소에서는 우리가", "촬영하면 안", "이 사실을 알지", "그는 \"가지 마")
            .forEach { text ->
                val assembler = ProviderTranscriptSemanticAssembler()
                assembler.observeSpeechActivity(true, 0)
                assembler.accept(partial(0, text, 100))
                assembler.quiet(200, 8_199)
                assertFalse(text, assembler.tick(8_199L.ms).any { it.isFinal })
                assembler.observeSpeechActivity(false, 8_200L.ms)
                assertEquals(text, assembler.tick(8_200L.ms).single { it.isFinal }.text)
                assertTrue(assembler.tick(20_000L.ms).isEmpty())
                assertTrue(assembler.accept(final(0, "$text 추가", 20_100)).isEmpty())
                assertTrue(assembler.finish(20_200L.ms).isEmpty())
            }
    }

    @Test
    fun `idle cap cannot turn missing PCM into eight seconds of quiet`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        assembler.observeSpeechActivity(true, 0)
        assembler.accept(partial(0, "다음 장소에서는 우리가", 100))
        assembler.quiet(200, 7_999)
        // Wall clock is much later, but less than 8s of quiet PCM actually arrived.
        assertFalse(assembler.tick(20_000L.ms).any { it.isFinal })
        assertFalse(assembler.tick(30_000L.ms).any { it.isFinal })
    }

    @Test
    fun `idle cap keeps newly revised text pending until its stability window completes`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        assembler.observeSpeechActivity(true, 0)
        assembler.accept(partial(0, "다음 장소에서는 우리가", 100))
        assembler.quiet(200, 8_200)
        assembler.accept(partial(0, "다음 장소에서는 저희가", 8_200))
        assertFalse(assembler.tick(8_699L.ms).any { it.isFinal })
        assembler.quiet(8_400, 8_700)
        assertEquals("다음 장소에서는 저희가", assembler.tick(8_700L.ms).single { it.isFinal }.text)
    }

    @Test
    fun `idle cap preserves an unfinished tail through active speech and short quiet resumption`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        assembler.observeSpeechActivity(true, 0)
        assembler.accept(partial(0, "촬영하면 안", 100))
        (200L..20_000L step 200L).forEach { assembler.observeSpeechActivity(true, it.ms) }
        assertFalse(assembler.tick(20_000L.ms).any { it.isFinal })
        assembler.quiet(20_200, 28_199)
        assertFalse(assembler.tick(28_199L.ms).any { it.isFinal })
        assembler.observeSpeechActivity(true, 28_200L.ms)
        assembler.accept(final(0, "촬영하면 안 됩니다", 28_300))
        assembler.quiet(28_400, 29_200)
        assertEquals("촬영하면 안 됩니다", assembler.tick(29_200L.ms).single { it.isFinal }.text)
        assertTrue(assembler.finish(29_300L.ms).isEmpty())
    }

    @Test
    fun `late final remains blocked but new speech appended to same provider id continues`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        assembler.observeSpeechActivity(true, 0)
        assembler.accept(partial(0, "회의 자료를 보내줘", 100))
        assembler.quiet(200, 2_200)
        assertEquals("회의 자료를 보내줘", assembler.tick(2_200L.ms).single { it.isFinal }.text)
        assertTrue(assembler.accept(final(0, "회의 자료를 보내줘 지금", 2_300)).isEmpty())
        assembler.observeSpeechActivity(true, 2_400L.ms)
        assembler.accept(partial(0, "회의 자료를 보내줘 다음 안건을 설명해", 2_500))
        assembler.accept(final(0, "회의 자료를 보내줘 다음 안건을 설명해", 2_600))
        assembler.quiet(2_700, 3_500)
        val next = assembler.tick(3_500L.ms).single { it.isFinal }
        assertEquals("다음 안건을 설명해", next.text)
        assertEquals("회의 자료를 보내줘", next.contextBefore)
        assertTrue(assembler.finish(3_600L.ms).isEmpty())
    }

    @Test
    fun `single informal imperative commits without a following recognizer callback`() {
        listOf("회의 자료를 보내줘", "다시 한번 설명해", "음량을 조금 내려 줘", "잠깐 기다려")
            .forEach { text ->
                val assembler = ProviderTranscriptSemanticAssembler()
                assembler.observeSpeechActivity(true, 0)
                assembler.accept(partial(0, text, 100))
                assembler.quiet(200, 2_199)
                assertFalse(text, assembler.tick(2_199L.ms).any { it.isFinal })
                assembler.observeSpeechActivity(false, 2_200L.ms)
                assertEquals(text, assembler.tick(2_200L.ms).single { it.isFinal }.text)
                assertTrue(assembler.tick(12_000L.ms).isEmpty())
                assertTrue(assembler.finish(12_100L.ms).isEmpty())
            }
    }

    @Test
    fun `late changed final cannot retranslate a sentence already released by silence`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        assembler.observeSpeechActivity(true, 0)
        assembler.accept(partial(0, "회의 자료를 보내줘", 100))
        assembler.quiet(200, 2_200)
        val first = assembler.tick(2_200L.ms).single { it.isFinal }

        // Both repeated and revised callbacks belong to the already committed provider line.
        assertTrue(assembler.accept(final(0, "회의 자료를 보내줘", 2_300)).isEmpty())
        assertTrue(assembler.accept(final(0, "회의 자료를 보내줘 지금", 2_400)).isEmpty())
        assertTrue(assembler.finish(2_500L.ms).isEmpty())
        assembler.observeSpeechActivity(true, 2_600L.ms)
        assembler.accept(final(1, "다음 안건을 설명해", 2_700))
        assembler.quiet(2_800, 3_600)
        val next = assembler.tick(3_600L.ms).single { it.isFinal }
        assertEquals("다음 안건을 설명해", next.text)
        assertEquals(first.text, next.contextBefore)
        assertTrue(first.sequence != next.sequence)
    }

    @Test
    fun `quiet completion releases hidden later provider line without dropping it`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        assembler.observeSpeechActivity(true, 0)
        assembler.accept(partial(0, "회의 자료를 보내줘", 100))
        assertTrue(assembler.accept(final(1, "다음 안건을 설명해", 200)).isEmpty())
        assembler.quiet(300, 2_300)
        val first = assembler.tick(2_300L.ms).filter { it.isFinal }
        assertEquals(listOf("회의 자료를 보내줘"), first.map { it.text })
        assembler.quiet(2_550, 3_100)
        val second = assembler.tick(3_100L.ms).filter { it.isFinal }
        assertEquals(listOf("다음 안건을 설명해"), second.map { it.text })
        assertEquals("회의 자료를 보내줘", second.single().contextBefore)
        assertTrue(assembler.finish(3_200L.ms).isEmpty())
    }

    @Test
    fun `eight hundred millisecond capture lag retains measured quiet without duplicate dispatch`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        assembler.observeSpeechActivity(true, 0)
        assembler.accept(partial(0, "회의 자료를 보내줘", 100))
        assembler.quiet(200, 2_200)
        assertEquals("회의 자료를 보내줘", assembler.tick(3_000L.ms).single { it.isFinal }.text)
        assertTrue(assembler.tick(3_100L.ms).isEmpty())
        assertTrue(assembler.accept(final(0, "회의 자료를 보내줘", 3_200)).isEmpty())
    }

    @Test
    fun `missing PCM or continued speech cannot be mistaken for idle`() {
        for (mode in listOf("speech", "missing", "shortQuiet")) {
            val assembler = ProviderTranscriptSemanticAssembler()
            assembler.observeSpeechActivity(true, 0)
            assembler.accept(partial(0, "회의 자료를 보내줘", 100))
            when (mode) {
                "speech" -> (200L..12_200L step 200L).forEach {
                    assembler.observeSpeechActivity(true, it.ms)
                }
                "shortQuiet" -> assembler.quiet(200, 500)
            }
            assertFalse(mode, assembler.tick(12_200L.ms).any { it.isFinal })
        }
    }

    @Test
    fun `short hesitation preserves negative and dependent clause until resumed speech`() {
        listOf("자료를 보내지" to "않았습니다", "회의가 끝나면" to "자료를 보내줘")
            .forEach { (prefix, suffix) ->
                val assembler = ProviderTranscriptSemanticAssembler()
                assembler.observeSpeechActivity(true, 0)
                assembler.accept(partial(0, prefix, 100))
                assembler.quiet(200, 1_400)
                assertFalse(prefix, assembler.tick(1_400L.ms).any { it.isFinal })
                assembler.observeSpeechActivity(true, 1_500L.ms)
                assembler.accept(final(0, "$prefix $suffix", 1_600))
                assembler.quiet(1_700, 2_500)
                assertEquals("$prefix $suffix", assembler.tick(2_500L.ms).single { it.isFinal }.text)
                assertTrue(assembler.finish(2_600L.ms).isEmpty())
            }
    }

    @Test
    fun `explicit stop and a fresh session never repeat the previous idle final`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        assembler.observeSpeechActivity(true, 0)
        assembler.accept(partial(0, "회의 자료를 보내줘", 100))
        assembler.quiet(200, 2_200)
        val first = assembler.tick(2_200L.ms).single { it.isFinal }
        assertTrue(assembler.finish(2_300L.ms).isEmpty())
        assembler.observeSpeechActivity(true, 2_400L.ms)
        assembler.accept(partial(1, first.text, 2_500))
        val second = assembler.finish(2_600L.ms).single { it.isFinal }
        assertEquals(first.text, second.text)
        assertTrue(first.sequence != second.sequence)
        assertTrue(assembler.finish(2_700L.ms).isEmpty())
    }

    private fun partial(sequence: Long, text: String, millis: Long) =
        RecognizedUtterance(sequence, text, "ko", false, 0L, millis.ms)

    private fun final(sequence: Long, text: String, millis: Long) =
        partial(sequence, text, millis).copy(isFinal = true)

    private fun ProviderTranscriptSemanticAssembler.quiet(from: Long, through: Long) {
        observeSpeechActivity(false, from.ms)
        (from + 200..through step 200).forEach { observeSpeechActivity(false, it.ms) }
        observeSpeechActivity(false, through.ms)
    }

    private val Long.ms: Long get() = this * 1_000_000L
}

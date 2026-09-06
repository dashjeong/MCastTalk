package app.guidecast.core.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderTranscriptSemanticAssemblerTest {
    @Test
    fun `speech resumed before two seconds stays together and commits once after final pause`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        assembler.observeSpeechActivity(true, 0)
        assembler.accept(partial(90, "Welcome", 100).copy(sourceLanguageTag = "en-US"))
        assembler.observeContinuousQuiet(200, 2_100)
        assertFalse(assembler.tick(2_100L.ms).any { it.isFinal })
        assembler.observeSpeechActivity(true, 2_150L.ms)
        assembler.accept(partial(90, "Welcome everyone", 2_200).copy(sourceLanguageTag = "en-US"))
        assembler.observeContinuousQuiet(2_300, 4_299)
        assertFalse(assembler.tick(4_299L.ms).any { it.isFinal })
        assembler.observeSpeechActivity(false, 4_300L.ms)
        assertEquals("Welcome everyone", assembler.tick(4_300L.ms).single { it.isFinal }.text)
        assertTrue(assembler.finish(4_400L.ms).isEmpty())
    }

    @Test
    fun `provider final with two complete sentences releases earlier one without silence`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        assembler.observeSpeechActivity(true, 0)
        val output = assembler.accept(providerFinal(91,
            "첫 장소에 도착했습니다 다음 장소로 함께 이동합니다", 100))
        assertEquals("첫 장소에 도착했습니다", output.single { it.isFinal }.text)
        val tail = assembler.finish(200L.ms).single { it.isFinal }
        assertEquals("다음 장소로 함께 이동합니다", tail.text)
        assertEquals("첫 장소에 도착했습니다", tail.contextBefore)
        assertTrue(assembler.finish(300L.ms).isEmpty())
    }

    @Test
    fun `speech with no provider result requests one endpoint after verified silence`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
        assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 200L.ms)
        assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 300L.ms)

        assertFalse(assembler.shouldRequestRecognizerEndpoint(3_199L.ms))
        assembler.observeContinuousQuiet(fromMillis = 3_200, throughMillis = 6_200)
        assertTrue(assembler.shouldRequestRecognizerEndpoint(6_200L.ms))
        assertFalse(assembler.shouldRequestRecognizerEndpoint(6_300L.ms))
    }

    @Test
    fun `single English partial commits once after silence and later speech is a separate unit`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        val first = partial(0, "Daddy looks at this.", 100)
            .copy(sourceLanguageTag = "en-US")
        assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
        assembler.accept(first)
        assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 200L.ms)
        assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 300L.ms)

        assertFalse(assembler.tick(3_199L.ms).any(RecognizedUtterance::isFinal))
        assembler.observeContinuousQuiet(fromMillis = 3_200, throughMillis = 6_200)
        val firstFinal = assembler.tick(6_200L.ms).single(RecognizedUtterance::isFinal)
        assertEquals("Daddy looks at this.", firstFinal.text)
        assertTrue(assembler.tick(6_300L.ms).isEmpty())

        assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 6_500L.ms)
        assembler.accept(
            partial(1, "that is the next point", 6_600)
                .copy(sourceLanguageTag = "en-US"),
        )
        assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 6_700L.ms)
        assembler.observeContinuousQuiet(fromMillis = 6_950, throughMillis = 8_699)
        assertFalse(assembler.tick(8_699L.ms).any(RecognizedUtterance::isFinal))
        assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 8_700L.ms)
        val secondFinal = assembler.tick(8_700L.ms).single(RecognizedUtterance::isFinal)

        assertEquals("that is the next point", secondFinal.text)
        assertEquals("Daddy looks at this.", secondFinal.contextBefore)
        assertTrue(assembler.tick(9_800L.ms).isEmpty())
    }

    @Test
    fun `new provider sequence follows a quiet speech fallback without VAD activation`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 0)
        assembler.accept(
            partial(80, "Quiet first sentence", 100).copy(sourceLanguageTag = "en-US"),
        )
        (250L..5_000L step 250L).forEach { millis ->
            assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = millis.ms)
        }
        assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 5_100L.ms)
        val first = assembler.tick(5_100L.ms).single(RecognizedUtterance::isFinal)

        val nextAccept = assembler.accept(
            partial(81, "Quiet second sentence", 5_200).copy(sourceLanguageTag = "en-US"),
        )
        assertFalse(nextAccept.any(RecognizedUtterance::isFinal))
        (5_250L..10_250L step 250L).forEach { millis ->
            assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = millis.ms)
        }
        val second = assembler.tick(10_250L.ms).single(RecognizedUtterance::isFinal)

        assertEquals("Quiet first sentence", first.text)
        assertEquals("Quiet second sentence", second.text)
        assertEquals("Quiet first sentence", second.contextBefore)
    }

    @Test
    fun `complete brief answers survive but incomplete single words remain pending`() {
        listOf("네", "예.").forEach { text ->
            val assembler = ProviderTranscriptSemanticAssembler()
            assembler.observeSpeechActivity(true, 0L)
            assembler.accept(providerFinal(0, text, 100))
            assembler.observeSpeechActivity(false, 200L.ms)
            assembler.observeContinuousQuiet(fromMillis = 450, throughMillis = 1_400)
            assertEquals(text, assembler.tick(1_400L.ms).single { it.isFinal }.text)
        }
        listOf("왜.", "안.").forEach { text ->
            val assembler = ProviderTranscriptSemanticAssembler()
            assembler.observeSpeechActivity(true, 0L)
            assembler.accept(providerFinal(0, text, 100))
            assembler.observeSpeechActivity(false, 200L.ms)
            assertFalse(assembler.tick(3_000L.ms).any { it.isFinal })
        }
    }

    @Test
    fun `Korean provider alias changes across attempts preserve the pending sentence`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        assembler.observeSpeechActivity(true, 0L)
        assembler.accept(providerFinal(0, "이번 전시회는", 100).copy(sourceLanguageTag = "ko-KR"))
        assembler.sealProviderAttempt(200L.ms)
        assembler.accept(providerFinal(1, "역사를 소개합니다", 300).copy(sourceLanguageTag = "ko"))
        assembler.observeSpeechActivity(false, 400L.ms)
        assembler.observeContinuousQuiet(fromMillis = 650, throughMillis = 1_600)
        assertEquals("이번 전시회는 역사를 소개합니다", assembler.tick(1_600L.ms).single { it.isFinal }.text)
    }

    @Test
    fun `provider finals remain pending until the complete Korean sentence and natural pause`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        val output = mutableListOf<RecognizedUtterance>()
        assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)

        output += assembler.accept(providerFinal(0, "오늘은 민통선 내부의.", 100))
        output += assembler.accept(partial(1, "역사와 생태를 함께", 300))

        assertFalse(output.any(RecognizedUtterance::isFinal))

        assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 500L.ms)
        assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 500L.ms)
        output += assembler.accept(
            providerFinal(1, "역사와 생태를 함께 설명하겠습니다", 600),
        )
        assertFalse(output.any(RecognizedUtterance::isFinal))

        assembler.observeContinuousQuiet(fromMillis = 750, throughMillis = 1_700)
        output += assembler.tick(1_700L.ms)

        assertEquals(
            listOf("오늘은 민통선 내부의 역사와 생태를 함께 설명하겠습니다"),
            output.filter(RecognizedUtterance::isFinal).map(RecognizedUtterance::text),
        )
    }

    @Test
    fun `automatic recognizer attempt boundaries retain an incomplete provider line`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)

        val firstAttempt = assembler.accept(providerFinal(10, "이 안내가 필요한 이유는", 100))
        val secondAttempt = assembler.accept(
            providerFinal(11, "모두의 안전을 지키기 위해서입니다", 400),
        )

        assertFalse(firstAttempt.any(RecognizedUtterance::isFinal))
        assertFalse(secondAttempt.any(RecognizedUtterance::isFinal))

        assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 500L.ms)
        assembler.observeContinuousQuiet(fromMillis = 750, throughMillis = 1_700)
        val completed = assembler.tick(1_700L.ms)
        assertEquals(
            "이 안내가 필요한 이유는 모두의 안전을 지키기 위해서입니다",
            completed.single(RecognizedUtterance::isFinal).text,
        )
    }

    @Test
    fun `partial left by provider error is sealed but not flushed before the next attempt`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
        assembler.accept(partial(12, "이 안내가 필요한 이유는", 100))

        val sealed = assembler.sealProviderAttempt(200L.ms)
        val continued = assembler.accept(
            providerFinal(13, "모두의 안전을 지키기 위해서입니다", 400),
        )

        assertFalse(sealed.any(RecognizedUtterance::isFinal))
        assertFalse(continued.any(RecognizedUtterance::isFinal))
        assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 500L.ms)
        assembler.observeContinuousQuiet(fromMillis = 750, throughMillis = 1_700)
        assertEquals(
            "이 안내가 필요한 이유는 모두의 안전을 지키기 위해서입니다",
            assembler.tick(1_700L.ms).single(RecognizedUtterance::isFinal).text,
        )
    }

    @Test
    fun `provider lines join Korean predicates negation and spoken decimal before dispatch`() {
        val cases = listOf(
            Triple("이곳은 사진을 찍으면 안", "됩니다", "이곳은 사진을 찍으면 안 됩니다"),
            Triple("식사를 하지", "않았습니다", "식사를 하지 않았습니다"),
            Triple("원의 비율은 삼 점", "일 사입니다", "원의 비율은 삼 점 일 사입니다"),
        )
        cases.forEachIndexed { index, (first, second, expected) ->
            val assembler = ProviderTranscriptSemanticAssembler()
            assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
            val output = mutableListOf<RecognizedUtterance>()
            output += assembler.accept(providerFinal(index * 2L, first, 100))
            output += assembler.accept(providerFinal(index * 2L + 1L, second, 300))
            assertFalse(first, output.any(RecognizedUtterance::isFinal))

            assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 400L.ms)
            assembler.observeContinuousQuiet(fromMillis = 650, throughMillis = 1_600)
            output += assembler.tick(1_600L.ms)
            assertEquals(expected, output.single(RecognizedUtterance::isFinal).text)
        }
    }

    @Test
    fun `twelve second endpoint request does not flush and the next attempt completes the sentence`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
        assembler.accept(providerFinal(20, "오래된 철길 주변의 역사와", 100))

        assertFalse(assembler.tick(12_000L.ms).any(RecognizedUtterance::isFinal))
        assertTrue(assembler.shouldRequestRecognizerEndpoint(12_000L.ms))
        assertFalse(assembler.shouldRequestRecognizerEndpoint(12_100L.ms))

        // A new sequence represents a fresh provider/recognizer attempt. No finish occurs between
        // attempts, so the immutable fragment remains ahead of the new provider line.
        assembler.accept(providerFinal(21, "생태적 의미를 함께 설명하겠습니다", 12_300))
        assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 12_400L.ms)
        assembler.observeContinuousQuiet(fromMillis = 12_650, throughMillis = 13_600)
        val completed = assembler.tick(13_600L.ms)

        assertEquals(
            "오래된 철길 주변의 역사와 생태적 의미를 함께 설명하겠습니다",
            completed.single(RecognizedUtterance::isFinal).text,
        )
    }

    @Test
    fun `interleaved provider lines retain first-observed order without duplicate finals`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
        val output = mutableListOf<RecognizedUtterance>()
        output += assembler.accept(partial(30, "첫 번째 안내는", 100))
        output += assembler.accept(partial(31, "안전을 위한 설명입니다", 200))
        output += assembler.accept(providerFinal(30, "첫 번째 안내는", 300))
        output += assembler.accept(providerFinal(31, "안전을 위한 설명입니다", 400))

        assertFalse(output.any(RecognizedUtterance::isFinal))
        assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 500L.ms)
        assembler.observeContinuousQuiet(fromMillis = 750, throughMillis = 1_700)
        output += assembler.tick(1_700L.ms)

        assertEquals(
            listOf("첫 번째 안내는 안전을 위한 설명입니다"),
            output.filter(RecognizedUtterance::isFinal).map(RecognizedUtterance::text),
        )
    }

    @Test
    fun `later completed line waits behind an earlier revisable line`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
        assembler.accept(partial(40, "첫 안내는", 100))
        val hiddenLaterFinal = assembler.accept(
            providerFinal(41, "안전을 위한 설명입니다", 200),
        )
        assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 300L.ms)

        assertTrue(hiddenLaterFinal.isEmpty())
        assembler.observeContinuousQuiet(fromMillis = 550, throughMillis = 1_500)
        assertFalse(assembler.tick(1_500L.ms).any(RecognizedUtterance::isFinal))

        val ordered = assembler.accept(providerFinal(40, "첫 번째 안내는", 1_600))
        assertFalse(ordered.any(RecognizedUtterance::isFinal))
        assembler.observeContinuousQuiet(fromMillis = 1_750, throughMillis = 2_099)
        assertFalse(assembler.tick(2_099L.ms).any(RecognizedUtterance::isFinal))
        assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 2_100L.ms)
        assertEquals(
            "첫 번째 안내는 안전을 위한 설명입니다",
            assembler.tick(2_100L.ms).single(RecognizedUtterance::isFinal).text,
        )
    }

    @Test
    fun `late provider final cleans a sentence already committed from a partial`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
        assembler.accept(partial(50, "첫 문장은 여기서 끝납니다", 100))
        assembler.accept(partial(50, "첫 문장은 여기서 끝납니다", 200))
        assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 300L.ms)
        assembler.observeContinuousQuiet(fromMillis = 550, throughMillis = 1_500)
        assertEquals(
            "첫 문장은 여기서 끝납니다",
            assembler.tick(1_500L.ms).single(RecognizedUtterance::isFinal).text,
        )

        assertTrue(assembler.accept(providerFinal(50, "첫 문장은 여기서 끝납니다", 1_600)).isEmpty())
        assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 1_700L.ms)
        assembler.accept(providerFinal(51, "둘째 문장도 정상입니다", 1_800))
        assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 1_900L.ms)
        assembler.observeContinuousQuiet(fromMillis = 2_150, throughMillis = 3_100)

        assertEquals(
            "둘째 문장도 정상입니다",
            assembler.tick(3_100L.ms).single(RecognizedUtterance::isFinal).text,
        )
    }

    @Test
    fun `incomplete particles connectives and speculative punctuation never become pause finals`() {
        listOf(
            "민통선 내부의.",
            "이동하기 때문에.",
            "하지만.",
            "설명할 수 없는데.",
            "이게 어떻게.",
            "어라 이렇게 어 없는데.",
            "이번 전시회는",
            "역사와 생태를",
            "매일 오후 세 시에",
            "이번 전시회는.",
            "역사와 생태를,",
        )
            .forEachIndexed { index, fragment ->
                val assembler = ProviderTranscriptSemanticAssembler()
                assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
                val accepted = assembler.accept(providerFinal(index.toLong(), fragment, 100))
                assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 200L.ms)

                val afterLongPause = assembler.tick(2_500L.ms)

                assertFalse(fragment, accepted.any(RecognizedUtterance::isFinal))
                assertFalse(fragment, afterLongPause.any(RecognizedUtterance::isFinal))
            }
    }

    @Test
    fun `recognizer punctuation after Korean particles is removed before the completed continuation`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
        val first = assembler.accept(providerFinal(60, "이번 전시회는.", 100))
        val second = assembler.accept(providerFinal(61, "역사와 생태를,", 200))
        val third = assembler.accept(providerFinal(62, "소개합니다", 300))

        assertFalse((first + second + third).any(RecognizedUtterance::isFinal))
        assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 400L.ms)
        assembler.observeContinuousQuiet(fromMillis = 650, throughMillis = 1_600)
        assertEquals(
            "이번 전시회는 역사와 생태를 소개합니다",
            assembler.tick(1_600L.ms).single(RecognizedUtterance::isFinal).text,
        )
    }

    @Test
    fun `real input EOF flushes one incomplete tail exactly once`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        assembler.accept(providerFinal(4, "마지막 미완 안내의", 100))

        val first = assembler.finish(500L.ms)
        val second = assembler.finish(600L.ms)

        assertEquals("마지막 미완 안내의", first.single().text)
        assertTrue(second.isEmpty())
    }

    @Test
    fun `direct input EOF includes a completed line hidden behind an earlier partial`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
        assembler.accept(partial(70, "이 안내가 필요한 이유는", 100))
        assertTrue(
            assembler.accept(providerFinal(71, "모두의 안전을 지키기 위해서입니다", 200))
                .isEmpty(),
        )

        val eof = assembler.finish(300L.ms)

        assertEquals(
            listOf("이 안내가 필요한 이유는 모두의 안전을 지키기 위해서입니다"),
            eof.filter(RecognizedUtterance::isFinal).map(RecognizedUtterance::text),
        )
        assertTrue(assembler.finish(400L.ms).isEmpty())
    }

    @Test
    fun `pending provider line limit fails explicitly without dropping old text`() {
        val assembler = ProviderTranscriptSemanticAssembler(maximumPendingProviderLines = 2)
        assembler.accept(providerFinal(1, "첫 미완 구간의", 100))
        assembler.accept(providerFinal(2, "다음 미완 구간의", 200))

        val error = assertThrows(ProviderTranscriptAssemblyOverflowException::class.java) {
            assembler.accept(providerFinal(3, "상한을 넘는 구간", 300))
        }

        assertTrue(error.message.orEmpty().contains("임의로 자르지 않고"))
    }

    private fun partial(sequence: Long, text: String, millis: Long) =
        utterance(sequence, text, millis, isFinal = false)

    private fun providerFinal(sequence: Long, text: String, millis: Long) =
        utterance(sequence, text, millis, isFinal = true)

    private fun ProviderTranscriptSemanticAssembler.observeContinuousQuiet(
        fromMillis: Long,
        throughMillis: Long,
    ) {
        var observedMillis = fromMillis
        while (observedMillis < throughMillis) {
            observeSpeechActivity(isSpeech = false, capturedAtNanos = observedMillis.ms)
            observedMillis += 250L
        }
        observeSpeechActivity(isSpeech = false, capturedAtNanos = throughMillis.ms)
    }

    private fun utterance(sequence: Long, text: String, millis: Long, isFinal: Boolean) =
        RecognizedUtterance(
            sequence = sequence,
            text = text,
            sourceLanguageTag = "ko-KR",
            isFinal = isFinal,
            capturedAtElapsedRealtimeNanos = millis.ms,
            recognizedAtElapsedRealtimeNanos = millis.ms,
        )

    private val Int.ms: Long get() = toLong().ms
    private val Long.ms: Long get() = this * 1_000_000L
}

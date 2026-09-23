package app.guidecast.core.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderTranscriptSemanticAssemblerTest {
    @Test
    fun `continuous final lines release committed history without freezing the pending tail`() {
        val recoveries = mutableListOf<TranscriptAssemblyRecovery>()
        val assembler = ProviderTranscriptSemanticAssembler(onRecovery = recoveries::add)
        assembler.observeSpeechActivity(true, 0L)
        val finals = mutableListOf<RecognizedUtterance>()
        val expected = (0 until 1_000).map { "Sentence number $it is complete." }
        expected.forEachIndexed { index, text ->
            finals += assembler.accept(providerFinal(index.toLong(), text, index * 100L + 100)
                .copy(sourceLanguageTag = "en-US")).filter { it.isFinal }
        }
        finals += assembler.finish(101_000L.ms).filter { it.isFinal }
        assertEquals(expected, finals.map { it.text })
        assertEquals(finals.size, finals.map { it.sequence }.toSet().size)
        assertTrue(assembler.finish(102_000L.ms).isEmpty())
        assertTrue("Normal continuous speech must not need capacity resets", recoveries.isEmpty())
    }

    @Test
    fun `character capacity recovery retains both long provider fragments exactly once`() {
        val reasons = mutableListOf<TranscriptAssemblyRecovery>()
        val assembler = ProviderTranscriptSemanticAssembler(onRecovery = reasons::add)
        val first = "가".repeat(1_100)
        val second = "나".repeat(1_100)
        assembler.accept(providerFinal(0, first, 100))
        val output = assembler.accept(providerFinal(1, second, 200)) + assembler.finish(300L.ms)
        assertEquals(listOf(first, second), output.filter { it.isFinal }.map { it.text })
        assertEquals(listOf(TranscriptAssemblyRecovery.CHARACTER_LIMIT), reasons)
    }

    @Test
    fun `rejected oversized callback leaves a recoverable pending sentence`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        val prefix = "가".repeat(1_000)
        assembler.accept(providerFinal(0, prefix, 50))
        assembler.accept(partial(1, "보존해야 하는 문장", 100))
        assertThrows(ProviderTranscriptAssemblyOverflowException::class.java) {
            assembler.accept(partial(1, "큰".repeat(1_100), 200))
        }
        assertEquals("$prefix 보존해야 하는 문장", assembler.finish(300L.ms).single { it.isFinal }.text)
        assembler.accept(providerFinal(2, "다음 문장입니다", 400))
        assertEquals("다음 문장입니다", assembler.finish(500L.ms).single { it.isFinal }.text)
    }

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
            "표지판을 확인하고,",
            "도착하면.",
            "밥을 먹었으면.",
            "도착했다면.",
            "사진을 찍으면 안.",
            "입장 요금은 15.",
            "방송은 오후 세.",
        )
            .forEachIndexed { index, fragment ->
                val assembler = ProviderTranscriptSemanticAssembler()
                assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
                val accepted = assembler.accept(providerFinal(index.toLong(), fragment, 100))
                assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 200L.ms)

                // Keep supplying real quiet PCM observations. A single old VAD observation only
                // tests stale-data protection and previously hid the three-second fragment bug.
                assembler.observeContinuousQuiet(fromMillis = 450, throughMillis = 12_200)
                val afterLongPause = assembler.tick(12_200L.ms)

                assertFalse(fragment, accepted.any(RecognizedUtterance::isFinal))
                assertFalse(fragment, afterLongPause.any(RecognizedUtterance::isFinal))
            }
    }

    @Test
    fun `breaths and long hesitations preserve complete Korean meaning across provider lines`() {
        val cases = listOf(
            "이번 전시회는." to "역사를 소개합니다",
            "표지판을 확인하고," to "안내를 따라 이동하세요",
            "비가 오면." to "안쪽에서 기다리세요",
            "촬영하면 안." to "됩니다",
            "비용은 15." to "만원입니다",
            "방송은 오후 세." to "시에 시작합니다",
        )
        for (pause in listOf(100L, 300L, 500L, 800L, 1_200L, 3_200L, 8_000L)) {
            cases.forEach { (prefix, suffix) ->
                val assembler = ProviderTranscriptSemanticAssembler()
                assembler.observeSpeechActivity(true, 0)
                val outputs = mutableListOf<RecognizedUtterance>()
                outputs += assembler.accept(providerFinal(0, prefix, 100))
                assembler.observeContinuousQuiet(200, 200 + pause)
                outputs += assembler.tick((200 + pause).ms)
                assertFalse("$prefix after $pause ms", outputs.any { it.isFinal })
                assembler.observeSpeechActivity(true, (250 + pause).ms)
                outputs += assembler.accept(providerFinal(1, suffix, 300 + pause))
                assembler.observeContinuousQuiet(400 + pause, 1_600 + pause)
                outputs += assembler.tick((1_600 + pause).ms)
                assertEquals(listOf("${prefix.trimEnd('.', ',')} $suffix"),
                    outputs.filter { it.isFinal }.map { it.text })
                assertTrue(assembler.finish((1_700 + pause).ms).isEmpty())
            }
        }
    }

    @Test
    fun `English conditions reasons contrast and negation are joined before translation`() {
        val cases = listOf(
            Triple("If it rains.", "we will wait indoors.", "If it rains we will wait indoors."),
            Triple("Because the door is locked.", "please use the next entrance.",
                "Because the door is locked please use the next entrance."),
            Triple("It is not only beautiful.", "but also practical.",
                "It is not only beautiful but also practical."),
            Triple("You must not.", "enter this room.", "You must not enter this room."),
            Triple("The price is three point.", "five dollars.", "The price is three point five dollars."),
        )
        cases.forEach { (prefix, suffix, expected) ->
            val assembler = ProviderTranscriptSemanticAssembler()
            assembler.observeSpeechActivity(true, 0)
            assembler.accept(providerFinal(0, prefix, 100).copy(sourceLanguageTag = "en-US"))
            assembler.observeContinuousQuiet(200, 8_200)
            assertFalse(prefix, assembler.tick(8_200L.ms).any { it.isFinal })
            assembler.observeSpeechActivity(true, 8_300L.ms)
            assembler.accept(providerFinal(1, suffix, 8_400).copy(sourceLanguageTag = "en-US"))
            assembler.observeContinuousQuiet(8_500, 9_700)
            assertEquals(expected, assembler.tick(9_700L.ms).single { it.isFinal }.text)
            assertTrue(assembler.finish(10_000L.ms).isEmpty())
        }
    }

    @Test
    fun `English complete sentence can be released while the following condition remains pending`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        assembler.observeSpeechActivity(true, 0)
        val outputs = assembler.accept(providerFinal(0, "We have arrived. If it rains.", 100)
            .copy(sourceLanguageTag = "en-US"))
        assertEquals(listOf("We have arrived."), outputs.filter { it.isFinal }.map { it.text })
        assembler.observeContinuousQuiet(200, 8_200)
        assertFalse(assembler.tick(8_200L.ms).any { it.isFinal })
        assembler.observeSpeechActivity(true, 8_300L.ms)
        assembler.accept(providerFinal(1, "we will wait indoors.", 8_400).copy(sourceLanguageTag = "en-US"))
        assembler.observeContinuousQuiet(8_500, 9_700)
        val completed = assembler.tick(9_700L.ms).single { it.isFinal }
        assertEquals("If it rains we will wait indoors.", completed.text)
        assertEquals("We have arrived.", completed.contextBefore)
    }

    @Test
    fun `common complete informal responses still commit naturally without waiting for input stop`() {
        listOf("응", "아니요", "아니야", "괜찮아", "고마워", "기분이 좋아", "집에 갈 거야", "내가 할게")
            .forEach { text ->
                val assembler = ProviderTranscriptSemanticAssembler()
                assembler.observeSpeechActivity(true, 0)
                assembler.accept(providerFinal(0, text, 100))
                assembler.observeContinuousQuiet(200, 1_400)
                assertEquals(text, assembler.tick(1_400L.ms).single { it.isFinal }.text)
            }
    }

    @Test
    fun `completed English object pronouns and inverted questions do not remain stuck`() {
        listOf(
            "Thank you.",
            "I can hear you.",
            "We will wait for you.",
            "We found it.",
            "I spoke with her.",
            "Who are they?",
            "Where were you?",
        ).forEach { text ->
            val assembler = ProviderTranscriptSemanticAssembler()
            assembler.observeSpeechActivity(true, 0)
            val outputs = assembler.accept(providerFinal(0, text, 100)
                .copy(sourceLanguageTag = "en-US"))
            assertFalse(text, outputs.any { it.isFinal })
            assembler.observeContinuousQuiet(200, 1_400)
            assertEquals(text, assembler.tick(1_400L.ms).single { it.isFinal }.text)
            assertTrue(text, assembler.finish(1_500L.ms).isEmpty())
        }
    }

    @Test
    fun `Korean number before a counter is not mistaken for affirmative reply`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        assembler.observeSpeechActivity(true, 0)
        val first = assembler.accept(providerFinal(0, "팁 한 네.", 100))
        assembler.observeContinuousQuiet(200, 8_200)
        assertFalse((first + assembler.tick(8_200L.ms)).any { it.isFinal })
        assembler.observeSpeechActivity(true, 8_300L.ms)
        val second = assembler.accept(providerFinal(1, "가지 정도를 설명해 드릴게요", 8_400))
        assembler.observeContinuousQuiet(8_500, 9_700)
        assertEquals(listOf("팁 한 네 가지 정도를 설명해 드릴게요"),
            (second + assembler.tick(9_700L.ms)).filter { it.isFinal }.map { it.text })
        assertTrue(assembler.finish(9_800L.ms).isEmpty())

        val counterFirst = ProviderTranscriptSemanticAssembler()
        counterFirst.observeSpeechActivity(true, 0)
        val counterOutputs = counterFirst.accept(providerFinal(0, "네 가지 안내를 설명합니다", 100))
        assertFalse(counterOutputs.any { it.isFinal })
        counterFirst.observeContinuousQuiet(200, 1_400)
        assertEquals("네 가지 안내를 설명합니다", counterFirst.tick(1_400L.ms).single { it.isFinal }.text)
    }

    @Test
    fun `standalone affirmative and affirmative followed by complete response remain usable`() {
        listOf("네" to listOf("네"), "예," to listOf("예,"),
            "네, 알겠습니다" to listOf("네,", "알겠습니다"))
            .forEach { (text, expected) ->
                val assembler = ProviderTranscriptSemanticAssembler()
                assembler.observeSpeechActivity(true, 0)
                val outputs = assembler.accept(providerFinal(0, text, 100))
                assembler.observeContinuousQuiet(200, 1_400)
                assertEquals(expected, (outputs + assembler.tick(1_400L.ms))
                    .filter { it.isFinal }.map { it.text })
                assertTrue(assembler.finish(1_500L.ms).isEmpty())
            }
    }

    @Test
    fun `common retrospective and promissive predicates complete without input stop`() {
        listOf("이 방법이 좋더라고요", "해 보니 생각보다 쉽더군요", "다음에 다시 알려 드릴게요")
            .forEach { text ->
                val assembler = ProviderTranscriptSemanticAssembler()
                assembler.observeSpeechActivity(true, 0)
                assembler.accept(providerFinal(0, text, 100))
                assembler.observeContinuousQuiet(200, 1_400)
                assertEquals(text, assembler.tick(1_400L.ms).single { it.isFinal }.text)
                assertTrue(assembler.finish(1_500L.ms).isEmpty())
            }
    }

    @Test
    fun `English subject only and missing auxiliary complements remain pending`() {
        listOf("You.", "They.", "It.", "Did you?", "I know that you.", "You must not.")
            .forEach { text ->
                val assembler = ProviderTranscriptSemanticAssembler()
                assembler.observeSpeechActivity(true, 0)
                val outputs = assembler.accept(providerFinal(0, text, 100)
                    .copy(sourceLanguageTag = "en-US"))
                assembler.observeContinuousQuiet(200, 8_200)
                assertFalse(text, (outputs + assembler.tick(8_200L.ms)).any { it.isFinal })
            }
    }

    @Test
    fun `Korean past tense informal and written predicates complete during live recognition`() {
        listOf(
            "밥 먹었어.",
            "정말 맛있었어.",
            "친구를 만났어.",
            "여기가 내 학교였어.",
            "새로운 방법을 배웠다.",
            "우리는 여기서 만났다.",
        ).forEach { text ->
            val assembler = ProviderTranscriptSemanticAssembler()
            assembler.observeSpeechActivity(true, 0)
            assembler.accept(providerFinal(0, text, 100))
            assembler.observeContinuousQuiet(200, 1_400)
            assertEquals(text, assembler.tick(1_400L.ms).single { it.isFinal }.text)
            assertTrue(text, assembler.finish(1_500L.ms).isEmpty())
        }
    }

    @Test
    fun `one provider callback drains separate confirmed sentences while retaining dependent tail`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        assembler.observeSpeechActivity(true, 0)
        val sentences = listOf(
            "첫 장소에 도착했습니다",
            "안내 사항을 확인합니다",
            "다음 장소로 이동합니다",
        )
        val outputs = assembler.accept(providerFinal(0,
            sentences.joinToString(" ") + " 다음 장소에서는 우리가", 100))
        assertEquals(sentences, outputs.filter { it.isFinal }.map { it.text })
        assertEquals("다음 장소에서는 우리가", outputs.last { !it.isFinal }.text)
        assertEquals(sentences[0], outputs.filter { it.isFinal }[1].contextBefore)
        assertEquals(sentences.take(2).joinToString(" "), outputs.filter { it.isFinal }[2].contextBefore)
        assertEquals(3, outputs.filter { it.isFinal }.map { it.sequence }.toSet().size)

        assembler.observeContinuousQuiet(200, 8_200)
        assertFalse(assembler.tick(8_200L.ms).any { it.isFinal })
        assembler.observeSpeechActivity(true, 8_300L.ms)
        assembler.accept(providerFinal(1, "쉬어갑니다", 8_400))
        assembler.observeContinuousQuiet(8_500, 9_700)
        val tail = assembler.tick(9_700L.ms).single { it.isFinal }
        assertEquals("다음 장소에서는 우리가 쉬어갑니다", tail.text)
        assertFalse(tail.sequence in outputs.filter { it.isFinal }.map { it.sequence })
        assertTrue(assembler.finish(9_800L.ms).isEmpty())
    }

    @Test
    fun `verified silence releases short confirmed sentences separately instead of merging residual`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        assembler.observeSpeechActivity(true, 0)
        assertFalse(assembler.accept(providerFinal(0, "도착했습니다 끝났습니다", 100)).any { it.isFinal })
        assembler.observeContinuousQuiet(200, 2_400)
        assertEquals(listOf("도착했습니다", "끝났습니다"),
            assembler.tick(2_400L.ms).filter { it.isFinal }.map { it.text })
        assertTrue(assembler.finish(2_500L.ms).isEmpty())
    }

    @Test
    fun `sentence draining keeps detached reported speech and decimal value together`() {
        val cases = listOf(
            "ko" to listOf("그는 내가 할게 라고 말했습니다", "우리는 다음 장소로 이동합니다"),
            "en-US" to listOf("The amount is 3.14 dollars.", "We can pay it tomorrow."),
        )
        cases.forEach { (language, sentences) ->
            val assembler = ProviderTranscriptSemanticAssembler()
            assembler.observeSpeechActivity(true, 0)
            val outputs = assembler.accept(providerFinal(0, sentences.joinToString(" "), 100)
                .copy(sourceLanguageTag = language)).toMutableList()
            assembler.observeContinuousQuiet(200, 1_400)
            outputs += assembler.tick(1_400L.ms)
            assertEquals(language, sentences, outputs.filter { it.isFinal }.map { it.text })
            assertTrue(assembler.finish(1_500L.ms).isEmpty())
        }
    }

    @Test
    fun `real repeated instructions remain two units while late provider callbacks cannot repeat them`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        val finals = mutableListOf<RecognizedUtterance>()
        for (sequence in 0L..1L) {
            val start = sequence * 10_000
            assembler.observeSpeechActivity(true, start.ms)
            val event = providerFinal(sequence, "들어가면 안 됩니다", start + 100)
            finals += assembler.accept(event).filter { it.isFinal }
            assembler.observeContinuousQuiet(start + 200, start + 1_400)
            finals += assembler.tick((start + 1_400).ms).filter { it.isFinal }
            assertTrue(assembler.accept(event.copy(recognizedAtElapsedRealtimeNanos = (start + 1_500).ms)).isEmpty())
        }
        assertEquals(listOf("들어가면 안 됩니다", "들어가면 안 됩니다"), finals.map { it.text })
        assertEquals(2, finals.map { it.sequence }.toSet().size)
        assertTrue(assembler.finish(20_000L.ms).isEmpty())
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
    fun `pending provider line limit preserves tail and continues with triggering line`() {
        val recoveries = mutableListOf<TranscriptAssemblyRecovery>()
        val assembler = ProviderTranscriptSemanticAssembler(maximumPendingProviderLines = 2,
            onRecovery = recoveries::add)
        assembler.accept(providerFinal(1, "첫 미완 구간의", 100))
        assembler.accept(providerFinal(2, "다음 미완 구간의", 200))

        val output = assembler.accept(providerFinal(3, "상한 이후 구간", 300)) + assembler.finish(400L.ms)
        assertEquals(listOf("첫 미완 구간의 다음 미완 구간의", "상한 이후 구간"),
            output.filter { it.isFinal }.map { it.text })
        assertEquals(listOf(TranscriptAssemblyRecovery.PROVIDER_LIMIT), recoveries)
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

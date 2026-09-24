package app.guidecast.core.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression and boundary verification tests for conversational Korean sentence latency (Beta).
 */
class ConversationalSentenceLatencyTest {

    @Test
    fun `conversational endings commit after 800ms quiet pause in two identical partials`() {
        val testEndings = listOf(
            "정말 그치",
            "그건 그렇네",
            "이 방식이 정말 좋네",
            "기온이 많이 떨어져",
            "내가 아까 말했잖아",
        )

        testEndings.forEachIndexed { index, sentence ->
            val assembler = ProviderTranscriptSemanticAssembler()
            val baseTime = index * 10_000L

            assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = (baseTime).ms)
            // Two identical partials for LocalAgreement confirmation
            assembler.accept(partial(index.toLong(), sentence, baseTime + 100))
            assembler.accept(partial(index.toLong(), sentence, baseTime + 200))

            assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = (baseTime + 300).ms)
            // 800ms pause check: 300ms + 799ms = 1_099ms -> should not commit yet
            assembler.observeContinuousQuiet(fromMillis = baseTime + 350, throughMillis = baseTime + 1_099)
            assertFalse("Sentence '$sentence' committed before 800ms pause",
                assembler.tick((baseTime + 1_099).ms).any(RecognizedUtterance::isFinal))

            // At 800ms pause (300ms + 800ms = 1_100ms)
            assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = (baseTime + 1_100).ms)
            val committed = assembler.tick((baseTime + 1_100).ms)
            assertEquals("Sentence '$sentence' should commit exactly once at 800ms pause",
                listOf(sentence), committed.filter(RecognizedUtterance::isFinal).map(RecognizedUtterance::text))

            // No duplicate commit on subsequent tick or finish
            assertTrue("No duplicate utterance after commit",
                assembler.tick((baseTime + 1_200).ms).isEmpty())
            assertTrue("Buffer should be empty after commit",
                assembler.finish((baseTime + 1_300).ms).isEmpty())
        }
    }

    @Test
    fun `conversational ending with two token stable continuation commits without pause`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)

        // Continuous speech without pause: complete conversational ending "좋네" + exactly 2-token stable right context ("내가", "볼")
        val fullText = "이 방식이 참 좋네 내가 볼"
        // 2 identical partials to establish stable hypothesis
        assembler.accept(partial(1, fullText, 100))
        val output = assembler.accept(partial(1, fullText, 200))

        // Speech is still active, zero pause
        assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 300L.ms)

        // "이 방식이 참 좋네" should be committed immediately due to 2 stable right-context tokens ("내가", "볼")
        val finals = output.filter(RecognizedUtterance::isFinal)
        assertEquals(listOf("이 방식이 참 좋네"), finals.map(RecognizedUtterance::text))

        // The remaining tail ("내가 볼") must stay uncommitted in pending buffer
        val pendingPreviews = output.filter { !it.isFinal }
        assertEquals("내가 볼", pendingPreviews.last().text)

        // Verify remaining tail flushes on finish
        val tailFinals = assembler.finish(400L.ms).filter(RecognizedUtterance::isFinal)
        assertEquals(listOf("내가 볼"), tailFinals.map(RecognizedUtterance::text))
    }

    @Test
    fun `conversational ji finite form without right context waits across pause to prevent late negative false split`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)

        // "이 사실을 알지" alone: -지 finite form without right context must NOT commit on pause alone
        assembler.accept(partial(1, "이 사실을 알지", 100))
        assembler.accept(partial(1, "이 사실을 알지", 200))
        assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 250L.ms)
        assembler.observeContinuousQuiet(fromMillis = 300, throughMillis = 1_200)

        // Even after 950ms pause, it must wait for potential negative/auxiliary continuation
        assertFalse("'-지' ending without right context must not commit across pause alone",
            assembler.tick(1_200L.ms).any(RecognizedUtterance::isFinal))

        // Late negative continuation arrives: "못했습니다"
        assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 1_300L.ms)
        assembler.accept(providerFinal(1, "이 사실을 알지 못했습니다", 1_400))
        assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 1_500L.ms)
        assembler.observeContinuousQuiet(fromMillis = 1_550, throughMillis = 2_350)

        val committed = assembler.tick(2_350L.ms)
        assertEquals(listOf("이 사실을 알지 못했습니다"),
            committed.filter(RecognizedUtterance::isFinal).map(RecognizedUtterance::text))
    }

    @Test
    fun `postpositional adverbial right context does not trigger premature sentence commit`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)

        // "이 방식이 참 좋네 아주 많이" - 2 right context tokens are postpositional adverbs ("아주", "많이"), NOT a new sentence
        val fullText = "이 방식이 참 좋네 아주 많이"
        assembler.accept(partial(2, fullText, 100))
        val output = assembler.accept(partial(2, fullText, 200))

        // Must NOT split "이 방식이 참 좋네" early when right context is postpositional adverbial phrase
        assertFalse("Postpositional adverbs must not trigger premature commit of preceding sentence",
            output.any(RecognizedUtterance::isFinal))

        assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 300L.ms)
        assembler.observeContinuousQuiet(fromMillis = 350, throughMillis = 1_150)

        val finals = assembler.tick(1_150L.ms).filter(RecognizedUtterance::isFinal)
        assertEquals(listOf(fullText), finals.map(RecognizedUtterance::text))
    }

    @Test
    fun `low volume undetected speech commits complete conversational sentence without VAD activation`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        // VAD never reports speech active (remains false throughout low-volume monologue)
        assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 0)

        val sentence = "이번 결과는 꽤 괜찮아"
        // 2 partial callbacks with identical text. lastHypothesisChangeAtNanos is set at 100ms.
        assembler.accept(partial(10, sentence, 100))
        assembler.accept(partial(10, sentence, 200))

        // Text settled threshold for undetected speech is 800ms from lastHypothesisChange (100ms + 800ms = 900ms)
        assembler.observeContinuousQuiet(fromMillis = 250, throughMillis = 899)
        assertFalse("Should not commit before 800ms text settled threshold (at 899ms)",
            assembler.tick(899L.ms).any(RecognizedUtterance::isFinal))

        assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 900L.ms)
        val committed = assembler.tick(900L.ms)

        assertEquals("Low-volume speech must commit at 800ms quiet fallback without VAD activation",
            listOf(sentence), committed.filter(RecognizedUtterance::isFinal).map(RecognizedUtterance::text))
        assertTrue(assembler.finish(1_000L.ms).isEmpty())
    }

    @Test
    fun `dependent clause with 300ms to 800ms hesitation pause is not prematurely finalized`() {
        val dependentClauses = listOf(
            "날씨가 좋으면",
            "표지판을 확인하고",
            "회의가 끝나면",
            "자리에 도착해서",
        )

        dependentClauses.forEachIndexed { index, clause ->
            // Test hesitation pauses at 300ms, 500ms, 800ms using independent assemblers to avoid re-injecting past timestamps
            for (pauseMillis in listOf(300L, 500L, 800L)) {
                val pauseAssembler = ProviderTranscriptSemanticAssembler()
                pauseAssembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
                pauseAssembler.accept(partial(0, clause, 100))
                pauseAssembler.accept(partial(0, clause, 200))
                pauseAssembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 250L.ms)
                pauseAssembler.observeContinuousQuiet(fromMillis = 300, throughMillis = 250 + pauseMillis)
                assertFalse("Clause '$clause' must not commit during hesitation pause of ${pauseMillis}ms",
                    pauseAssembler.tick((250 + pauseMillis).ms).any(RecognizedUtterance::isFinal))
            }

            // Speaker resumes and completes sentence
            val assembler = ProviderTranscriptSemanticAssembler()
            val baseTime = index * 10_000L
            assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = (baseTime).ms)
            assembler.accept(partial(index.toLong(), clause, baseTime + 100))
            assembler.accept(partial(index.toLong(), clause, baseTime + 200))
            assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = (baseTime + 250).ms)
            assembler.observeContinuousQuiet(fromMillis = baseTime + 300, throughMillis = baseTime + 750)

            val continuation = "다음 장소로 이동합시다"
            val fullSentence = "$clause $continuation"
            assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = (baseTime + 800).ms)
            assembler.accept(partial(index.toLong(), fullSentence, baseTime + 900))
            assembler.accept(partial(index.toLong(), fullSentence, baseTime + 1_000))

            assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = (baseTime + 1_100).ms)
            assembler.observeContinuousQuiet(fromMillis = baseTime + 1_150, throughMillis = baseTime + 1_950)

            val committed = assembler.tick((baseTime + 1_950).ms)
            assertEquals(listOf(fullSentence),
                committed.filter(RecognizedUtterance::isFinal).map(RecognizedUtterance::text))
        }
    }

    @Test
    fun `nouns ending with apparent suffix characters do not cause false boundary`() {
        val nounCases = listOf(
            "새 의자" to "사러 백화점에 갑시다",
            "우리 동네" to "골목길이 참 예쁩니다",
            "아기 돼지" to "삼형제 이야기를 들려줄게요",
            "주식 거래" to "내역을 확인했습니다",
        )

        nounCases.forEachIndexed { index, (nounPhrase, continuation) ->
            val assembler = ProviderTranscriptSemanticAssembler()
            val baseTime = index * 10_000L

            assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = (baseTime).ms)
            // Even if noun phrase is stable and followed by hesitation pause, it must not commit as sentence
            assembler.accept(partial(index.toLong(), nounPhrase, baseTime + 100))
            assembler.accept(partial(index.toLong(), nounPhrase, baseTime + 200))
            assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = (baseTime + 250).ms)
            assembler.observeContinuousQuiet(fromMillis = baseTime + 250, throughMillis = baseTime + 1_500)

            assertFalse("Noun phrase '$nounPhrase' must not falsely commit as complete sentence",
                assembler.tick((baseTime + 1_500).ms).any(RecognizedUtterance::isFinal))

            // Completion arrives
            val fullSentence = "$nounPhrase $continuation"
            assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = (baseTime + 1_600).ms)
            assembler.accept(providerFinal(index.toLong(), fullSentence, baseTime + 1_700))
            assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = (baseTime + 1_800).ms)
            assembler.observeContinuousQuiet(fromMillis = baseTime + 1_850, throughMillis = baseTime + 2_650)

            assertEquals(listOf(fullSentence),
                assembler.tick((baseTime + 2_650).ms).filter(RecognizedUtterance::isFinal).map(RecognizedUtterance::text))
        }
    }

    @Test
    fun `number followed by counter is not confused with affirmative reply`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)

        // "사과 네" followed by hesitation: '네' must not commit as standalone reply
        assembler.accept(partial(1, "사과 네", 100))
        assembler.accept(partial(1, "사과 네", 200))
        assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 250L.ms)
        assembler.observeContinuousQuiet(fromMillis = 300, throughMillis = 1_500)

        assertFalse("'네' in '사과 네' must not be mistaken for standalone affirmative reply",
            assembler.tick(1_500L.ms).any(RecognizedUtterance::isFinal))

        // Counter and continuation arrive
        assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 1_600L.ms)
        assembler.accept(providerFinal(1, "사과 네 개를 샀습니다", 1_700))
        assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 1_800L.ms)
        assembler.observeContinuousQuiet(fromMillis = 1_850, throughMillis = 2_650)

        assertEquals(listOf("사과 네 개를 샀습니다"),
            assembler.tick(2_650L.ms).filter(RecognizedUtterance::isFinal).map(RecognizedUtterance::text))
    }

    @Test
    fun `critical counter-examples prevent premature boundary and false split`() {
        // Counter-example 1: "먹을걸 준비했어" (-을걸 should not split into "먹을걸" | "준비했어")
        run {
            val assembler = ProviderTranscriptSemanticAssembler()
            assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
            assembler.accept(partial(1, "먹을걸 준비했어", 100))
            val output = assembler.accept(partial(1, "먹을걸 준비했어", 200))
            assertFalse("'먹을걸' must not split early before the full sentence settles",
                output.any(RecognizedUtterance::isFinal))

            assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 300L.ms)
            assembler.observeContinuousQuiet(fromMillis = 350, throughMillis = 1_150)
            val finals = assembler.tick(1_150L.ms).filter(RecognizedUtterance::isFinal)
            assertEquals(listOf("먹을걸 준비했어"), finals.map(RecognizedUtterance::text))
        }

        // Counter-example 2: "우리 어린이네 집" (-이네 broad ending removed, noun/plural suffix)
        run {
            val assembler = ProviderTranscriptSemanticAssembler()
            assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
            assembler.accept(partial(2, "우리 어린이네 집", 100))
            assembler.accept(partial(2, "우리 어린이네 집", 200))
            assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 250L.ms)
            assembler.observeContinuousQuiet(fromMillis = 300, throughMillis = 1_200)

            // "우리 어린이네 집" is an incomplete noun phrase without predicate; must not commit "어린이네"
            assertFalse("'어린이네' must not commit as sentence boundary",
                assembler.tick(1_200L.ms).any(RecognizedUtterance::isFinal))

            assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 1_300L.ms)
            assembler.accept(providerFinal(2, "우리 어린이네 집으로 갑니다", 1_400))
            assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 1_500L.ms)
            assembler.observeContinuousQuiet(fromMillis = 1_550, throughMillis = 2_350)
            assertEquals(listOf("우리 어린이네 집으로 갑니다"),
                assembler.tick(2_350L.ms).filter(RecognizedUtterance::isFinal).map(RecognizedUtterance::text))
        }

        // Counter-example 3: "좋지 않아요" ("좋지" is conversational finite, but "않" is dependent right context)
        run {
            val assembler = ProviderTranscriptSemanticAssembler()
            assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
            assembler.accept(partial(3, "좋지 않아요", 100))
            val output = assembler.accept(partial(3, "좋지 않아요", 200))
            assertFalse("'좋지' must not commit separately when followed by dependent '않아요'",
                output.any(RecognizedUtterance::isFinal))

            assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 300L.ms)
            assembler.observeContinuousQuiet(fromMillis = 350, throughMillis = 1_150)
            val finals = assembler.tick(1_150L.ms).filter(RecognizedUtterance::isFinal)
            assertEquals(listOf("좋지 않아요"), finals.map(RecognizedUtterance::text))
        }

        // Counter-example 4: "할게 라고 말했다" ("할게" ending followed by quotation particle "라고")
        run {
            val assembler = ProviderTranscriptSemanticAssembler()
            assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
            assembler.accept(partial(4, "할게 라고 말했다", 100))
            val output = assembler.accept(partial(4, "할게 라고 말했다", 200))
            assertFalse("'할게' must not commit separately when followed by quotation '라고'",
                output.any(RecognizedUtterance::isFinal))

            assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 300L.ms)
            assembler.observeContinuousQuiet(fromMillis = 350, throughMillis = 1_150)
            val finals = assembler.tick(1_150L.ms).filter(RecognizedUtterance::isFinal)
            assertEquals(listOf("할게 라고 말했다"), finals.map(RecognizedUtterance::text))
        }

        // Counter-example 5: "그렇지 않은 경우" ("그렇지" ending followed by "않은")
        run {
            val assembler = ProviderTranscriptSemanticAssembler()
            assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
            assembler.accept(partial(5, "그렇지 않은 경우", 100))
            val output = assembler.accept(partial(5, "그렇지 않은 경우", 200))
            assertFalse("'그렇지' must not split early when followed by '않은'",
                output.any(RecognizedUtterance::isFinal))

            assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 250L.ms)
            assembler.observeContinuousQuiet(fromMillis = 300, throughMillis = 1_200)
            // "경우" is a noun tail, incomplete phrase
            assertFalse("Incomplete condition phrase '그렇지 않은 경우' must not commit early",
                assembler.tick(1_200L.ms).any(RecognizedUtterance::isFinal))

            assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 1_300L.ms)
            assembler.accept(providerFinal(5, "그렇지 않은 경우에는 다시 시도합니다", 1_400))
            assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 1_500L.ms)
            assembler.observeContinuousQuiet(fromMillis = 1_550, throughMillis = 2_350)
            assertEquals(listOf("그렇지 않은 경우에는 다시 시도합니다"),
                assembler.tick(2_350L.ms).filter(RecognizedUtterance::isFinal).map(RecognizedUtterance::text))
        }

        // Counter-example 6: "맞아 죽을 뻔했어" ("맞아" standalone response must not split from "죽을 뻔했어")
        run {
            val assembler = ProviderTranscriptSemanticAssembler()
            assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
            assembler.accept(partial(6, "맞아 죽을 뻔했어", 100))
            val output = assembler.accept(partial(6, "맞아 죽을 뻔했어", 200))
            assertFalse("'맞아' must not split early from '죽을 뻔했어'",
                output.any(RecognizedUtterance::isFinal))

            assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 300L.ms)
            assembler.observeContinuousQuiet(fromMillis = 350, throughMillis = 1_150)
            val finals = assembler.tick(1_150L.ms).filter(RecognizedUtterance::isFinal)
            assertEquals(listOf("맞아 죽을 뻔했어"), finals.map(RecognizedUtterance::text))
        }
    }

    @Test
    fun `unclosed speech quotes suppress conversational boundary until quote closes`() {
        // Double ASCII quote: "이번 방법이 좋아
        run {
            val assembler = ProviderTranscriptSemanticAssembler()
            assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)

            val openQuote = "\"이번 방법이 좋아"
            assembler.accept(partial(6, openQuote, 100))
            val outputOpen = assembler.accept(partial(6, openQuote, 200))
            assertFalse("Interior of open double quote must not commit as independent unit",
                outputOpen.any(RecognizedUtterance::isFinal))

            assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 250L.ms)
            assembler.observeContinuousQuiet(fromMillis = 300, throughMillis = 1_200)
            assertFalse("Unclosed double quote must not finalize across pause",
                assembler.tick(1_200L.ms).any(RecognizedUtterance::isFinal))

            val completedQuote = "\"이번 방법이 좋아\" 라고 평가했습니다"
            assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 1_300L.ms)
            assembler.accept(providerFinal(6, completedQuote, 1_400))
            assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 1_500L.ms)
            assembler.observeContinuousQuiet(fromMillis = 1_550, throughMillis = 2_350)

            val committed = assembler.tick(2_350L.ms)
            assertEquals(listOf(completedQuote),
                committed.filter(RecognizedUtterance::isFinal).map(RecognizedUtterance::text))
        }

        // Single ASCII quote: '이번 방법이 좋아
        run {
            val assembler = ProviderTranscriptSemanticAssembler()
            assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)

            val openSingleQuote = "'이번 방법이 좋아"
            assembler.accept(partial(7, openSingleQuote, 100))
            val outputOpen = assembler.accept(partial(7, openSingleQuote, 200))
            assertFalse("Interior of open single quote must not commit as independent unit",
                outputOpen.any(RecognizedUtterance::isFinal))

            assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 250L.ms)
            assembler.observeContinuousQuiet(fromMillis = 300, throughMillis = 1_200)
            assertFalse("Unclosed single quote must not finalize across pause",
                assembler.tick(1_200L.ms).any(RecognizedUtterance::isFinal))

            val completedSingleQuote = "'이번 방법이 좋아' 라고 평가했습니다"
            assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 1_300L.ms)
            assembler.accept(providerFinal(7, completedSingleQuote, 1_400))
            assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 1_500L.ms)
            assembler.observeContinuousQuiet(fromMillis = 1_550, throughMillis = 2_350)

            val committed = assembler.tick(2_350L.ms)
            assertEquals(listOf(completedSingleQuote),
                committed.filter(RecognizedUtterance::isFinal).map(RecognizedUtterance::text))
        }
    }

    @Test
    fun `revised partial replaces previous draft without missing words or duplicate finals`() {
        val assembler = ProviderTranscriptSemanticAssembler()
        assembler.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)

        val outputs = mutableListOf<RecognizedUtterance>()
        // Draft 1
        outputs += assembler.accept(partial(7, "그렇지 아마도", 100))
        // Draft 2 revises the tail before agreement
        outputs += assembler.accept(partial(7, "그렇지 확실히 맞아", 200))
        // Draft 3 confirms the revision. "그렇지" is committed on this callback due to 2 stable right-context tokens ("확실히", "맞아")
        outputs += assembler.accept(partial(7, "그렇지 확실히 맞아", 300))

        assembler.observeSpeechActivity(isSpeech = false, capturedAtNanos = 350L.ms)
        assembler.observeContinuousQuiet(fromMillis = 400, throughMillis = 1_200)

        // Remaining tail ("확실히 맞아") commits after 800ms quiet pause
        outputs += assembler.tick(1_200L.ms)

        val finals = outputs.filter(RecognizedUtterance::isFinal)
        // Should commit both sentences in order, without the stale draft "아마도"
        assertEquals(listOf("그렇지", "확실히 맞아"),
            finals.map(RecognizedUtterance::text))
        // Ensure exactly two distinct sequences emitted (no duplicates)
        assertEquals(2, finals.map { it.sequence }.toSet().size)
        assertTrue(assembler.finish(1_300L.ms).isEmpty())
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

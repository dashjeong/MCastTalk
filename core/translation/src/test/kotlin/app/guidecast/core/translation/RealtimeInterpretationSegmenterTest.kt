package app.guidecast.core.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeInterpretationSegmenterTest {
    @Test
    fun `continuous speech commits stable prefix before safety limit and retains right context`() {
        val segmenter = RealtimeInterpretationSegmenter()
        val outputs = mutableListOf<RecognizedUtterance>()
        outputs += segmenter.accept(partial(0, "오늘 경복궁에서 중요한 문화재 안내를 계속 시작하겠습니다", 0))
        outputs += segmenter.accept(partial(0, "오늘 경복궁에서 중요한 문화재 안내를 계속 시작하겠습니다 여러분", 4_300))

        val committed = outputs.single { it.isFinal }
        assertEquals("오늘 경복궁에서 중요한 문화재 안내를 계속 시작하겠습니다", committed.text)
        assertTrue(committed.recognizedAtElapsedRealtimeNanos < 7_000.ms)
        assertEquals("여러분", outputs.last { !it.isFinal }.text)
    }

    @Test
    fun `natural Korean sentence ending commits without fixed duration cut`() {
        val segmenter = RealtimeInterpretationSegmenter()
        segmenter.accept(partial(0, "이제 경복궁 관람을 시작합니다", 0))
        val output = segmenter.accept(partial(0, "이제 경복궁 관람을 시작합니다 다음", 600))

        assertEquals("이제 경복궁 관람을 시작합니다", output.single { it.isFinal }.text)
        assertEquals("다음", output.single { !it.isFinal }.text)
    }

    @Test
    fun `unstable revised tail is not translated twice`() {
        val segmenter = RealtimeInterpretationSegmenter()
        val finalTexts = mutableListOf<String>()
        listOf(
            partial(3, "지금 오른쪽 문으로 천천히 이동해 주시기", 0),
            partial(3, "지금 오른쪽 문으로 천천히 이동해 주시기 바랍니다", 4_300),
            partial(3, "지금 오른쪽 문으로 천천히 이동해 주시기 바라고", 4_700),
            final(3, "지금 오른쪽 문으로 천천히 이동해 주시기 바랍니다", 5_000),
        ).forEach { event ->
            finalTexts += segmenter.accept(event).filter { it.isFinal }.map { it.text }
        }

        assertEquals(listOf("지금 오른쪽 문으로 천천히", "이동해 주시기 바랍니다"), finalTexts)
        assertEquals(finalTexts.size, finalTexts.distinct().size)
    }

    @Test
    fun `provider final before a pause produces one complete translation unit`() {
        val segmenter = RealtimeInterpretationSegmenter()
        segmenter.accept(partial(9, "안녕하세요 반갑습니다", 100))
        val output = segmenter.accept(final(9, "안녕하세요 반갑습니다", 700))

        assertEquals(1, output.size)
        assertTrue(output.single().isFinal)
        assertEquals("안녕하세요 반갑습니다", output.single().text)
        assertEquals(0L, output.single().sequence)
    }

    @Test
    fun `late native final does not repeat a prefix already committed from partials`() {
        val segmenter = RealtimeInterpretationSegmenter()
        val outputs = mutableListOf<RecognizedUtterance>()
        outputs += segmenter.accept(partial(1, "첫 번째 안내 문장을 설명합니다 다음 장소로", 0))
        outputs += segmenter.accept(partial(1, "첫 번째 안내 문장을 설명합니다 다음 장소로 이동합니다", 500))
        outputs += segmenter.accept(final(1, "첫 번째 안내 문장을 설명합니다 다음 장소로 이동합니다", 1_000))

        val finals = outputs.filter(RecognizedUtterance::isFinal)
        assertEquals(listOf("첫 번째 안내 문장을 설명합니다", "다음 장소로 이동합니다"), finals.map { it.text })
        assertEquals("첫 번째 안내 문장을 설명합니다", finals.last().contextBefore)
        assertFalse(finals.zipWithNext().any { (left, right) -> left.text == right.text })
        assertEquals(listOf(0L, 1L), finals.map { it.sequence })
    }

    @Test
    fun `independent tick commits a usable prefix when recognizer callbacks stall`() {
        val segmenter = RealtimeInterpretationSegmenter()
        segmenter.accept(
            partial(1, "계속 이어지는 안내에서 경복궁의 중요한 역사를 설명하고 있는 동안", 100),
        )
        val preview = segmenter.accept(
            partial(1, "계속 이어지는 안내에서 경복궁의 중요한 역사를 설명하고 있는 동안 여러분", 200),
        )

        val output = segmenter.tick(4_800.ms)

        assertTrue(preview.isNotEmpty())
        assertEquals(
            "계속 이어지는 안내에서 경복궁의 중요한 역사를 설명하고",
            output.single { it.isFinal }.text,
        )
        assertEquals("있는 동안 여러분", output.single { !it.isFinal }.text)
        assertTrue(output.single { it.isFinal }.recognizedAtElapsedRealtimeNanos < 5_000.ms)
    }

    @Test
    fun `single unconfirmed hypothesis requests one recognizer endpoint before seven seconds`() {
        val segmenter = RealtimeInterpretationSegmenter()
        segmenter.accept(
            partial(11, "아직 인식기가 한 번만 보낸 수정 가능한 발화입니다", 100),
        )

        val output = segmenter.tick(4_700.ms)

        assertFalse(output.any(RecognizedUtterance::isFinal))
        assertFalse(segmenter.shouldRequestRecognizerEndpoint(4_700.ms))
        assertTrue(segmenter.shouldRequestRecognizerEndpoint(4_800.ms))
        assertFalse(segmenter.shouldRequestRecognizerEndpoint(4_900.ms))

        val flushed = segmenter.finish(4_900.ms)
        assertEquals("아직 인식기가 한 번만 보낸 수정 가능한 발화입니다", flushed.single().text)
        assertTrue(segmenter.finish(5_000.ms).isEmpty())
    }

    @Test
    fun `seven second tick never cuts a lone revisable hypothesis into a final`() {
        val segmenter = RealtimeInterpretationSegmenter()
        segmenter.accept(
            partial(13, "계속 말하는 중이라 아직 한 번만 관측된 수정 가능한 구간", 0),
        )

        val atSevenSeconds = segmenter.tick(7_000.ms)

        assertFalse(atSevenSeconds.any(RecognizedUtterance::isFinal))
        assertTrue(segmenter.shouldRequestRecognizerEndpoint(7_000.ms))
        assertFalse(segmenter.tick(14_000.ms).any(RecognizedUtterance::isFinal))
    }

    @Test
    fun `energy pause alone never commits text without local agreement`() {
        val segmenter = RealtimeInterpretationSegmenter()
        segmenter.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
        segmenter.accept(partial(12, "바람 소리 속에서 한 번만 잡힌 불안정한 가설", 100))
        segmenter.observeSpeechActivity(isSpeech = false, capturedAtNanos = 200.ms)

        val output = segmenter.tick(1_500.ms)

        assertFalse(output.any(RecognizedUtterance::isFinal))
    }

    @Test
    fun `three seconds of verified silence finalizes one usable English partial`() {
        val segmenter = RealtimeInterpretationSegmenter(sentenceCompletionInterpretationPolicy())
        segmenter.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
        val hypothesis = RecognizedUtterance(
            sequence = 14,
            text = "Daddy looks at this.",
            sourceLanguageTag = "en-US",
            isFinal = false,
            capturedAtElapsedRealtimeNanos = 0,
            recognizedAtElapsedRealtimeNanos = 100L.ms,
        )
        segmenter.accept(hypothesis)
        segmenter.observeSpeechActivity(isSpeech = true, capturedAtNanos = 200L.ms)
        segmenter.observeSpeechActivity(isSpeech = false, capturedAtNanos = 300L.ms)

        assertFalse(segmenter.tick(3_199L.ms).any(RecognizedUtterance::isFinal))
        segmenter.observeContinuousQuiet(fromMillis = 3_200, throughMillis = 6_200)
        assertEquals(
            "Daddy looks at this.",
            segmenter.tick(6_200L.ms).single(RecognizedUtterance::isFinal).text,
        )
    }

    @Test
    fun `continuous explicit quiet finalizes text at five seconds when VAD never detects voice`() {
        val segmenter = RealtimeInterpretationSegmenter(sentenceCompletionInterpretationPolicy())
        segmenter.observeSpeechActivity(isSpeech = false, capturedAtNanos = 0)
        segmenter.accept(
            partial(17, "Quiet speech still produced this text", 100)
                .copy(sourceLanguageTag = "en-US"),
        )
        (250L..5_000L step 250L).forEach { millis ->
            segmenter.observeSpeechActivity(isSpeech = false, capturedAtNanos = millis.ms)
        }

        assertFalse(segmenter.tick(5_099L.ms).any(RecognizedUtterance::isFinal))
        segmenter.observeSpeechActivity(isSpeech = false, capturedAtNanos = 5_100L.ms)
        assertEquals(
            "Quiet speech still produced this text",
            segmenter.tick(5_100L.ms).single(RecognizedUtterance::isFinal).text,
        )
    }

    @Test
    fun `no acoustic observations never imply silence`() {
        val segmenter = RealtimeInterpretationSegmenter(sentenceCompletionInterpretationPolicy())
        segmenter.accept(
            partial(18, "Text without PCM state remains pending", 100)
                .copy(sourceLanguageTag = "en-US"),
        )

        assertFalse(segmenter.tick(30_000L.ms).any(RecognizedUtterance::isFinal))
    }

    @Test
    fun `elapsed time never finalizes a lone partial while speech remains active`() {
        val segmenter = RealtimeInterpretationSegmenter(sentenceCompletionInterpretationPolicy())
        segmenter.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
        segmenter.accept(partial(15, "계속 말하는 중이라 이어지는 문장입니다", 100))
        segmenter.observeSpeechActivity(isSpeech = true, capturedAtNanos = 7_000L.ms)

        assertFalse(segmenter.tick(20_000L.ms).any(RecognizedUtterance::isFinal))
    }

    @Test
    fun `old acoustic silence waits for a newly changed partial to settle`() {
        val segmenter = RealtimeInterpretationSegmenter(sentenceCompletionInterpretationPolicy())
        segmenter.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
        segmenter.observeSpeechActivity(isSpeech = false, capturedAtNanos = 100L.ms)
        segmenter.observeContinuousQuiet(fromMillis = 350, throughMillis = 3_850)
        segmenter.accept(
            partial(16, "that is", 4_000).copy(sourceLanguageTag = "en-US"),
        )
        segmenter.observeSpeechActivity(isSpeech = false, capturedAtNanos = 4_100L.ms)

        val changed = segmenter.accept(
            partial(16, "that is the next point", 4_400).copy(sourceLanguageTag = "en-US"),
        )

        assertFalse(changed.any(RecognizedUtterance::isFinal))
        segmenter.observeSpeechActivity(isSpeech = false, capturedAtNanos = 4_600L.ms)
        assertFalse(segmenter.tick(4_899L.ms).any(RecognizedUtterance::isFinal))
        segmenter.observeSpeechActivity(isSpeech = false, capturedAtNanos = 4_900L.ms)
        assertEquals(
            "that is the next point",
            segmenter.tick(4_900L.ms).single(RecognizedUtterance::isFinal).text,
        )
    }

    @Test
    fun `natural acoustic pause commits a stable short phrase`() {
        val segmenter = RealtimeInterpretationSegmenter()
        segmenter.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
        segmenter.accept(partial(2, "왼쪽을 보세요", 100))
        segmenter.accept(partial(2, "왼쪽을 보세요", 200))
        segmenter.observeSpeechActivity(isSpeech = false, capturedAtNanos = 250.ms)
        segmenter.observeContinuousQuiet(fromMillis = 500, throughMillis = 1_100)

        val output = segmenter.tick(1_100.ms)

        assertEquals("왼쪽을 보세요", output.single().text)
        assertTrue(output.single().isFinal)
    }

    @Test
    fun `pause ladder distinguishes hesitation from a stable phrase boundary`() {
        val expectedCommitByQuietMillis = linkedMapOf(
            100L to false,
            300L to false,
            500L to true,
            800L to true,
            1_200L to true,
        )

        expectedCommitByQuietMillis.forEach { (quietMillis, expectedCommit) ->
            val segmenter = RealtimeInterpretationSegmenter()
            segmenter.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
            segmenter.accept(partial(21, "경복궁 왼쪽 회랑", 100))
            segmenter.accept(partial(21, "경복궁 왼쪽 회랑", 200))
            segmenter.observeSpeechActivity(isSpeech = true, capturedAtNanos = 200.ms)
            segmenter.observeSpeechActivity(isSpeech = false, capturedAtNanos = 200.ms)
            val decisionMillis = 200L + quietMillis
            segmenter.observeContinuousQuiet(
                fromMillis = minOf(450L, decisionMillis),
                throughMillis = decisionMillis,
            )

            val output = segmenter.tick(decisionMillis.ms)

            assertEquals(
                "quiet=${quietMillis}ms",
                expectedCommit,
                output.any(RecognizedUtterance::isFinal),
            )
        }
    }

    @Test
    fun `two token phrase waits for sentence pause instead of short hesitation`() {
        fun finalAfter(quietMillis: Long): Boolean {
            val segmenter = RealtimeInterpretationSegmenter()
            segmenter.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
            segmenter.accept(partial(22, "오른쪽 회랑", 100))
            segmenter.accept(partial(22, "오른쪽 회랑", 200))
            segmenter.observeSpeechActivity(isSpeech = true, capturedAtNanos = 200.ms)
            segmenter.observeSpeechActivity(isSpeech = false, capturedAtNanos = 200.ms)
            val decisionMillis = 200L + quietMillis
            segmenter.observeContinuousQuiet(
                fromMillis = minOf(450L, decisionMillis),
                throughMillis = decisionMillis,
            )
            return segmenter.tick(decisionMillis.ms)
                .any(RecognizedUtterance::isFinal)
        }

        assertFalse(finalAfter(800))
        assertTrue(finalAfter(1_200))
    }

    @Test
    fun `insertion before spoken boundary does not replay committed words`() {
        val segmenter = RealtimeInterpretationSegmenter()
        segmenter.accept(partial(3, "지금 오른쪽 문으로 이동합니다 다음", 0))
        segmenter.accept(partial(3, "지금 오른쪽 문으로 이동합니다 다음 장소", 200))

        val output = segmenter.accept(
            final(3, "지금 바로 오른쪽 문으로 이동합니다 다음 장소입니다", 600),
        )

        assertEquals("다음 장소입니다", output.single { it.isFinal }.text)
        assertFalse(output.filter { it.isFinal }.any { it.text.contains("이동합니다 다음") })
    }

    @Test
    fun `deletion before spoken boundary does not skip new words`() {
        val segmenter = RealtimeInterpretationSegmenter()
        segmenter.accept(partial(4, "지금 바로 오른쪽 문으로 이동합니다 다음", 0))
        segmenter.accept(partial(4, "지금 바로 오른쪽 문으로 이동합니다 다음 장소", 200))

        val output = segmenter.accept(
            final(4, "지금 오른쪽 문으로 이동합니다 다음 장소입니다", 600),
        )

        assertEquals("다음 장소입니다", output.single { it.isFinal }.text)
    }

    @Test
    fun `large insertion and deletion before boundary do not replay or skip residual`() {
        fun committedSegmenter() = RealtimeInterpretationSegmenter().also { segmenter ->
            segmenter.accept(partial(30, "지금 오른쪽 문으로 천천히 이동해 주시기 바랍니다 다음", 0))
            segmenter.accept(partial(30, "지금 오른쪽 문으로 천천히 이동해 주시기 바랍니다 다음 장소", 200))
        }

        val inserted = committedSegmenter().accept(
            final(
                30,
                "안내를 위해 지금부터 모두 함께 천천히 지금 오른쪽 문으로 천천히 이동해 주시기 바랍니다 다음 장소입니다",
                600,
            ),
        )
        val deleted = committedSegmenter().accept(
            final(30, "이동해 주시기 바랍니다 다음 장소입니다", 600),
        )

        assertEquals("다음 장소입니다", inserted.single { it.isFinal }.text)
        assertEquals("다음 장소입니다", deleted.single { it.isFinal }.text)
    }

    @Test
    fun `insertion and deletion keep the first seen time of the aligned residual token`() {
        val segmenter = RealtimeInterpretationSegmenter()
        segmenter.accept(partial(31, "지금 오른쪽 문으로 이동합니다 다음", 0))
        segmenter.accept(partial(31, "지금 오른쪽 문으로 이동합니다 다음 장소", 200))

        val revised = segmenter.accept(
            partial(31, "지금 바로 오른쪽 문으로 이동합니다 장소 추가", 600),
        )

        val preview = revised.single { !it.isFinal && !it.isRetracted }
        assertEquals("장소 추가", preview.text)
        assertEquals(200.ms, preview.capturedAtElapsedRealtimeNanos)
        assertEquals(7_200.ms, preview.firstAudioDeadlineElapsedRealtimeNanos)
    }

    @Test
    fun `new insertion keeps its own first seen time after older tail is deleted`() {
        val segmenter = RealtimeInterpretationSegmenter()
        segmenter.accept(partial(32, "지금 오른쪽 문으로 이동합니다 다음", 0))
        segmenter.accept(partial(32, "지금 오른쪽 문으로 이동합니다 다음 장소", 200))
        segmenter.accept(
            partial(32, "지금 오른쪽 문으로 이동합니다 그리고 다음 장소", 600),
        )

        val revised = segmenter.accept(
            partial(32, "지금 오른쪽 문으로 이동합니다 그리고", 800),
        )

        val preview = revised.single { !it.isFinal && !it.isRetracted }
        assertEquals("그리고", preview.text)
        assertEquals(600.ms, preview.capturedAtElapsedRealtimeNanos)
        assertEquals(7_600.ms, preview.firstAudioDeadlineElapsedRealtimeNanos)
    }

    @Test
    fun `tail keeps its original age after an earlier prefix commits`() {
        val segmenter = RealtimeInterpretationSegmenter()
        segmenter.accept(partial(40, "오늘 경복궁 주요 건물 배치를 차례대로 함께 살펴보는 동안", 0))
        val firstCommit = segmenter.accept(
            partial(40, "오늘 경복궁 주요 건물 배치를 차례대로 함께 살펴보는 동안 다음 장소로 천천히 이동할 준비를", 3_700),
        )
        assertTrue(firstCommit.any { it.isFinal })

        val nextCommit = segmenter.accept(
            partial(40, "오늘 경복궁 주요 건물 배치를 차례대로 함께 살펴보는 동안 다음 장소로 천천히 이동할 준비를 하며", 4_000),
        )

        assertTrue(nextCommit.any { it.isFinal })
        assertTrue(nextCommit.first { it.isFinal }.capturedAtElapsedRealtimeNanos == 0L)
    }

    @Test
    fun `twelve second continuation advances token deadlines without a fixed audio cut`() {
        val segmenter = RealtimeInterpretationSegmenter()
        val outputs = mutableListOf<RecognizedUtterance>()
        outputs += segmenter.accept(
            partial(50, "오늘 경복궁 주요 건물 배치를 차례대로 함께 살펴보는 동안", 0),
        )
        outputs += segmenter.accept(
            partial(
                50,
                "오늘 경복궁 주요 건물 배치를 차례대로 함께 살펴보는 동안 " +
                    "다음 장소로 천천히 이동할 준비를",
                3_700,
            ),
        )
        outputs += segmenter.accept(
            partial(
                50,
                "오늘 경복궁 주요 건물 배치를 차례대로 함께 살펴보는 동안 " +
                    "다음 장소로 천천히 이동할 준비를 하며 계속 설명을 이어가고 있습니다",
                4_000,
            ),
        )
        outputs += segmenter.accept(
            partial(
                50,
                "오늘 경복궁 주요 건물 배치를 차례대로 함께 살펴보는 동안 " +
                    "다음 장소로 천천히 이동할 준비를 하며 계속 설명을 이어가고 있습니다 " +
                    "이제 관람객 여러분은",
                8_000,
            ),
        )
        outputs += segmenter.accept(
            partial(
                50,
                "오늘 경복궁 주요 건물 배치를 차례대로 함께 살펴보는 동안 " +
                    "다음 장소로 천천히 이동할 준비를 하며 계속 설명을 이어가고 있습니다 " +
                    "이제 관람객 여러분은 안내 표지판을 확인해 주세요",
                12_000,
            ),
        )

        val eightSecondCommit = outputs.single {
            it.isFinal && it.recognizedAtElapsedRealtimeNanos == 8_000.ms
        }
        assertEquals(3_700.ms, eightSecondCommit.capturedAtElapsedRealtimeNanos)
        assertEquals(10_700.ms, eightSecondCommit.firstAudioDeadlineElapsedRealtimeNanos)
        val twelveSecondPreview = outputs.last { !it.isFinal && !it.isRetracted }
        assertEquals(8_000.ms, twelveSecondPreview.capturedAtElapsedRealtimeNanos)
        assertEquals(15_000.ms, twelveSecondPreview.firstAudioDeadlineElapsedRealtimeNanos)
        assertTrue(
            outputs.filterNot(RecognizedUtterance::isRetracted).all { event ->
                requireNotNull(event.firstAudioDeadlineElapsedRealtimeNanos) >
                    event.recognizedAtElapsedRealtimeNanos
            },
        )
        assertFalse(segmenter.shouldRequestRecognizerEndpoint(12_700.ms))
        assertTrue(segmenter.shouldRequestRecognizerEndpoint(12_800.ms))
        outputs.filter(RecognizedUtterance::isFinal).zipWithNext().forEach { (previous, next) ->
            assertTrue(requireNotNull(next.contextBefore).endsWith(previous.text))
        }
    }

    @Test
    fun `removed unstable tail retracts the existing transcript preview`() {
        val segmenter = RealtimeInterpretationSegmenter()
        val outputs = mutableListOf<RecognizedUtterance>()
        outputs += segmenter.accept(partial(5, "첫 안내를 시작합니다 잘못된", 0))
        outputs += segmenter.accept(partial(5, "첫 안내를 시작합니다 잘못된 꼬리", 200))
        val tailPreview = outputs.last { !it.isFinal }

        val retraction = segmenter.accept(partial(5, "첫 안내를 시작합니다", 400)).single()

        assertTrue(retraction.isRetracted)
        assertEquals(tailPreview.sequence, retraction.sequence)
        assertEquals("", retraction.text)
    }

    @Test
    fun `normal recognizer completion flushes pending text exactly once`() {
        val segmenter = RealtimeInterpretationSegmenter()
        segmenter.accept(partial(6, "짧은 안내 문구", 100))

        val firstFinish = segmenter.finish(500.ms)
        val secondFinish = segmenter.finish(700.ms)

        assertEquals("짧은 안내 문구", firstFinish.single().text)
        assertTrue(secondFinish.isEmpty())
    }

    @Test
    fun `sentence completion policy requests one endpoint only at twelve seconds and carries context`() {
        val segmenter = RealtimeInterpretationSegmenter(sentenceCompletionInterpretationPolicy())
        segmenter.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
        segmenter.accept(partial(70, "오늘 저희가 걸어갈 길은 오래된 철길의 주변 풍경과", 100))
        segmenter.accept(
            partial(
                70,
                "오늘 저희가 걸어갈 길은 오래된 철길의 주변 풍경과 역사 이야기를",
                4_900,
            ),
        )

        val beforeTwelveSecondCeiling = segmenter.tick(11_999.ms)

        assertFalse(beforeTwelveSecondCeiling.any(RecognizedUtterance::isFinal))
        assertFalse(segmenter.shouldRequestRecognizerEndpoint(11_999.ms))
        assertTrue(segmenter.shouldRequestRecognizerEndpoint(12_000.ms))
        assertFalse(segmenter.shouldRequestRecognizerEndpoint(12_001.ms))
        assertFalse(segmenter.shouldRequestRecognizerEndpoint(20_000.ms))

        val endpointFlush = segmenter.finish(12_000.ms).single()
        assertEquals(
            "오늘 저희가 걸어갈 길은 오래된 철길의 주변 풍경과 역사 이야기를",
            endpointFlush.text,
        )
        val nextSession = segmenter.accept(
            final(71, "다음 장소에서 평화의 의미를 설명합니다", 12_500),
        ).single()
        assertEquals(endpointFlush.text, nextSession.contextBefore)
    }

    @Test
    fun `unpunctuated continuous speech never commits a connective on elapsed time alone`() {
        val segmenter = RealtimeInterpretationSegmenter(sentenceCompletionInterpretationPolicy())
        segmenter.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
        segmenter.accept(
            partial(
                75,
                "오늘 저희는 오래된 철길을 따라 계속 이동하고 오른쪽 안내판을 확인합니다",
                100,
            ),
        )
        val output = segmenter.accept(
            partial(
                75,
                "오늘 저희는 오래된 철길을 따라 계속 이동하고 오른쪽 안내판을 확인한 뒤 출발합니다",
                4_800,
            ),
        )

        assertFalse(output.any(RecognizedUtterance::isFinal))
        assertEquals(
            "오늘 저희는 오래된 철길을 따라 계속 이동하고 오른쪽 안내판을 확인한 뒤 출발합니다",
            output.single { !it.isFinal }.text,
        )
    }

    @Test
    fun `revised connective tail remains one preview until a complete meaning boundary`() {
        val segmenter = RealtimeInterpretationSegmenter(sentenceCompletionInterpretationPolicy())
        segmenter.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
        segmenter.accept(
            partial(
                76,
                "평화공원을 지나 계속 이동하고 오른쪽 안내판을 먼저 확인합니다",
                100,
            ),
        )
        val decision = segmenter.accept(
            partial(
                76,
                "평화공원을 지나 계속 이동하고 오른쪽 안내판을 잠시 확인합니다",
                4_800,
            ),
        )

        assertFalse(decision.any(RecognizedUtterance::isFinal))
        assertEquals(
            "평화공원을 지나 계속 이동하고 오른쪽 안내판을 잠시 확인합니다",
            decision.single { !it.isFinal }.text,
        )
        val finalTail = segmenter.accept(
            final(
                76,
                "평화공원을 지나 계속 이동하고 오른쪽 안내판을 잠시 확인해 주세요",
                5_200,
            ),
        ).single { it.isFinal }

        assertEquals("평화공원을 지나 계속 이동하고 오른쪽 안내판을 잠시 확인해 주세요", finalTail.text)
        assertFalse(finalTail.text.contains("먼저"))
        assertEquals(null, finalTail.contextBefore)
    }

    @Test
    fun `sentence completion policy waits for pause after a grammatical ending`() {
        val segmenter = RealtimeInterpretationSegmenter(sentenceCompletionInterpretationPolicy())
        segmenter.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
        segmenter.accept(partial(71, "이 길은 평화를 상징합니다", 100))
        val whileSpeaking = segmenter.accept(
            partial(71, "이 길은 평화를 상징합니다", 300),
        )
        segmenter.observeSpeechActivity(isSpeech = true, capturedAtNanos = 400.ms)
        segmenter.observeSpeechActivity(isSpeech = false, capturedAtNanos = 400.ms)
        segmenter.observeContinuousQuiet(fromMillis = 650, throughMillis = 1_400)

        assertFalse(whileSpeaking.any(RecognizedUtterance::isFinal))
        assertFalse(segmenter.tick(1_500.ms).any(RecognizedUtterance::isFinal))
        segmenter.observeSpeechActivity(isSpeech = false, capturedAtNanos = 1_600.ms)
        assertEquals(
            "이 길은 평화를 상징합니다",
            segmenter.tick(1_600.ms).single { it.isFinal }.text,
        )
    }

    @Test
    fun `sentence completion policy commits a complete sentence during continuous speech`() {
        val segmenter = RealtimeInterpretationSegmenter(sentenceCompletionInterpretationPolicy())
        segmenter.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
        segmenter.accept(
            partial(74, "이 길은 평화를 상징합니다 그리고 다음 장소로 천천히", 100),
        )
        val output = segmenter.accept(
            partial(74, "이 길은 평화를 상징합니다 그리고 다음 장소로 천천히 이동합니다", 500),
        )

        assertEquals("이 길은 평화를 상징합니다", output.single { it.isFinal }.text)
        assertEquals(
            "그리고 다음 장소로 천천히 이동합니다",
            output.single { !it.isFinal }.text,
        )
    }

    @Test
    fun `sentence completion policy uses comma pause but protects an unfinished hesitation`() {
        val commaSegmenter = RealtimeInterpretationSegmenter(
            sentenceCompletionInterpretationPolicy(),
        )
        commaSegmenter.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
        commaSegmenter.accept(partial(72, "왼쪽에는 철책이 있습니다,", 100))
        commaSegmenter.accept(partial(72, "왼쪽에는 철책이 있습니다,", 300))
        commaSegmenter.observeSpeechActivity(isSpeech = true, capturedAtNanos = 400.ms)
        commaSegmenter.observeSpeechActivity(isSpeech = false, capturedAtNanos = 400.ms)
        commaSegmenter.observeContinuousQuiet(fromMillis = 650, throughMillis = 1_400)

        assertFalse(commaSegmenter.tick(1_500.ms).any(RecognizedUtterance::isFinal))
        commaSegmenter.observeSpeechActivity(isSpeech = false, capturedAtNanos = 1_600.ms)
        assertEquals(
            "왼쪽에는 철책이 있습니다,",
            commaSegmenter.tick(1_600.ms).single { it.isFinal }.text,
        )

        val hesitation = RealtimeInterpretationSegmenter(sentenceCompletionInterpretationPolicy())
        hesitation.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
        hesitation.accept(partial(73, "그리고 다음 장소에서는 우리가", 100))
        hesitation.accept(partial(73, "그리고 다음 장소에서는 우리가", 300))
        hesitation.observeSpeechActivity(isSpeech = true, capturedAtNanos = 400.ms)
        hesitation.observeSpeechActivity(isSpeech = false, capturedAtNanos = 400.ms)

        assertFalse(hesitation.tick(2_100.ms).any(RecognizedUtterance::isFinal))
        assertFalse(hesitation.tick(2_200.ms).any(RecognizedUtterance::isFinal))
    }

    @Test
    fun `latin abbreviations and decimals are not sentence punctuation boundaries`() {
        listOf("Dr.", "e.g.", "No.", "0.5%.").forEachIndexed { index, token ->
            val segmenter = RealtimeInterpretationSegmenter(sentenceCompletionInterpretationPolicy())
            segmenter.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
            val hypothesis = RecognizedUtterance(
                sequence = 90 + index.toLong(),
                text = "The next label is $token",
                sourceLanguageTag = "en-US",
                isFinal = false,
                capturedAtElapsedRealtimeNanos = 0,
                recognizedAtElapsedRealtimeNanos = 100L.ms,
            )
            segmenter.accept(hypothesis)
            segmenter.accept(hypothesis.copy(recognizedAtElapsedRealtimeNanos = 200L.ms))
            segmenter.observeSpeechActivity(isSpeech = false, capturedAtNanos = 300L.ms)

            assertFalse(token, segmenter.tick(2_500L.ms).any(RecognizedUtterance::isFinal))
        }
    }

    @Test
    fun `spurious punctuation fragment does not hide an earlier complete Korean sentence`() {
        val segmenter = RealtimeInterpretationSegmenter(sentenceCompletionInterpretationPolicy())
        segmenter.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
        segmenter.accept(
            partial(95, "첫 안내는 여기서 끝납니다 이번 전시회는. 아직", 100),
        )
        val output = segmenter.accept(
            partial(95, "첫 안내는 여기서 끝납니다 이번 전시회는. 아직 준비 중입니다", 300),
        )

        assertEquals("첫 안내는 여기서 끝납니다", output.single { it.isFinal }.text)
        assertEquals(
            "이번 전시회는. 아직 준비 중입니다",
            output.single { !it.isFinal }.text,
        )
    }

    @Test
    fun `conservative Korean product endings include formal and common polite predicates`() {
        listOf("끝납니다", "갑니다", "봅니다", "좋아요", "있어요", "가나요").forEachIndexed {
                index, ending ->
            val segmenter = RealtimeInterpretationSegmenter(sentenceCompletionInterpretationPolicy())
            segmenter.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
            segmenter.accept(partial(100 + index.toLong(), "안내가 $ending", 100))
            segmenter.accept(partial(100 + index.toLong(), "안내가 $ending", 200))
            segmenter.observeSpeechActivity(isSpeech = false, capturedAtNanos = 300L.ms)
            segmenter.observeContinuousQuiet(fromMillis = 550, throughMillis = 1_500)

            assertEquals(
                ending,
                "안내가 $ending",
                segmenter.tick(1_500L.ms).single(RecognizedUtterance::isFinal).text,
            )
        }
    }

    private fun partial(sequence: Long, text: String, millis: Long) = utterance(
        sequence = sequence,
        text = text,
        millis = millis,
        isFinal = false,
    )

    private fun final(sequence: Long, text: String, millis: Long) = utterance(
        sequence = sequence,
        text = text,
        millis = millis,
        isFinal = true,
    )

    private fun RealtimeInterpretationSegmenter.observeContinuousQuiet(
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

    private fun utterance(
        sequence: Long,
        text: String,
        millis: Long,
        isFinal: Boolean,
    ) = RecognizedUtterance(
        sequence = sequence,
        text = text,
        sourceLanguageTag = "ko-KR",
        isFinal = isFinal,
        capturedAtElapsedRealtimeNanos = 0,
        recognizedAtElapsedRealtimeNanos = millis.ms,
    )

    private val Int.ms: Long get() = toLong().ms
    private val Long.ms: Long get() = this * 1_000_000L
}

package app.guidecast.core.translation

import org.junit.Assert.assertEquals
import org.junit.Test

class CommittedPrefixRevisionTest {
    @Test fun `explanatory and contracted past endings release before the unfinished continuation`() {
        for (sentence in listOf("경기를 봤거든요.", "재능이 터졌구나.", "준비를 마쳤구나.", "그 팀은 가끔 그런다.")) {
            val segmenter = RealtimeInterpretationSegmenter(sentenceCompletionInterpretationPolicy())
            val text = "$sentence 다음 이야기는 아직"
            val first = RecognizedUtterance(1, text, "ko-KR", false, 0, 100_000_000)
            segmenter.observeSpeechActivity(true, 0)
            segmenter.accept(first)
            val output = segmenter.accept(first.copy(recognizedAtElapsedRealtimeNanos = 200_000_000))
            assertEquals(sentence, listOf(sentence), output.filter { it.isFinal }.map { it.text })
            assertEquals("다음 이야기는 아직", output.last { !it.isFinal }.text)
        }
    }

    @Test fun `similar nouns conditional endings and reported speech are not complete sentences`() {
        for (text in listOf("내 친구나", "오래된 가구나", "경기를 보거든", "경기가 끝났구나 라고 말", "경기를 봤거든요 라는 말")) {
            val segmenter = RealtimeInterpretationSegmenter(sentenceCompletionInterpretationPolicy())
            val first = RecognizedUtterance(1, text, "ko-KR", false, 0, 100_000_000)
            segmenter.observeSpeechActivity(true, 0)
            val output = segmenter.accept(first).toMutableList()
            output += segmenter.accept(first.copy(recognizedAtElapsedRealtimeNanos = 200_000_000))
            for (millis in 400L..2_000L step 100) {
                segmenter.observeSpeechActivity(false, millis * 1_000_000)
                output += segmenter.tick(millis * 1_000_000)
            }
            assertEquals(text, emptyList<String>(), output.filter { it.isFinal }.map { it.text })
        }
    }

    @Test fun `negative explanation after finite sentence does not block completed meaning`() {
        val segmenter = RealtimeInterpretationSegmenter(sentenceCompletionInterpretationPolicy())
        val text = "기량이 떨어져. 못 쉬어서 그런"
        val value = RecognizedUtterance(1, text, "ko-KR", false, 0, 100_000_000)
        segmenter.observeSpeechActivity(true, 0)
        segmenter.accept(value)
        val result = segmenter.accept(value.copy(recognizedAtElapsedRealtimeNanos = 200_000_000))
        assertEquals(listOf("기량이 떨어져."), result.filter { it.isFinal }.map { it.text })
        assertEquals("못 쉬어서 그런", result.last { !it.isFinal }.text)
    }

    @Test fun `spoken number reformatting must not replay the committed question ending`() {
        checkRevision("총 10번 했을걸? 11번 했어?", "총 열 번 했을걸? 열 한 번 했어?")
        checkRevision("총 열 번 했을걸? 열 한 번 했어?", "총 10번 했을걸? 11번 했어?")
    }

    @Test fun `a genuine repeated question in the pending continuation is retained`() {
        checkRevision("총 10번 했을걸? 11번 했어?", "총 열 번 했을걸? 열 한 번 했어?",
            "내가 본 기록은 달라요 11번 했어?")
    }

    private fun checkRevision(prefix: String, revisedPrefix: String, finalTail: String = "내가 본 기록은 달라요") {
        val segmenter = RealtimeInterpretationSegmenter(sentenceCompletionInterpretationPolicy())
        fun hypothesis(text: String, at: Long, final: Boolean = false) = RecognizedUtterance(
            sequence = 1, text = text, sourceLanguageTag = "ko-KR", isFinal = final,
            capturedAtElapsedRealtimeNanos = 0, recognizedAtElapsedRealtimeNanos = at * 1_000_000,
        )
        val pending = "내가 본 기록은"
        segmenter.observeSpeechActivity(true, 0)
        segmenter.accept(hypothesis("$prefix $pending", 100))
        val first = segmenter.accept(hypothesis("$prefix $pending", 200)).filter { it.isFinal }
        assertEquals(listOf(prefix), first.map { it.text })
        val revised = segmenter.accept(hypothesis("$revisedPrefix $finalTail", 300, true))
        assertEquals(listOf(finalTail), revised.filter { it.isFinal }.map { it.text })
    }
}

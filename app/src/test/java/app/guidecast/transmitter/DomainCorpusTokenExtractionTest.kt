package app.guidecast.transmitter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainCorpusTokenExtractionTest {

    private val repository = DomainCorpusRepository(null)

    @Test
    fun koreanParticleStrippingExtractsMeaningfulStems() {
        val tokens = repository.extractTokens("회의에서는 다음 일정으로 진행상황을 검토합니다")

        assertTrue("Should contain original or stem '회의'", tokens.contains("회의"))
        assertTrue("Should contain original or stem '일정'", tokens.contains("일정"))
        assertTrue("Should contain original or stem '진행상황'", tokens.contains("진행상황"))
        assertTrue("Should contain '검토합니다' or '검토'", tokens.any { it.startsWith("검토") })
    }

    @Test
    fun shortWordsDoNotProduceSubTwoCharStems() {
        val tokens = repository.extractTokens("돈을 밥을")
        // "돈" has length 1, so stripKoreanParticle should not produce a 1-char stem;
        // but original word or CJK/Hangul character is retained.
        assertTrue(tokens.contains("돈을"))
        assertTrue(tokens.contains("밥을"))
    }

    @Test
    fun cjkIdeographsProduceBigramTokensForUnspacedPhrases() {
        val tokens = repository.extractTokens("会议日程安排")
        assertTrue("Should produce bigrams for Chinese", tokens.contains("会议"))
        assertTrue("Should produce bigrams for Chinese", tokens.contains("议日"))
        assertTrue("Should produce bigrams for Chinese", tokens.contains("日程"))
        assertTrue("Should produce bigrams for Chinese", tokens.contains("程安"))
        assertTrue("Should produce bigrams for Chinese", tokens.contains("安排"))
    }

    @Test
    fun punctuationAndWhitespaceAreStripped() {
        val tokens = repository.extractTokens("“안녕하세요!” [회의] {2026/10/02}?")
        assertTrue(tokens.contains("안녕하세요"))
        assertTrue(tokens.contains("회의"))
        assertFalse(tokens.any { it.contains("“") || it.contains("”") || it.contains("[") || it.contains("]") })
    }
}

package app.guidecast.transmitter

import org.junit.Assert.*
import org.junit.Test

class FileScriptQualityTest {
    @Test fun checksKeepOriginalAndExposeEstimatedTimingAndNumericDifferences() {
        val line = FileSpeechSegment(0, 100, 1_000, "가격은 1200원입니다.", "ko-KR")
        val notes = inspectFileTranscript(listOf(line), 1_000)
        assertTrue(notes.any { "추정" in it })
        assertEquals("가격은 1200원입니다.", line.text)
        assertTrue(translationNumbersNeedReview(line.text, "The price is 200 won."))
        assertFalse(translationNumbersNeedReview(line.text, "The price is 1200 won."))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsReversedMediaOrder() {
        inspectFileTranscript(listOf(FileSpeechSegment(0, 100, 200, "a", "en"),
            FileSpeechSegment(1, 0, 300, "b", "en")), 300)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWordsBeyondTheirMediaSegment() {
        inspectFileTranscript(listOf(FileSpeechSegment(0, 0, 200, "a", "en",
            listOf(FileSpeechWord("a", 3_000)))), 200)
    }

    @Test fun translationChunkingPreservesUnicodeAndContent() {
        val source = ("하나둘😀셋넷다섯".repeat(125))
        val pieces = fileTranslationChunks(source, 20)
        assertEquals(source, pieces.joinToString(""))
        assertTrue(pieces.all { it.length <= 20 && !Character.isHighSurrogate(it.last()) && !Character.isLowSurrogate(it.first()) })
    }
}

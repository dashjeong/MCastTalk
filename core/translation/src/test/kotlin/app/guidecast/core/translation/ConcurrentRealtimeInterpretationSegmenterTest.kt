package app.guidecast.core.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcurrentRealtimeInterpretationSegmenterTest {
    @Test
    fun `interleaved provider lines keep independent revisions and unique transcript ids`() {
        val segmenter = ConcurrentRealtimeInterpretationSegmenter()
        val outputs = mutableListOf<RecognizedUtterance>()
        outputs += segmenter.accept(partial(10, "첫 번째 안내입니다", 100))
        outputs += segmenter.accept(partial(11, "두 번째 장소로", 200))
        outputs += segmenter.accept(final(10, "첫 번째 안내입니다", 300))
        outputs += segmenter.accept(final(11, "두 번째 장소로 이동합니다", 400))

        val finals = outputs.filter { it.isFinal }
        assertEquals(listOf("첫 번째 안내입니다", "두 번째 장소로 이동합니다"), finals.map { it.text })
        assertEquals(finals.size, finals.map { it.sequence }.distinct().size)
        assertFalse(finals.zipWithNext().any { (left, right) -> left.text == right.text })
        assertEquals("첫 번째 안내입니다", finals.last().contextBefore)
    }

    @Test
    fun `finish flushes every unfinished source once`() {
        val segmenter = ConcurrentRealtimeInterpretationSegmenter()
        segmenter.accept(partial(1, "먼저 안내", 100))
        segmenter.accept(partial(2, "다음 안내", 200))

        val first = segmenter.finish(500L.ms)
        val second = segmenter.finish(600L.ms)
        val lateProviderFinal = segmenter.accept(final(1, "먼저 안내입니다", 700))

        assertEquals(listOf("먼저 안내", "다음 안내"), first.map { it.text })
        assertEquals(emptyList<RecognizedUtterance>(), second)
        assertEquals(emptyList<RecognizedUtterance>(), lateProviderFinal)
    }

    @Test
    fun `new provider line during continuous speech uses its own recent onset`() {
        val segmenter = ConcurrentRealtimeInterpretationSegmenter()
        segmenter.observeSpeechActivity(isSpeech = true, capturedAtNanos = 0)
        segmenter.accept(partial(10, "첫 번째 안내를 계속 설명하고 있습니다", 100, 100))

        segmenter.observeSpeechActivity(isSpeech = true, capturedAtNanos = 5_900L.ms)
        val secondLine = segmenter.accept(
            partial(11, "여섯 초 뒤 시작한 새 안내입니다", 6_000, 6_000),
        ).single()

        assertEquals(5_900L.ms, secondLine.capturedAtElapsedRealtimeNanos)
        assertEquals(12_900L.ms, secondLine.firstAudioDeadlineElapsedRealtimeNanos)
        assertTrue(secondLine.firstAudioDeadlineElapsedRealtimeNanos!! > 6_000L.ms)
    }

    @Test
    fun `duplicate final and late partial from a completed source are ignored`() {
        val segmenter = ConcurrentRealtimeInterpretationSegmenter()
        segmenter.accept(partial(20, "중복 방지 안내", 100))

        val firstFinal = segmenter.accept(final(20, "중복 방지 안내입니다", 200))
        val duplicateFinal = segmenter.accept(final(20, "중복 방지 안내입니다", 210))
        val latePartial = segmenter.accept(partial(20, "뒤늦은 수정 가설", 220))

        assertEquals(listOf("중복 방지 안내입니다"), firstFinal.map { it.text })
        assertTrue(duplicateFinal.isEmpty())
        assertTrue(latePartial.isEmpty())
        assertTrue(segmenter.finish(300L.ms).isEmpty())
    }

    private fun partial(sequence: Long, text: String, millis: Long) =
        utterance(sequence, text, millis, false)

    private fun partial(
        sequence: Long,
        text: String,
        millis: Long,
        capturedMillis: Long,
    ) = utterance(sequence, text, millis, false, capturedMillis)

    private fun final(sequence: Long, text: String, millis: Long) =
        utterance(sequence, text, millis, true)

    private fun utterance(
        sequence: Long,
        text: String,
        millis: Long,
        isFinal: Boolean,
        capturedMillis: Long = 0,
    ) =
        RecognizedUtterance(
            sequence = sequence,
            text = text,
            sourceLanguageTag = "ko-KR",
            isFinal = isFinal,
            capturedAtElapsedRealtimeNanos = capturedMillis.ms,
            recognizedAtElapsedRealtimeNanos = millis.ms,
        )

    private val Long.ms: Long get() = this * 1_000_000L
}

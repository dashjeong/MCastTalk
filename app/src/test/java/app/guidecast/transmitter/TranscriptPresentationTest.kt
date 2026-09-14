package app.guidecast.transmitter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class TranscriptPresentationTest {
    @Test
    fun selectionCannotDeleteHiddenRowsOrMatchingSequenceInAnotherBroadcast() {
        val visible = archived(20L, 1L)
        val hidden = archived(10L, 1L)
        val stale = TranscriptArchiveKey(20L, 99L)
        assertEquals(setOf(visible.key), visibleArchiveSelection(
            setOf(visible.key, hidden.key, stale), listOf(visible)))
        assertTrue(visibleArchiveSelection(setOf(hidden.key), listOf(visible)).isEmpty())
    }

    @Test
    fun liveScreenKeepsLatestHundredInBottomFirstOrderAndCoalescesTranslationUpdates() {
        val source = (1L..120L).map { line(it) }
        val updated = source.last().copy(translations = mapOf("en" to "updated translation"))
        val displayed = liveTranscriptDisplayLines(source + updated)
        assertEquals(100, displayed.size)
        assertEquals(120L, displayed.first().sequence)
        assertEquals(21L, displayed.last().sequence)
        assertEquals("updated translation", displayed.first().translations["en"])
        assertEquals(100, displayed.map { it.sequence }.distinct().size)
    }

    @Test
    fun sessionDateIncludesYearAndSecondsToDistinguishRepeatedBroadcasts() {
        val date = formatArchiveSessionTime(0L)
        assertTrue(date.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")))
    }

    @Test
    fun dateRangeIncludesWholeEndDayAndHandlesDaylightSavingWithoutFixed24Hours() {
        val range = parseArchiveDateRange("2026-03-08", "2026-03-08", ZoneId.of("America/New_York"))
        assertEquals(23L * 60L * 60L * 1_000L,
            requireNotNull(range.startedBeforeEpochMillis) - requireNotNull(range.startedFromEpochMillis))
        assertEquals(ArchiveDateRange(null, null), parseArchiveDateRange("", ""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidCalendarDateCannotSilentlyNormalizeIntoAnotherMonth() {
        parseArchiveDateRange("2026-02-30", "2026-03-01")
    }

    @Test(expected = IllegalArgumentException::class)
    fun reversedPeriodCannotEraseOrMislabelQueryScope() {
        parseArchiveDateRange("2026-09-14", "2026-09-13")
    }

    private fun archived(sessionId: Long, sequence: Long) =
        ArchivedTranscriptLine(TranscriptArchiveKey(sessionId, sequence), sessionId, "ko-KR", line(sequence))

    private fun line(sequence: Long) = TranslationTranscriptLine(sequence, "source $sequence", sequence, true)
}

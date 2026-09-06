package app.guidecast.transmitter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcastTranscriptArchiveMergeTest {
    @Test
    fun currentLineReplacesPersistedVersionWithoutDuplicatingSequence() {
        val archived = line(4, "source", translations = mapOf("en" to "old"))
        val current = line(
            4,
            "source corrected",
            translations = mapOf("en" to "new", "ja" to "新しい"),
        )

        val merged = mergeTranscriptLines(listOf(archived), listOf(current))

        assertEquals(1, merged.size)
        assertEquals("source corrected", merged.single().sourceText)
        assertEquals("new", merged.single().translations["en"])
        assertEquals("新しい", merged.single().translations["ja"])
    }

    @Test
    fun mergeIsChronologicalAndBoundedForListenerResponse() {
        val archived = (1L..1_005L).map { sequence -> line(sequence, "line $sequence") }

        val merged = mergeTranscriptLines(archived, emptyList(), maximumLines = 1_000)

        assertEquals(1_000, merged.size)
        assertEquals(6L, merged.first().sequence)
        assertEquals(1_005L, merged.last().sequence)
        assertTrue(merged.zipWithNext().all { (left, right) -> left.sequence < right.sequence })
    }

    @Test
    fun repeatedFailedWriteRequeueStaysBoundedAndKeepsNewestCoalescedValues() {
        val maximumEntries = 256
        val pending = linkedMapOf<Int, String>()

        repeat(6) { retry ->
            val failedBatch = (0 until 300).map { key ->
                key to "failed-$retry-$key"
            }
            (200 until 500).forEach { key ->
                pending.putLatestBounded(
                    key = key,
                    value = "newer-$retry-$key",
                    maximumEntries = maximumEntries,
                )
            }

            pending.mergeOlderEntriesBounded(
                olderEntries = failedBatch,
                maximumEntries = maximumEntries,
            )

            assertEquals(maximumEntries, pending.size)
            assertEquals("newer-$retry-499", pending[499])
            assertTrue(pending.keys.all { it in 244..499 })
        }
    }

    @Test
    fun updatingAnExistingPendingKeyMovesItBehindOlderOverflowCandidates() {
        val pending = linkedMapOf(1 to "old-one", 2 to "two", 3 to "three")

        val dropped = pending.putLatestBounded(1, "new-one", maximumEntries = 3)
        pending.putLatestBounded(4, "four", maximumEntries = 3)

        assertEquals(0, dropped)
        assertEquals(listOf(3, 1, 4), pending.keys.toList())
        assertEquals("new-one", pending[1])
    }

    private fun line(
        sequence: Long,
        source: String,
        translations: Map<String, String> = emptyMap(),
    ) = TranslationTranscriptLine(
        sequence = sequence,
        sourceText = source,
        capturedAtElapsedRealtimeNanos = sequence,
        isFinal = true,
        translations = translations,
    )
}

package app.guidecast.transmitter

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OpenAiNativeLaneIdentityTest {
    @Test fun cancellationUsesTheTranscriptIdentityAndBlocksOnlyItsDequeuedLane() = runTest {
        val targets = listOf("en", "ja", "zh", "zh-TW", "vi")
        val identities = mutableSetOf<Long>()
        for ((index, target) in targets.withIndex()) {
            val base = 2_000_000_000L + index * 1_000_000_000L
            val event = OpenAiAudioEvent("same-provider-input", inputSequence = 7L,
                source = "source", translation = "translation")
            val segments = OpenAiAudioSegments(target, "ko", 77L, sequenceBase = base)
            val transcript = segments.accept(event, 100L)
            val retired = NativeAudioRetiredTurns()
            var cancelledSequence: Long? = null
            var cancelledTranscript: TranslationTranscriptLine? = null
            val output = NativeAudioOutputQueue({ _, _ -> error("Cancelled audio must not be published") })
            val router = NativeAudioEventRouter(output, { cancelled ->
                cancelledSequence = openAiAudioTurnSequence(base, requireNotNull(cancelled.inputSequence))
                retired.retire(requireNotNull(cancelledSequence))
            }, { cancelledTranscript = segments.accept(it, 200L) }, {})
            try {
                router.accept(event.copy(interrupted = true, status = "cancelled"))
                assertEquals(transcript.sequence, cancelledSequence)
                assertEquals(transcript.sequence, cancelledTranscript?.sequence)
                assertEquals(LiveOutputState.CANCELLED, cancelledTranscript?.liveOutputState)
                var writes = 0
                // The PCM was dequeued before cancellation; the final write guard uses its caption ID.
                assertEquals(0, retired.writeIfAllowed(transcript.sequence) { writes++; 1 })
                val otherLane = 2_000_000_000L + ((index + 1) % targets.size) * 1_000_000_000L
                assertEquals(1, retired.writeIfAllowed(openAiAudioTurnSequence(otherLane, 7L)) { writes++; 1 })
                assertEquals(1, writes)
                assertTrue(identities.add(transcript.sequence))
            } finally { output.close() }
        }
        assertEquals(5, identities.size)
    }

    @Test fun omittedLaneBaseKeepsTheExistingSingleLanguageIdentity() {
        val event = OpenAiAudioEvent("input", inputSequence = 7L)
        val original = OpenAiAudioSegments("en", "ko").accept(event, 1L)
        assertEquals(2_000_000_007L, original.sequence)
        assertEquals(original.sequence, openAiAudioTurnSequence(2_000_000_000L, 7L))
    }
}

package app.guidecast.transmitter

import app.guidecast.core.stream.AudioChannelDescriptor
import app.guidecast.core.stream.AudioStreamRegistry
import app.guidecast.core.stream.PcmAudioFrame
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NativeRelayFiveChannelIntegrationTest {
    private val targets = listOf("en", "ja", "zh", "ru", "vi")
    @Test fun fiveIndependentCaptionsAndAudioRemainDistinctAndOneCancellationDoesNotFlushSiblings() = runTest {
        val lifecycle = NativeRelayChannelLifecycle(9, targets)
        val stream = AudioStreamRegistry().configure(targets.map { AudioChannelDescriptor(it, it, it, 24000) })
        val listeners = targets.associateWith { stream.subscribeLocalMonitor(it, preserveNativeAudio = true) }
        val segments = targets.mapIndexed { index, tag -> OpenAiAudioSegments(tag, "ko-KR", 9, 2_000_000_000L + index * 1_000_000_000L) }
        val retired = targets.associateWith { NativeAudioRetiredTurns() }
        try {
            lifecycle.awaitReadyChannels(awaitReady = { })
            val rows = targets.mapIndexed { index, tag -> async {
                val row = segments[index].accept(OpenAiAudioEvent("same-local-provider-id", "synthetic source", "target-$tag",
                    inputSequence = 1, status = "completed", finished = true, sourceFinal = true), 100L + index)
                assertTrue(stream.tryPublish(tag, PcmAudioFrame(byteArrayOf(index.toByte(), 0), 100, row.sequence, nativeAudioSessionId = 9)).accepted)
                row
            } }.awaitAll()
            assertEquals(5, rows.map { it.sequence }.distinct().size)
            assertEquals(targets, rows.map { it.translations.keys.single() })
            val inFlight = targets.associateWith { requireNotNull(listeners.getValue(it).receiveNext()) }
            targets.forEachIndexed { index, tag -> stream.tryPublish(tag, PcmAudioFrame(byteArrayOf(1, 0), 101, rows[index].sequence)) }
            val ja = rows[1].sequence
            cancelNativeAudioOutputTurn(ja, retired.getValue("ja"),
                { stream.discardQueuedAudio("ja", it) }, {}, isCurrent = { true })
            lifecycle.fail("ja")
            assertFalse(retired.getValue("ja").allows(inFlight.getValue("ja").utteranceSequence))
            assertEquals(0, listeners.getValue("ja").bufferSnapshot().pendingFrames)
            for (tag in targets - "ja") {
                assertTrue(lifecycle.accepts(tag))
                assertTrue(retired.getValue(tag).allows(inFlight.getValue(tag).utteranceSequence))
                assertEquals(1, listeners.getValue(tag).bufferSnapshot().pendingFrames)
            }
            lifecycle.revokeConsent()
            assertTrue(targets.none(lifecycle::accepts))
            assertFalse(lifecycle.markReady("ja"))
        } finally { listeners.values.forEach { it.close() }; stream.close() }
    }
    @Test fun longRetirementHistoryInOneLanguageCannotRetireAnotherLanguagesQueuedTurn() {
        val failedLanguage = NativeAudioRetiredTurns()
        val healthyLanguage = NativeAudioRetiredTurns()
        repeat(2500) { failedLanguage.retire(4_000_000_000L + it) }
        assertFalse(failedLanguage.allows(2_000_000_001L))
        assertTrue(healthyLanguage.allows(2_000_000_001L))
        var writes = 0
        assertEquals(12, healthyLanguage.writeIfAllowed(2_000_000_001L) { writes++; 12 })
        assertEquals(1, writes)
    }
}

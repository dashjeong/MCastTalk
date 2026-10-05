package app.guidecast.transmitter

import org.junit.Assert.*
import org.junit.Test

class NativeAudioStageTraceTest {
    private val pcm = byteArrayOf(0, 0, 0, 2, 0, 2, 0, 2)
    @Test fun subsequentTurnsUseTheirOwnExplicitInputAndAbsoluteSampleGoals() {
        var now = 0L
        val trace = NativeAudioStageTrace { now }
        repeat(2) { n ->
            val id = n + 1L
            trace.defineFixtureRegion(id, n * 8L, (n + 1) * 8L); trace.bindFixtureTurn(id, id)
            now += 10; trace.input(8, true)
            now += 2; trace.packetReady(8); now += 3; trace.sent(8)
            now += 10; trace.provider(id, 8, true, false)
            now += 1; trace.published(id, 8)
            now += 9; trace.dequeued(id, 8, now - 9)
            trace.written(id, pcm, 0, 4, 0); trace.written(id, pcm, 4, 4, 0)
            now += 5; trace.head(n * 4L + 1, 0)
            val row = trace.snapshot().getJSONArray("turns").getJSONObject(n)
            assertTrue(row.isNull("first_signal_playhead_ns")); assertTrue(row.isNull("final_sample_playhead_ns"))
            now += 5; trace.head((n + 1) * 4L, 0)
        }
        val rows = trace.snapshot().getJSONArray("turns")
        for (i in 0..1) {
            val r = rows.getJSONObject(i)
            assertEquals("EXPLICIT_SYNTHETIC_FIXTURE", r.getString("association"))
            assertEquals(3L, r.getLong("packet_ready_to_sent_ns"))
            assertEquals(2L, r.getLong("first_input_to_packet_ready_ns"))
            assertEquals(15L, r.getLong("input_signal_to_provider_ns"))
            assertEquals(35L, r.getLong("input_signal_to_playhead_ns"))
            assertEquals(9L, r.getLong("max_queue_residence_ns"))
            assertEquals((i + 1) * 4L, r.getLong("final_sample_goal"))
            assertTrue(r.toString().length < 1800) // durable log has a 2048-byte line budget
        }
    }
    @Test fun ambiguousTurnNeverBorrowsLastInputAndSilentPcmHasNoSignalGoal() {
        val trace = NativeAudioStageTrace { 100 }
        trace.defineFixtureRegion(1, 0, 8); trace.input(8, true); trace.packetReady(8); trace.sent(8)
        trace.provider(1, 8, true, false); trace.published(1, 8); trace.dequeued(1, 8, 99)
        trace.written(1, ByteArray(8), 0, 8, 0); trace.head(4, 0)
        val r = trace.snapshot().getJSONArray("turns").getJSONObject(0)
        assertEquals("UNKNOWN", r.getString("association")); assertTrue(r.isNull("input_signal_to_provider_ns"))
        assertTrue(r.isNull("first_signal_playhead_ns")); assertEquals(100L, r.getLong("final_sample_playhead_ns"))
    }
    @Test fun lossesIncompleteWritesAndEpochChangesCannotCertifyDrainOrAssociation() {
        val trace = NativeAudioStageTrace { 100 }
        trace.defineFixtureRegion(1, 0, 8); trace.bindFixtureTurn(1, 1)
        trace.input(8, true); trace.packetReady(8); trace.sent(8); trace.inputLoss()
        trace.provider(1, 8, true, false); trace.published(1, 8); trace.dequeued(1, 8, null)
        trace.written(1, pcm, 0, 4, 0); trace.head(100, 0)
        var r = trace.snapshot().getJSONArray("turns").getJSONObject(0)
        assertTrue(r.isNull("final_sample_playhead_ns")); assertEquals("UNKNOWN", r.getString("association"))
        trace.head(0, 1); trace.written(1, pcm, 4, 4, 1); trace.head(100, 1)
        r = trace.snapshot().getJSONArray("turns").getJSONObject(0)
        assertTrue(r.getBoolean("invalidated")); assertTrue(r.isNull("final_sample_playhead_ns"))
    }
    @Test fun providerCompletionIsRequiredAndClosingConnectionReceiptDoesNotFreezeOutputTrace() {
        val timing = NativeLiveTiming(1) { 100 }
        val trace = timing.stages
        trace.provider(1, 8, false, false); timing.snapshot(close = true)
        trace.published(1, 8); trace.dequeued(1, 8, null); trace.written(1, pcm, 0, 8, 0); trace.head(4, 0)
        assertTrue(trace.snapshot().getJSONArray("turns").getJSONObject(0).isNull("final_sample_playhead_ns"))
        trace.provider(1, 0, true, false); trace.head(4, 0)
        assertFalse(trace.snapshot().getJSONArray("turns").getJSONObject(0).isNull("final_sample_playhead_ns"))
        val frozen = trace.snapshot(close = true).toString(); trace.provider(2, 8, true, false)
        assertEquals(frozen, trace.snapshot().toString())
    }
    @Test fun boundedTraceDoesNotInventMatchesForEvictedOrUnobservedTurns() {
        val trace = NativeAudioStageTrace { 100 }
        repeat(1000) { trace.provider(it + 1L, 8, true, false) }
        val snapshot = trace.snapshot()
        assertEquals(32, snapshot.getJSONArray("turns").length())
        assertEquals(968L, snapshot.getLong("omitted_turn_events"))
        assertTrue(snapshot.toString().length < 60_000)
    }
}

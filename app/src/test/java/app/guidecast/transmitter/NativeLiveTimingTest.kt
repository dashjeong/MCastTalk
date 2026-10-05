package app.guidecast.transmitter

import org.junit.Assert.*
import org.junit.Test

class NativeLiveTimingTest {
    @Test fun unobservedTimesRemainUnknownAndEmptyWritesAreNotOutput() {
        val timing = NativeLiveTiming()
        timing.input(0, true); timing.sent(0, true); timing.providerAudio(0)
        timing.published(0); timing.localWrite(0); timing.webSocketSent(0)
        val row = timing.snapshot()
        assertTrue(row.isNull("first_input_ns")); assertTrue(row.isNull("first_sent_ns"))
        assertTrue(row.isNull("first_provider_audio_ns")); assertTrue(row.isNull("first_publication_ns"))
        assertTrue(row.isNull("first_local_write_ns")); assertTrue(row.isNull("first_websocket_send_ns"))
    }
    @Test fun inputSignalWireAudioPublicationAndHeadAreSeparateSameClockObservations() {
        var now = 0L
        val timing = NativeLiveTiming(7) { now }
        timing.ready(); now = 10; timing.input(640, true); now = 20; timing.input(640, false)
        now = 30; timing.sent(1280, true); now = 40; timing.sent(3200, false)
        now = 50; timing.providerAudio(960); now = 60; timing.published(960)
        now = 70; timing.localWrite(480); now = 80; timing.localSignalHead(); now = 90; timing.webSocketSent(960)
        val row = timing.snapshot()
        assertEquals(0L, row.getLong("ready_ns")); assertEquals(10L, row.getLong("last_input_signal_ns"))
        assertEquals(20L, row.getLong("last_input_ns")); assertEquals(30L, row.getLong("last_sent_signal_ns"))
        assertEquals(50L, row.getLong("first_provider_audio_ns")); assertEquals(60L, row.getLong("first_publication_ns"))
        assertEquals(70L, row.getLong("first_local_write_ns")); assertEquals(80L, row.getLong("first_local_signal_head_ns"))
        assertEquals(90L, row.getLong("first_websocket_send_ns")); assertEquals(480L, row.getLong("local_written_samples"))
    }
    @Test fun closeFreezesReceiptAndNewConnectionDoesNotBorrowOldTiming() {
        val timing = NativeLiveTiming(1) { 10 }
        timing.providerAudio(960); val closed = timing.snapshot(close = true).toString()
        timing.sent(640, true); timing.localSignalHead(); timing.providerAudio(960)
        assertEquals(closed, timing.snapshot().toString())
        assertTrue(NativeLiveTiming(2).snapshot().isNull("first_provider_audio_ns"))
    }
    @Test fun interruptedConnectionRetainsFirstObservationsAndReportsChangedOutputBoundary() {
        var now = 10L
        val timing = NativeLiveTiming(1) { now }
        timing.providerAudio(960); timing.interrupted(); now = 50
        timing.providerAudio(960); timing.localSignalHead()
        val row = timing.snapshot(close = true)
        assertEquals(10L, row.getLong("first_provider_audio_ns"))
        assertEquals(50L, row.getLong("first_local_signal_head_ns"))
        assertEquals(1L, row.getLong("interruptions"))
        timing.interrupted()
        assertEquals(1L, timing.snapshot().getLong("interruptions"))
    }
    @Test fun snapshotStaysFixedSizeWithoutSpeechKeysAudioOrPerFrameHistory() {
        val timing = NativeLiveTiming(1) { 100 }
        repeat(10000) { timing.input(640, it % 2 == 0); timing.sent(640, it % 2 == 0) }
        val row = timing.snapshot()
        assertTrue(row.toString().length < 1600)
        assertFalse(row.has("source")); assertFalse(row.has("transcript")); assertFalse(row.has("audio")); assertFalse(row.has("key"))
        assertEquals(6_400_000L, row.getLong("input_bytes"))
    }
    @Test fun acceptedLeadingSilenceAndPartialWritesDoNotCountAsSignalPlayhead() {
        val head = NativeAudioHeadProgress()
        val pcm = byteArrayOf(0, 0, 0, 0, 0, 2, 0, 2)
        head.accepted(pcm, 0, 4, 1); assertFalse(head.hasGoal(1))
        head.accepted(pcm, 4, 4, 1); assertFalse(head.reached(2, 1)); assertTrue(head.reached(3, 1))
    }
    @Test fun flushEpochInvalidatesPreviousSignalGoal() {
        val head = NativeAudioHeadProgress()
        head.accepted(byteArrayOf(0, 2), 0, 2, 1)
        assertFalse(head.reached(1, 2))
        head.accepted(byteArrayOf(0, 0), 0, 2, 2); assertFalse(head.hasGoal(2))
    }
    @Test fun signedHeadCounterIsReadAsUnsignedAndIncompleteSamplesAreRejected() {
        val head = NativeAudioHeadProgress()
        assertThrows(IllegalArgumentException::class.java) { head.accepted(byteArrayOf(0, 2), 1, 1, 1) }
        head.accepted(byteArrayOf(0, 2), 0, 2, 1)
        assertEquals(2147483648L, head.samplePosition(Int.MIN_VALUE, 1))
        assertTrue(head.reached(Int.MIN_VALUE, 1))
    }
    @Test fun outOfOrderObservationCannotInventWrapOrCompleteFinalSample() {
        val head = NativeAudioHeadProgress()
        head.accepted(ByteArray(400), 0, 400, 1)
        assertEquals(100L, head.samplePosition(100, 1))
        assertEquals(110L, head.samplePosition(110, 1))
        assertNull(head.samplePosition(100, 1))
        assertEquals(199L, head.samplePosition(199, 1))
        assertTrue(requireNotNull(head.samplePosition(199, 1)) < 200)
        assertEquals(200L, head.samplePosition(200, 1))
    }
    @Test fun actualUnsignedCounterWrapExtendsOnlyPlausibleForwardObservation() {
        val head = NativeAudioHeadProgress()
        head.accepted(byteArrayOf(0, 2), 0, 2, 1)
        assertEquals(4294967280L, head.samplePosition(-16, 1))
        assertEquals(4294967312L, head.samplePosition(16, 1))
        assertNull(head.samplePosition(-8, 1))
        assertEquals(4294967328L, head.samplePosition(32, 1))
    }
    @Test fun flushResetDoesNotBorrowPreviousExtendedPositionOrSignalGoal() {
        val head = NativeAudioHeadProgress()
        head.accepted(byteArrayOf(0, 2), 0, 2, 1)
        assertEquals(1000L, head.samplePosition(1000, 1))
        assertNull(head.samplePosition(0, 1))
        assertNull(head.samplePosition(0, 2))
        head.accepted(byteArrayOf(0, 0), 0, 2, 2)
        assertEquals(0L, head.samplePosition(0, 2))
        assertFalse(head.reached(1000, 2))
    }
}

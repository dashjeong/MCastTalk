package app.guidecast.transmitter

import app.guidecast.core.audio.AudioInputKind
import app.guidecast.core.audio.PcmSineWaveGenerator
import org.junit.Assert.*
import org.junit.Test

class GeminiWireDiagnosticsTest {
    @Test fun physicalInputPermissionAndMuteAreCheckedWithoutOpeningCapture() {
        for (kind in listOf(AudioInputKind.BUILT_IN, AudioInputKind.USB, AudioInputKind.BLUETOOTH, AudioInputKind.WIRED_HEADSET))
            assertNull(relayInputIssue(kind, true, false))
        for (kind in listOf(null, AudioInputKind.DEVICE_PLAYBACK, AudioInputKind.WEB_SPEAKER, AudioInputKind.OTHER))
            assertEquals(RelayInputIssue.PHYSICAL_MICROPHONE_REQUIRED, relayInputIssue(kind, true, false))
        assertEquals(RelayInputIssue.PERMISSION_REQUIRED, relayInputIssue(AudioInputKind.BUILT_IN, false, null))
        assertEquals(RelayInputIssue.SYSTEM_MUTED, relayInputIssue(AudioInputKind.BUILT_IN, true, true))
        assertNull(relayInputIssue(AudioInputKind.BUILT_IN, true, null))
    }
    @Test fun packetBoundariesPreserveOrderingAndNeverPadOrReplayTail() {
        val input = ByteArray(7400) { (it % 251).toByte() }; val packetizer = GeminiPcmPacketizer()
        val packets = listOf(input.copyOfRange(0, 640), input.copyOfRange(640, 3202), input.copyOfRange(3202, 7400))
            .flatMap(packetizer::accept)
        assertEquals(2, packets.size); packets.forEach { assertEquals(3200, it.size) }
        assertArrayEquals(input.copyOf(6400), packets.fold(byteArrayOf()) { all, next -> all + next })
        assertEquals(1000, packetizer.finish()); assertEquals(0, packetizer.finish())
        assertThrows(IllegalStateException::class.java) { packetizer.accept(ByteArray(2)) }
        assertThrows(IllegalArgumentException::class.java) { GeminiPcmPacketizer().accept(ByteArray(3)) }
    }
    @Test fun marksAndSignalHaveKnownClockWhileMissingEventsRemainUnknown() {
        var now = 10L; val diagnostics = GeminiWireDiagnostics { now }
        diagnostics.mark(GeminiWireMark.SETUP_COMPLETE)
        diagnostics.sent(PcmSineWaveGenerator(16_000, 800.0).nextFrame(100))
        now=20; diagnostics.received(parseGeminiLiveEvent("""{"serverContent":{"turnComplete":true}}"""))
        diagnostics.endInput(640, GeminiInputEnd.STOP); now=30; diagnostics.mark(GeminiWireMark.CLOSED)
        val value = diagnostics.snapshot()
        assertEquals(100_000L, value.getLong("sent_audio_duration_us")); assertEquals(1L, value.getLong("sent_energy_packets"))
        assertEquals(20L, value.getJSONObject("server_content").getLong("first_ns"))
        assertTrue(value.getJSONObject("pcm").isNull("first_ns")); assertTrue(value.isNull("safe_failure"))
        assertEquals("STOP", value.getString("input_end")); assertEquals(640, value.getInt("discarded_tail_bytes"))
        assertTrue(value.toString().length < 1800)
    }
}

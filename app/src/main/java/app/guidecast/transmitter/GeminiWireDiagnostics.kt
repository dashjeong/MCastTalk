package app.guidecast.transmitter

import app.guidecast.core.audio.CaptureSignalAccumulator
import org.json.JSONObject

internal enum class GeminiWireMark { SETUP_COMPLETE, SERVER_CONTENT, PCM, TURN_COMPLETE, INTERRUPTED, ERROR, CLOSED }
internal enum class GeminiInputEnd { NORMAL_EOS, STOP, CONSENT_REVOKED, FAILURE }

/** Fixed counters and process-monotonic times, without speech or provider payloads. */
internal class GeminiWireDiagnostics(private val nowNanos: () -> Long = System::nanoTime) {
    private val counts = LongArray(GeminiWireMark.entries.size)
    private val first = arrayOfNulls<Long>(counts.size)
    private val last = arrayOfNulls<Long>(counts.size)
    private val sentSignal = CaptureSignalAccumulator()
    private var tailBytes: Int? = null
    private var tailReason: GeminiInputEnd? = null
    private var failure: OnlineConnectionResult? = null
    @Synchronized fun mark(kind: GeminiWireMark) {
        val at = nowNanos(); counts[kind.ordinal]++
        if (first[kind.ordinal] == null) first[kind.ordinal] = at
        last[kind.ordinal] = at
    }
    @Synchronized fun received(event: GeminiLiveEvent) {
        if (event.serverContent) mark(GeminiWireMark.SERVER_CONTENT)
        if (event.audio.isNotEmpty()) mark(GeminiWireMark.PCM)
        if (event.finished) mark(GeminiWireMark.TURN_COMPLETE)
        if (event.interrupted) mark(GeminiWireMark.INTERRUPTED)
    }
    @Synchronized fun sent(packet: ByteArray) { sentSignal.add(packet) }
    @Synchronized fun sentPacketCount(): Long = sentSignal.snapshot().frames
    @Synchronized fun failed(error: Throwable) {
        failure = onlineConnectionFailureResult(error); mark(GeminiWireMark.ERROR)
    }
    @Synchronized fun endInput(bytes: Int, reason: GeminiInputEnd) { tailBytes = bytes; tailReason = reason }
    @Synchronized fun snapshot(): JSONObject {
        val signal = sentSignal.snapshot()
        return JSONObject().put("clock", "PROCESS_MONOTONIC_NANOS")
            .put("declared_rate_hz", 16_000).put("declared_channels", 1).put("declared_encoding", "PCM16LE")
            .put("sent_packets", signal.frames).put("sent_bytes", signal.bytes)
            .put("sent_sample_count", signal.samples).put("sent_audio_duration_us", signal.samples * 1_000_000 / 16_000)
            .put("sent_rms", signal.rms ?: JSONObject.NULL).put("sent_peak", signal.peak ?: JSONObject.NULL)
            .put("sent_energy_packets", signal.energyFrames)
            .put("discarded_tail_bytes", tailBytes ?: JSONObject.NULL)
            .put("input_end", tailReason?.name ?: JSONObject.NULL)
            .put("safe_failure", failure?.name ?: JSONObject.NULL).also { root ->
                for (kind in GeminiWireMark.entries) root.put(kind.name.lowercase(), JSONObject()
                    .put("count", counts[kind.ordinal]).put("first_ns", first[kind.ordinal] ?: JSONObject.NULL)
                    .put("last_ns", last[kind.ordinal] ?: JSONObject.NULL))
            }
    }
}

/** Emits complete 100ms PCM16 mono16k packets only. All termination paths discard the tail. */
internal class GeminiPcmPacketizer {
    private val pending = java.io.ByteArrayOutputStream(3_200)
    private var ended = false
    fun accept(frame: ByteArray): List<ByteArray> {
        check(!ended); require(frame.size % 2 == 0 && frame.size <= 32_000)
        val packets = ArrayList<ByteArray>()
        var offset = 0
        while (offset < frame.size) {
            val count = minOf(3_200 - pending.size(), frame.size - offset)
            pending.write(frame, offset, count); offset += count
            if (pending.size() == 3_200) { packets += pending.toByteArray(); pending.reset() }
        }
        return packets
    }
    fun finish(): Int { ended = true; return pending.size().also { pending.reset() } }
}

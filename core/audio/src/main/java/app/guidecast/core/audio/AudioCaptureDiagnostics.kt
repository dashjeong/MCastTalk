package app.guidecast.core.audio

import kotlin.math.sqrt

/** Numeric observations only; no device identifiers or PCM are retained. */
data class CaptureSignalSnapshot(
    val frames: Long = 0, val bytes: Long = 0, val samples: Long = 0,
    val rms: Double? = null, val peak: Float? = null, val energyFrames: Long = 0,
)

class CaptureSignalAccumulator {
    private var frames = 0L
    private var bytes = 0L
    private var samples = 0L
    private var squares = 0.0
    private var peak: Float? = null
    private var energyFrames = 0L
    @Synchronized fun add(pcm: ByteArray) {
        val signal = pcm.pcmS16LeSignalStats()
        frames++; bytes += pcm.size; samples += signal.sampleCount
        squares += signal.rms.toDouble() * signal.rms * signal.sampleCount
        peak = maxOf(peak ?: 0f, signal.peak)
        if (signal.rms >= 0.002f || signal.peak >= 0.01f) energyFrames++
    }
    @Synchronized fun snapshot() = CaptureSignalSnapshot(frames, bytes, samples,
        if (samples == 0L) null else sqrt(squares / samples), peak, energyFrames)
}

/** Carries a partial PCM16 sample across positive reads; stop never emits its remaining byte. */
class Pcm16ReadAssembler {
    private var lowByte: Byte? = null
    val pendingBytes: Int @Synchronized get() = if (lowByte == null) 0 else 1
    @Synchronized fun accept(buffer: ByteArray, count: Int): ByteArray {
        require(count in 0..buffer.size)
        val total = count + pendingBytes
        val output = ByteArray(total - total % 2)
        var at = 0
        lowByte?.let { if (output.isNotEmpty()) output[at++] = it }
        var offset = 0
        while (at < output.size) output[at++] = buffer[offset++]
        lowByte = if (offset < count) buffer[offset] else if (output.isEmpty()) lowByte else null
        return output
    }
    @Synchronized fun discardTail(): Int = pendingBytes.also { lowByte = null }
}

data class AudioCaptureDiagnosticSnapshot(
    val session: Long = 0, val state: String = "NOT_STARTED",
    val requestedSampleRateHz: Int? = null, val recorderSampleRateHz: Int? = null,
    val recorderChannels: Int? = null, val recorderEncoding: Int? = null,
    val routedDeviceType: Int? = null, val recordingState: Int? = null,
    val systemMicrophoneMuted: Boolean? = null, val clientSilenced: Boolean? = null,
    val readCalls: Long = 0, val zeroReads: Long = 0, val readErrors: Long = 0,
    val shortReads: Long = 0, val oddReads: Long = 0, val lastReadElapsedNanos: Long? = null,
    val lastReadAttemptElapsedNanos: Long? = null, val lastReadResultElapsedNanos: Long? = null,
    val noProgressSinceElapsedNanos: Long? = null, val consecutiveEmptyReads: Long = 0,
    val lastReadResult: String = "NOT_READ",
    val raw: CaptureSignalSnapshot = CaptureSignalSnapshot(),
    val output: CaptureSignalSnapshot = CaptureSignalSnapshot(),
    val discardedPartialSampleBytes: Int = 0, val denoiserPendingInputBytes: Int = 0,
)

internal class AudioCaptureDiagnosticTracker(private var value: AudioCaptureDiagnosticSnapshot) {
    private val raw = CaptureSignalAccumulator()
    private val rawAssembler = Pcm16ReadAssembler()
    private val output = CaptureSignalAccumulator()
    @Synchronized fun route(type: Int?) { if (value.state == "RECORDING") value = value.copy(routedDeviceType = type) }
    @Synchronized fun policy(muted: Boolean?, silenced: Boolean?) {
        if (value.state == "RECORDING") value = value.copy(systemMicrophoneMuted = muted, clientSilenced = silenced)
    }
    @Synchronized fun beginRead(now: Long) {
        if (value.state != "RECORDING") return
        value = value.copy(readCalls = value.readCalls + 1, lastReadAttemptElapsedNanos = now,
            noProgressSinceElapsedNanos = value.noProgressSinceElapsedNanos ?: if (value.lastReadElapsedNanos == null) now else null)
    }
    @Synchronized fun read(pcm: ByteArray, requestedBytes: Int, now: Long) {
        if (value.state != "RECORDING") return
        val aligned = rawAssembler.accept(pcm, pcm.size)
        if (aligned.isNotEmpty()) raw.add(aligned)
        value = value.copy(
            shortReads = value.shortReads + if (pcm.size < requestedBytes) 1 else 0,
            oddReads = value.oddReads + pcm.size % 2, lastReadElapsedNanos = now,
            lastReadResultElapsedNanos = now, noProgressSinceElapsedNanos = null,
            consecutiveEmptyReads = 0, lastReadResult = "PCM_READ")
    }
    @Synchronized fun emptyRead(error: Boolean, now: Long) {
        if (value.state != "RECORDING") return
        value = value.copy(
            zeroReads = value.zeroReads + if (error) 0 else 1,
            readErrors = value.readErrors + if (error) 1 else 0,
            lastReadResultElapsedNanos = now, consecutiveEmptyReads = value.consecutiveEmptyReads + 1,
            noProgressSinceElapsedNanos = value.noProgressSinceElapsedNanos ?: now,
            lastReadResult = if (error) "READ_ERROR" else "ZERO_READ")
    }
    @Synchronized fun emitted(pcm: ByteArray) { if (value.state == "RECORDING" && pcm.isNotEmpty()) output.add(pcm) }
    @Synchronized fun close(partial: Int, denoiserPending: Int) {
        value = value.copy(state = if (value.readErrors > 0) "FAILED" else "CLOSED",
            discardedPartialSampleBytes = partial, denoiserPendingInputBytes = denoiserPending)
    }
    @Synchronized fun snapshot() = value.copy(raw = raw.snapshot(), output = output.snapshot())
}

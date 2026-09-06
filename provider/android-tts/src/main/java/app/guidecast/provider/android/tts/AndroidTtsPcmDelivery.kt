package app.guidecast.provider.android.tts

import android.os.SystemClock
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Callback-safe handoff from Android TTS into a paced Flow collector.
 *
 * Android engines may deliver a complete utterance from [onAudioAvailable] in a burst. This
 * bridge accepts only a bounded prefix as fixed-duration PCM16 mono frames. Once the live queue
 * is full it stops accepting callback PCM; [recoveryStartByteOffset] then identifies the exact
 * unsent suffix in the completed synthesize-to-file WAV, without replaying accepted audio.
 */
internal class BoundedPcmFrameBridge(
    sampleRateHz: Int,
    frameDurationMillis: Int,
    capacity: Int,
) {
    val frameBytes = pcm16MonoFrameBytes(sampleRateHz, frameDurationMillis)
    private val channel = Channel<ByteArray>(capacity)
    val frames: ReceiveChannel<ByteArray> = channel

    private val lock = Any()
    private var state = BridgeState.ACTIVE
    private val tailBuffer = ByteArray(frameBytes)
    private var tailBytes = 0
    private var callbackPcmBytesSeen = 0L
    private var acceptedFilePrefixBytes = 0
    private var overflowed = false
    private var recoveryOffset: Int? = null

    init {
        require(capacity > 0) { "PCM bridge capacity must be positive" }
    }

    /** Called only from TTS callbacks; never blocks the callback thread. */
    fun offer(pcmS16LeMono: ByteArray) {
        require(pcmS16LeMono.size % Short.SIZE_BYTES == 0) {
            "Android TTS PCM contains an incomplete sample"
        }
        if (pcmS16LeMono.isEmpty()) return

        synchronized(lock) {
            if (state != BridgeState.ACTIVE) return
            callbackPcmBytesSeen += pcmS16LeMono.size
            if (overflowed) return

            var inputOffset = 0
            val inputSize = pcmS16LeMono.size

            if (tailBytes > 0) {
                val needed = frameBytes - tailBytes
                if (inputSize >= needed) {
                    val frame = ByteArray(frameBytes)
                    System.arraycopy(tailBuffer, 0, frame, 0, tailBytes)
                    System.arraycopy(pcmS16LeMono, 0, frame, tailBytes, needed)
                    tailBytes = 0
                    if (!channel.trySend(frame).isSuccess) {
                        overflowed = true
                        return
                    }
                    acceptedFilePrefixBytes += frameBytes
                    inputOffset = needed
                } else {
                    System.arraycopy(pcmS16LeMono, 0, tailBuffer, tailBytes, inputSize)
                    tailBytes += inputSize
                    return
                }
            }

            while (inputSize - inputOffset >= frameBytes) {
                val frame = pcmS16LeMono.copyOfRange(inputOffset, inputOffset + frameBytes)
                if (!channel.trySend(frame).isSuccess) {
                    overflowed = true
                    tailBytes = 0
                    return
                }
                acceptedFilePrefixBytes += frameBytes
                inputOffset += frameBytes
            }

            val remaining = inputSize - inputOffset
            if (remaining > 0) {
                System.arraycopy(pcmS16LeMono, inputOffset, tailBuffer, 0, remaining)
                tailBytes = remaining
            }
        }
    }

    /** Marks the WAV complete and closes the live prefix after preserving any final partial frame. */
    fun finish() {
        synchronized(lock) {
            if (state != BridgeState.ACTIVE) return
            if (!overflowed && tailBytes > 0) {
                val rawTailBytes = tailBytes
                val padded = ByteArray(frameBytes)
                System.arraycopy(tailBuffer, 0, padded, 0, rawTailBytes)
                if (channel.trySend(padded).isSuccess) {
                    acceptedFilePrefixBytes += rawTailBytes
                } else {
                    overflowed = true
                }
                tailBytes = 0
            }

            recoveryOffset = when {
                overflowed -> acceptedFilePrefixBytes
                callbackPcmBytesSeen == 0L -> 0
                else -> null
            }
            state = BridgeState.FINISHED
            channel.close()
        }
    }

    fun fail(error: Throwable) {
        synchronized(lock) {
            if (state != BridgeState.ACTIVE) return
            state = BridgeState.FAILED
            tailBytes = 0
            channel.close(error)
        }
    }

    fun abort() {
        synchronized(lock) {
            if (state != BridgeState.ACTIVE) return
            state = BridgeState.FAILED
            tailBytes = 0
            channel.close()
        }
    }

    fun recoveryStartByteOffset(): Int? = synchronized(lock) {
        check(state == BridgeState.FINISHED) {
            "Android TTS PCM recovery was queried before synthesis completed"
        }
        recoveryOffset
    }

    private enum class BridgeState {
        ACTIVE,
        FINISHED,
        FAILED,
    }
}

/** Splits PCM16 mono into fixed frames and zero-pads only the final incomplete frame. */
internal fun fixedPcm16MonoFrames(
    pcm: ByteArray,
    frameBytes: Int,
    startByteOffset: Int = 0,
): Sequence<ByteArray> {
    require(frameBytes > 0 && frameBytes % Short.SIZE_BYTES == 0) {
        "PCM frame size must contain complete PCM16 samples"
    }
    require(pcm.size % Short.SIZE_BYTES == 0) { "PCM contains an incomplete sample" }
    require(startByteOffset in 0..pcm.size && startByteOffset % Short.SIZE_BYTES == 0) {
        "PCM recovery offset is invalid"
    }
    return sequence {
        var offset = startByteOffset
        while (offset < pcm.size) {
            val end = minOf(pcm.size, offset + frameBytes)
            val frame = pcm.copyOfRange(offset, end)
            yield(if (frame.size == frameBytes) frame else frame.copyOf(frameBytes))
            offset = end
        }
    }
}

/** Ensures frames leave the provider no faster than their PCM sample duration. */
internal class SampleDurationPacer(
    private val sampleRateHz: Int,
    private val nowNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val waitUntilNanos: suspend (Long) -> Unit = ::waitUntilElapsedRealtimeNanos,
) {
    private var nextFrameAtNanos: Long? = null

    init {
        require(sampleRateHz > 0) { "PCM sample rate must be positive" }
    }

    suspend fun awaitTurn(frameByteCount: Int): Long {
        require(frameByteCount > 0 && frameByteCount % Short.SIZE_BYTES == 0) {
            "PCM frame contains incomplete samples"
        }
        val target = nextFrameAtNanos
        if (target != null) waitUntilNanos(target)
        val now = nowNanos()
        val emittedAt = max(now, target ?: now)
        val sampleCount = frameByteCount / Short.SIZE_BYTES
        val durationNanos = sampleCount * NANOS_PER_SECOND / sampleRateHz
        check(durationNanos > 0L) { "PCM frame duration is too short" }
        nextFrameAtNanos = emittedAt + durationNanos
        return emittedAt
    }
}

private fun pcm16MonoFrameBytes(sampleRateHz: Int, frameDurationMillis: Int): Int {
    require(sampleRateHz > 0) { "PCM sample rate must be positive" }
    require(frameDurationMillis > 0) { "PCM frame duration must be positive" }
    val samplesNumerator = sampleRateHz.toLong() * frameDurationMillis
    require(samplesNumerator % MILLIS_PER_SECOND == 0L) {
        "PCM frame duration must contain a whole number of samples"
    }
    val bytes = samplesNumerator / MILLIS_PER_SECOND * Short.SIZE_BYTES
    require(bytes in Short.SIZE_BYTES.toLong()..Int.MAX_VALUE.toLong()) {
        "PCM frame size is invalid"
    }
    return bytes.toInt()
}

private suspend fun waitUntilElapsedRealtimeNanos(targetNanos: Long) {
    while (true) {
        val remainingNanos = targetNanos - SystemClock.elapsedRealtimeNanos()
        if (remainingNanos <= 0L) return
        delay((remainingNanos / NANOS_PER_MILLISECOND).coerceAtLeast(1L))
    }
}

private const val MILLIS_PER_SECOND = 1_000L
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val NANOS_PER_SECOND = 1_000_000_000L

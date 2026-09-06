package app.guidecast.core.audio

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe, generation-scoped bridge connecting remote web speaker audio
 * (streamed via WebSocket from /ws/speaker-input) to the GuideCast audio capture pipeline.
 *
 * Uses replay=0 to ensure new subscribers receive only fresh live PCM frames without
 * stale historical playback. Enforces PCM16 LE, 16kHz mono format, monotonic timestamp ordering,
 * and frame size constraints.
 */
object WebAudioInputBridge {
    const val EXPECTED_SAMPLE_RATE_HZ = 16_000
    const val BYTES_PER_SAMPLE = 2 // PCM16 LE mono
    const val MAX_FRAME_BYTES = 16_384

    private data class SessionFrame(
        val generation: Long,
        val frame: PcmFrame,
    )

    private val frameFlow = MutableSharedFlow<SessionFrame>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val connected = AtomicBoolean(false)
    private val activeSessionGeneration = AtomicLong(0L)
    private val lastFrameElapsedNanos = AtomicLong(0L)
    private val lastFrameCapturedNanos = AtomicLong(0L)

    val isSpeakerConnected: Boolean
        get() = connected.get()

    val currentSessionGeneration: Long
        get() = activeSessionGeneration.get()

    fun openSession(): Long = synchronized(this) {
        val newGen = activeSessionGeneration.incrementAndGet()
        connected.set(true)
        lastFrameElapsedNanos.set(0L)
        lastFrameCapturedNanos.set(0L)
        newGen
    }

    fun closeSession(generation: Long): Boolean = synchronized(this) {
        if (activeSessionGeneration.get() == generation) {
            connected.set(false)
            lastFrameElapsedNanos.set(0L)
            lastFrameCapturedNanos.set(0L)
            true
        } else {
            false
        }
    }

    fun isStreamingActive(
        nowNanos: Long = System.nanoTime(),
        thresholdNanos: Long = 600_000_000L,
    ): Boolean = connected.get() && (nowNanos - lastFrameElapsedNanos.get()) < thresholdNanos

    /**
     * A lagging collector may still have entries in SharedFlow's bounded extra buffer when a web
     * microphone reconnects. Tagging every entry and checking at delivery prevents that old PCM
     * from crossing into the replacement input session.
     */
    fun frames(): Flow<PcmFrame> = frameFlow.asSharedFlow()
        .filter { sessionFrame ->
            connected.get() && sessionFrame.generation == activeSessionGeneration.get()
        }
        .map { sessionFrame -> sessionFrame.frame }

    val subscriptionCount: kotlinx.coroutines.flow.StateFlow<Int>
        get() = frameFlow.subscriptionCount

    /**
     * Validates and emits a frame into the bridge.
     * Rejects frames that do not match 16kHz mono S16LE, violate monotonic order,
     * exceed max frame bytes, or belong to an outdated session generation.
     */
    @Synchronized
    fun emitFrame(
        frame: PcmFrame,
        generation: Long = activeSessionGeneration.get(),
    ): Boolean {
        if (!connected.get() || generation != activeSessionGeneration.get()) return false
        if (frame.sampleRateHz != EXPECTED_SAMPLE_RATE_HZ) return false
        if (frame.bytes.isEmpty() || frame.bytes.size > MAX_FRAME_BYTES || frame.bytes.size % BYTES_PER_SAMPLE != 0) {
            return false
        }
        val prevCaptured = lastFrameCapturedNanos.get()
        if (prevCaptured > 0L && frame.capturedAtElapsedRealtimeNanos < prevCaptured) {
            return false // Out of order frame
        }

        lastFrameCapturedNanos.set(frame.capturedAtElapsedRealtimeNanos)
        lastFrameElapsedNanos.set(System.nanoTime())
        return frameFlow.tryEmit(SessionFrame(generation, frame))
    }

    fun reset() = synchronized(this) {
        activeSessionGeneration.incrementAndGet()
        connected.set(false)
        lastFrameElapsedNanos.set(0L)
        lastFrameCapturedNanos.set(0L)
    }
}

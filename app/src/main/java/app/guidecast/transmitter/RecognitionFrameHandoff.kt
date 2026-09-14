package app.guidecast.transmitter

import app.guidecast.core.stream.PcmAudioFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/** A stalled recognition consumer must not suspend the independent original-audio input. */
internal suspend fun forwardRecognitionFrame(
    input: SendChannel<PcmAudioFrame>,
    frame: PcmAudioFrame,
    timeoutMillis: Long = 100L,
): Boolean = try {
    withTimeoutOrNull(timeoutMillis) { input.send(frame); true } ?: false
} catch (cancelled: CancellationException) {
    coroutineContext.ensureActive() // User/owner cancellation always wins.
    false // Only the replaced recognition channel was cancelled.
} catch (_: ClosedSendChannelException) {
    false
}

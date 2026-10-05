package app.guidecast.transmitter

import app.guidecast.core.stream.LocalAudioBufferSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Latest local playback only. PCM acceptance and playhead progress do not prove human hearing. */
internal data class NativeLocalPlaybackSnapshot(
    val generation: Long, val buffer: LocalAudioBufferSnapshot,
    val writtenBytes: Long, val playbackHeadSamples: Long?, val unwrittenDequeuedBytes: Long,
    val epochChanged: Boolean, val closed: Boolean = false,
    val completedProviderBytes: Long? = null,
    val playbackOwner: Long = 0,
) {
    val fullyDrained: Boolean get() = !epochChanged && buffer.pendingBytes == 0L && buffer.pendingFrames == 0 &&
        buffer.offeredBytes == buffer.admittedBytes && buffer.admittedBytes == writtenBytes &&
        buffer.rejectedBytes == 0L && buffer.overwrittenBytes == 0L && buffer.canceledBytes == 0L &&
        unwrittenDequeuedBytes == 0L && (playbackHeadSamples ?: -1) >= writtenBytes / 2 && writtenBytes > 0
    val providerCompleteAndDrained: Boolean get() = completedProviderBytes != null &&
        completedProviderBytes == buffer.offeredBytes && fullyDrained
    fun countsOnly() = JSONObject().put("generation", generation).put("offered_bytes", buffer.offeredBytes)
        .put("playback_owner", playbackOwner)
        .put("admitted_bytes", buffer.admittedBytes).put("dequeued_bytes", buffer.dequeuedBytes)
        .put("queued_bytes", buffer.pendingBytes).put("queued_packets", buffer.pendingFrames)
        .put("rejected_bytes", buffer.rejectedBytes).put("overwritten_bytes", buffer.overwrittenBytes)
        .put("canceled_bytes", buffer.canceledBytes).put("written_bytes", writtenBytes)
        .put("head_samples", playbackHeadSamples ?: JSONObject.NULL)
        .put("unwritten_dequeued_bytes", unwrittenDequeuedBytes).put("epoch_changed", epochChanged)
        .put("fully_drained", fullyDrained).put("closed", closed)
        .put("completed_provider_bytes", completedProviderBytes ?: JSONObject.NULL)
        .put("provider_complete_and_drained", providerCompleteAndDrained)
        .put("scope", "LOCAL_PCM_AND_TRACK_PLAYHEAD_NOT_ACOUSTIC")
}

internal class NativeLocalPlaybackProgressStore {
    private val mutableState = MutableStateFlow<NativeLocalPlaybackSnapshot?>(null)
    private var activeOwner = 0L
    private var completedGeneration: Long? = null
    private var completedBytes: Long? = null
    val state = mutableState.asStateFlow()
    @Synchronized fun beginOwner(): Long {
        activeOwner += 1
        mutableState.value = null
        completedGeneration = null; completedBytes = null
        return activeOwner
    }
    @Synchronized fun update(value: NativeLocalPlaybackSnapshot) {
        if (value.playbackOwner != activeOwner) return
        val previous = mutableState.value
        if (previous != null && (previous.generation > value.generation ||
            previous.generation == value.generation && previous.closed && !value.closed)) return
        mutableState.value = value.copy(completedProviderBytes =
            completedBytes.takeIf { completedGeneration == value.generation })
    }
    @Synchronized fun providerCompleted(generation: Long, publishedBytes: Long, playbackOwner: Long) {
        if (playbackOwner != activeOwner) return
        if ((mutableState.value?.generation ?: generation) > generation) return
        completedGeneration = generation; completedBytes = publishedBytes
        mutableState.value?.takeIf { it.generation == generation }?.let {
            mutableState.value = it.copy(completedProviderBytes = publishedBytes)
        }
    }
}

internal object NativeLocalPlaybackProgress {
    private val store = NativeLocalPlaybackProgressStore()
    val state = store.state
    fun beginOwner(): Long = store.beginOwner()
    fun update(value: NativeLocalPlaybackSnapshot) = store.update(value)
    fun providerCompleted(generation: Long, publishedBytes: Long, playbackOwner: Long) =
        store.providerCompleted(generation, publishedBytes, playbackOwner)
}

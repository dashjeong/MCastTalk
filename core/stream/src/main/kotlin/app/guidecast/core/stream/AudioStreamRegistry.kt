package app.guidecast.core.stream

import java.io.Closeable
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AudioChannelDescriptor(
    val id: String,
    val displayName: String,
    val languageTag: String,
    val sampleRateHz: Int,
) {
    init {
        require(ID_PATTERN.matches(id)) { "Invalid channel id" }
        require(displayName.isNotBlank() && displayName.length <= 40) { "Invalid display name" }
        require(languageTag.matches(LANGUAGE_TAG_PATTERN)) { "Invalid language tag" }
        require(sampleRateHz in 8_000..48_000) { "Unsupported sample rate" }
    }

    companion object {
        private val ID_PATTERN = Regex("[a-z0-9][a-z0-9_-]{0,23}")
        private val LANGUAGE_TAG_PATTERN = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*")
    }
}

data class PcmAudioFrame(
    val bytes: ByteArray,
    val capturedAtElapsedRealtimeNanos: Long,
    /** Finalized utterance that produced this frame, when the producer can correlate it. */
    val utteranceSequence: Long? = null,
) {
    init {
        require(bytes.isNotEmpty()) { "PCM frame must not be empty" }
        require(bytes.size % 2 == 0) { "PCM S16LE frame must contain complete samples" }
    }
}

class ListenerLimitExceededException(
    message: String = "Listener limit reached",
) : IllegalStateException(message)

class StreamSessionSupersededException :
    IllegalStateException("Stream session was replaced by a newer broadcast")

/** Maximum number of independently routed audio channels in one local broadcast session. */
const val MAX_SIMULTANEOUS_TRANSLATED_CHANNELS = 7
const val MAX_SIMULTANEOUS_AUDIO_CHANNELS = MAX_SIMULTANEOUS_TRANSLATED_CHANNELS + 1

/**
 * The sole, immutable source of truth for listener and overflow telemetry.
 *
 * [droppedFrames] counts listener-frame deliveries discarded because that listener's bounded
 * queue was full. Thus one PCM frame dropped for two slow listeners contributes two. Session
 * replacement and explicit close do not count as congestion drops.
 */
data class AudioStreamObservabilitySnapshot(
    val generation: Long,
    val isActive: Boolean,
    val totalListeners: Int,
    val listenersByChannel: Map<String, Int>,
    val droppedFrames: Long,
    val droppedFramesByChannel: Map<String, Long>,
    /**
     * Listener-frame writes for which the server's WebSocket send call completed successfully.
     * This is stronger evidence than queue admission, but does not claim browser playback.
     */
    val webSocketDeliveredFrames: Long = 0,
    val webSocketDeliveredFramesByChannel: Map<String, Long> = emptyMap(),
    val lastWebSocketDeliveredSequenceByChannel: Map<String, Long?> = emptyMap(),
)

enum class StreamPublishStatus {
    PUBLISHED,
    STALE_SESSION,
    UNKNOWN_CHANNEL,
}

data class StreamPublishResult(
    val status: StreamPublishStatus,
    val targetListeners: Int = 0,
    /** Legacy name: frames accepted into listener mailboxes, before any WebSocket send. */
    val deliveredFrames: Int = 0,
    val droppedFrames: Int = 0,
) {
    val accepted: Boolean get() = status == StreamPublishStatus.PUBLISHED

    /** Clearer compatibility alias: [deliveredFrames] means bounded-mailbox admission only. */
    val enqueuedFrames: Int get() = deliveredFrames
}

/**
 * Immutable handle for exactly one configured broadcast generation.
 *
 * Retain this handle in every producer and server created for the broadcast. Calling [configure]
 * creates a new handle, atomically supersedes this one, and cancels all of this session's
 * subscriptions even when the new descriptors reuse identical channel IDs.
 */
class StreamSession internal constructor(
    private val registry: AudioStreamRegistry,
    internal val state: StreamSessionState,
) : Closeable {
    val generation: Long = state.generation
    val channels: List<AudioChannelDescriptor> = state.descriptors
    val maxListeners: Int = registry.maxListeners

    /** Descriptor lookup and listener admission happen at one registry linearization point. */
    fun subscribe(channelId: String): AudioListenerSubscription =
        registry.subscribe(this, channelId)

    /**
     * In-process operator monitoring of already-published PCM. This is intentionally outside
     * remote listener admission and WebSocket delivery telemetry.
     */
    fun subscribeLocalMonitor(channelId: String): LocalAudioMonitorSubscription =
        registry.subscribeLocalMonitor(this, channelId)

    /** A stale generation is rejected and can never publish into a newer same-ID channel. */
    fun tryPublish(channelId: String, frame: PcmAudioFrame): StreamPublishResult =
        registry.tryPublish(this, channelId, frame)

    fun publish(channelId: String, frame: PcmAudioFrame): StreamPublishResult {
        val result = tryPublish(channelId, frame)
        when (result.status) {
            StreamPublishStatus.PUBLISHED -> Unit
            StreamPublishStatus.STALE_SESSION -> throw StreamSessionSupersededException()
            StreamPublishStatus.UNKNOWN_CHANNEL -> error("Unknown stream channel")
        }
        return result
    }

    /** A frozen zero-listener snapshot is returned after this session is superseded. */
    fun observabilitySnapshot(): AudioStreamObservabilitySnapshot = registry.snapshotFor(this)

    fun descriptor(channelId: String): AudioChannelDescriptor? =
        channels.firstOrNull { it.id == channelId }

    fun isActive(): Boolean = registry.isActive(this)

    /**
     * Deactivates only this exact generation. Closing a stale handle can never tear down the
     * newer broadcast that replaced it.
     */
    override fun close() {
        registry.close(this)
    }
}

internal class StreamSessionState(
    val generation: Long,
    descriptors: List<AudioChannelDescriptor>,
) {
    val descriptors: List<AudioChannelDescriptor> =
        Collections.unmodifiableList(ArrayList(descriptors))
    val descriptorById: Map<String, AudioChannelDescriptor> =
        Collections.unmodifiableMap(this.descriptors.associateBy(AudioChannelDescriptor::id))
    val subscribersByChannel = mutableMapOf<String, MutableMap<Long, ListenerMailbox>>()
    val monitorSubscribersByChannel = mutableMapOf<String, MutableMap<Long, ListenerMailbox>>()
    /**
     * Copy-on-write topology used by the 20 ms publish path. Mailboxes are copied only when a
     * listener joins or leaves, never for every audio frame.
     */
    val publishTargetsByChannel: MutableMap<String, PublishTargets> =
        this.descriptors.associateTo(linkedMapOf()) { descriptor ->
            descriptor.id to PublishTargets.EMPTY
        }
    val droppedFramesByChannel = this.descriptors.associate { it.id to 0L }.toMutableMap()
    val webSocketDeliveredFramesByChannel =
        this.descriptors.associate { it.id to 0L }.toMutableMap()
    val lastWebSocketDeliveredSequenceByChannel: MutableMap<String, Long?> =
        linkedMapOf<String, Long?>().apply {
            this@StreamSessionState.descriptors.forEach { descriptor -> put(descriptor.id, null) }
        }
    var active: Boolean = true
}

class AudioStreamRegistry(
    private val maxChannels: Int = MAX_SIMULTANEOUS_AUDIO_CHANNELS,
    val maxListeners: Int = 50,
    private val listenerBufferFrames: Int = 8,
) {
    private val lock = Any()
    private val nextSubscriptionId = AtomicLong(0)
    private var nextGeneration = 0L
    private var activeState = StreamSessionState(generation = nextGeneration, descriptors = emptyList())

    private val mutableChannels = MutableStateFlow<List<AudioChannelDescriptor>>(emptyList())
    /** Compatibility view. A retained [StreamSession] is safer for broadcast work. */
    val channels: StateFlow<List<AudioChannelDescriptor>> = mutableChannels.asStateFlow()

    private val mutableObservability = MutableStateFlow(activeState.toSnapshot())
    /**
     * Coalesced compatibility view. Listener topology changes are emitted immediately; frame-level
     * drop/delivery counters are refreshed by [StreamSession.observabilitySnapshot] so a slow
     * browser cannot drive one UI/notification update per 20 ms audio frame.
     */
    val observability: StateFlow<AudioStreamObservabilitySnapshot> = mutableObservability.asStateFlow()

    private val mutableListenerCount = MutableStateFlow(0)
    /** Compatibility projection of [observability]. */
    val listenerCount: StateFlow<Int> = mutableListenerCount.asStateFlow()

    private val mutableListenerCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Compatibility projection of [observability], ordered like [channels]. */
    val listenerCounts: StateFlow<Map<String, Int>> = mutableListenerCounts.asStateFlow()

    init {
        require(maxChannels in 1..MAX_SIMULTANEOUS_AUDIO_CHANNELS) {
            "GuideCast supports one to $MAX_SIMULTANEOUS_AUDIO_CHANNELS channels"
        }
        require(maxListeners in 1..50) { "GuideCast supports one to fifty listeners" }
        require(listenerBufferFrames > 0) { "Listener buffer must be positive" }
    }

    /**
     * Starts a new stream generation and returns the handle that its server/producers must retain.
     * All prior subscriptions are synchronously cancelled before this method returns.
     */
    fun configure(descriptors: List<AudioChannelDescriptor>): StreamSession {
        validateDescriptors(descriptors)

        synchronized(lock) {
            val previous = activeState
            previous.active = false
            previous.subscribersByChannel.values
                .flatMap { it.values }
                .forEach { mailbox -> mailbox.cancelForReplacement() }
            previous.subscribersByChannel.clear()
            previous.monitorSubscribersByChannel.values
                .flatMap { it.values }
                .forEach { mailbox -> mailbox.cancelForReplacement() }
            previous.monitorSubscribersByChannel.clear()
            previous.clearPublishTargets()

            nextGeneration += 1L
            val replacement = StreamSessionState(nextGeneration, descriptors)
            activeState = replacement
            mutableChannels.value = replacement.descriptors
            publishSnapshotLocked(replacement)
            return StreamSession(this, replacement)
        }
    }

    fun currentSession(): StreamSession = synchronized(lock) {
        StreamSession(this, activeState)
    }

    internal fun subscribe(
        session: StreamSession,
        channelId: String,
    ): AudioListenerSubscription = synchronized(lock) {
        val state = session.state
        if (!state.active || state !== activeState) throw StreamSessionSupersededException()
        val descriptor = state.descriptorById[channelId] ?: error("Unknown stream channel")
        enforceAdmissionPolicyLocked(state, channelId)

        val id = nextSubscriptionId.incrementAndGet()
        val mailbox = ListenerMailbox(listenerBufferFrames)
        state.subscribersByChannel.getOrPut(channelId, ::mutableMapOf)[id] = mailbox
        state.refreshRemotePublishTargets(channelId)
        publishSnapshotLocked(state)

        AudioListenerSubscription(
            descriptor = descriptor,
            frames = mailbox.frames,
            onWebSocketDelivery = { frame ->
                recordWebSocketDelivery(state, channelId, id, mailbox, frame)
            },
            onClose = { removeSubscription(state, channelId, id, mailbox) },
        )
    }

    internal fun subscribeLocalMonitor(
        session: StreamSession,
        channelId: String,
    ): LocalAudioMonitorSubscription = synchronized(lock) {
        val state = session.state
        if (!state.active || state !== activeState) throw StreamSessionSupersededException()
        val descriptor = state.descriptorById[channelId] ?: error("Unknown stream channel")
        val id = nextSubscriptionId.incrementAndGet()
        val mailbox = ListenerMailbox(listenerBufferFrames)
        state.monitorSubscribersByChannel.getOrPut(channelId, ::mutableMapOf)[id] = mailbox
        state.refreshMonitorPublishTargets(channelId)
        LocalAudioMonitorSubscription(
            descriptor = descriptor,
            frames = mailbox.frames,
            onClose = { removeLocalMonitorSubscription(state, channelId, id, mailbox) },
        )
    }

    /**
     * Compatibility subscription for callers that have not retained a session yet.
     * Descriptor lookup and admission are still atomic, but long-lived work should use
     * [StreamSession.subscribe] so supersession is explicit.
     */
    fun subscribe(channelId: String): AudioListenerSubscription = synchronized(lock) {
        subscribe(StreamSession(this, activeState), channelId)
    }

    internal fun tryPublish(
        session: StreamSession,
        channelId: String,
        frame: PcmAudioFrame,
    ): StreamPublishResult = publishFromState(session.state, channelId, frame)

    /** Compatibility publisher. New broadcast producers should use [StreamSession.tryPublish]. */
    fun tryPublish(channelId: String, frame: PcmAudioFrame): Boolean {
        val state = synchronized(lock) { activeState }
        return publishFromState(state, channelId, frame).accepted
    }

    /** Compatibility publisher. New broadcast producers should use [StreamSession.publish]. */
    fun publish(channelId: String, frame: PcmAudioFrame) {
        val state = synchronized(lock) { activeState }
        val result = publishFromState(state, channelId, frame)
        check(result.status != StreamPublishStatus.UNKNOWN_CHANNEL) { "Unknown stream channel" }
    }

    internal fun snapshotFor(session: StreamSession): AudioStreamObservabilitySnapshot =
        synchronized(lock) {
            val snapshot = session.state.toSnapshot()
            if (session.state === activeState) publishSnapshotLocked(snapshot)
            snapshot
        }

    internal fun isActive(session: StreamSession): Boolean = synchronized(lock) {
        session.state.active && session.state === activeState
    }

    internal fun close(session: StreamSession) {
        synchronized(lock) {
            val state = session.state
            if (!state.active || state !== activeState) return
            state.active = false
            state.subscribersByChannel.values
                .flatMap { it.values }
                .forEach { mailbox -> mailbox.cancelForReplacement() }
            state.subscribersByChannel.clear()
            state.monitorSubscribersByChannel.values
                .flatMap { it.values }
                .forEach { mailbox -> mailbox.cancelForReplacement() }
            state.monitorSubscribersByChannel.clear()
            state.clearPublishTargets()
            mutableChannels.value = emptyList()
            publishSnapshotLocked(state)
        }
    }

    private fun publishFromState(
        state: StreamSessionState,
        channelId: String,
        frame: PcmAudioFrame,
    ): StreamPublishResult {
        val targets = synchronized(lock) {
            if (!state.active || state !== activeState) {
                return StreamPublishResult(StreamPublishStatus.STALE_SESSION)
            }
            if (channelId !in state.descriptorById) {
                return StreamPublishResult(StreamPublishStatus.UNKNOWN_CHANNEL)
            }
            state.publishTargetsByChannel.getValue(channelId)
        }

        var delivered = 0
        var dropped = 0
        targets.remote.forEach { target ->
            val result = target.offer(frame)
            if (result.delivered) delivered += 1
            if (result.dropped) dropped += 1
        }
        // Local monitor backpressure is deliberately isolated from listener/drop telemetry.
        // A slow phone speaker must not make the operator believe a hotspot listener is dropping.
        targets.localMonitors.forEach { target -> target.offer(frame) }
        if (dropped > 0) {
            synchronized(lock) {
                state.droppedFramesByChannel[channelId] =
                    state.droppedFramesByChannel.getValue(channelId) + dropped
            }
        }
        return StreamPublishResult(
            status = StreamPublishStatus.PUBLISHED,
            targetListeners = targets.remote.size,
            deliveredFrames = delivered,
            droppedFrames = dropped,
        )
    }

    private fun recordWebSocketDelivery(
        state: StreamSessionState,
        channelId: String,
        subscriptionId: Long,
        mailbox: ListenerMailbox,
        frame: PcmAudioFrame,
    ) {
        synchronized(lock) {
            if (!state.active || state !== activeState) return
            if (state.subscribersByChannel[channelId]?.get(subscriptionId) !== mailbox) return
            state.webSocketDeliveredFramesByChannel[channelId] =
                state.webSocketDeliveredFramesByChannel.getValue(channelId) + 1L
            frame.utteranceSequence?.let { sequence ->
                val previous = state.lastWebSocketDeliveredSequenceByChannel[channelId]
                if (previous == null || sequence > previous) {
                    state.lastWebSocketDeliveredSequenceByChannel[channelId] = sequence
                }
            }
        }
    }

    /**
     * Fair admission policy: reserve one slot for every other active channel that currently has
     * zero listeners. A busy channel may consume unreserved capacity, but can never take the last
     * slot needed for each other configured language's first listener. Once all languages are
     * represented, any channel may use remaining capacity; a later disconnect immediately restores
     * that language's one-slot reservation.
     */
    private fun enforceAdmissionPolicyLocked(state: StreamSessionState, channelId: String) {
        val counts = state.listenerCounts()
        val total = counts.values.sum()
        if (total >= maxListeners) throw ListenerLimitExceededException()

        val unrepresentedOtherChannels = state.descriptors.count { descriptor ->
            descriptor.id != channelId && counts.getValue(descriptor.id) == 0
        }
        val remainingAfterAdmission = maxListeners - total - 1
        if (remainingAfterAdmission < unrepresentedOtherChannels) {
            throw ListenerLimitExceededException(
                "Listener capacity is reserved for unrepresented language channels",
            )
        }
    }

    private fun removeSubscription(
        state: StreamSessionState,
        channelId: String,
        subscriptionId: Long,
        mailbox: ListenerMailbox,
    ) {
        val removed = synchronized(lock) {
            val subscribers = state.subscribersByChannel[channelId]
            val didRemove = subscribers?.remove(subscriptionId) === mailbox
            if (subscribers?.isEmpty() == true) state.subscribersByChannel.remove(channelId)
            if (didRemove) state.refreshRemotePublishTargets(channelId)
            if (didRemove && state.active && state === activeState) publishSnapshotLocked(state)
            didRemove
        }
        if (removed) mailbox.close()
    }

    private fun removeLocalMonitorSubscription(
        state: StreamSessionState,
        channelId: String,
        subscriptionId: Long,
        mailbox: ListenerMailbox,
    ) {
        val removed = synchronized(lock) {
            val subscribers = state.monitorSubscribersByChannel[channelId]
            val didRemove = subscribers?.remove(subscriptionId) === mailbox
            if (subscribers?.isEmpty() == true) {
                state.monitorSubscribersByChannel.remove(channelId)
            }
            if (didRemove) state.refreshMonitorPublishTargets(channelId)
            didRemove
        }
        if (removed) mailbox.close()
    }

    private fun validateDescriptors(descriptors: List<AudioChannelDescriptor>) {
        require(descriptors.isNotEmpty()) { "At least one stream channel is required" }
        require(descriptors.size <= maxChannels) { "Too many stream channels" }
        require(descriptors.count { it.id != "source" } <= MAX_SIMULTANEOUS_TRANSLATED_CHANNELS) {
            "At most five translated channels plus the original source are supported"
        }
        require(descriptors.size <= maxListeners) {
            "Listener capacity must provide at least one reserved slot per stream channel"
        }
        require(descriptors.map { it.id }.toSet().size == descriptors.size) {
            "Channel ids must be unique"
        }
    }

    private fun publishSnapshotLocked(state: StreamSessionState) {
        publishSnapshotLocked(state.toSnapshot())
    }

    private fun publishSnapshotLocked(snapshot: AudioStreamObservabilitySnapshot) {
        mutableObservability.value = snapshot
        mutableListenerCount.value = snapshot.totalListeners
        mutableListenerCounts.value = snapshot.listenersByChannel
    }
}

internal data class PublishTargets(
    val remote: List<ListenerMailbox>,
    val localMonitors: List<ListenerMailbox>,
) {
    companion object {
        val EMPTY = PublishTargets(emptyList(), emptyList())
    }
}

private fun StreamSessionState.refreshRemotePublishTargets(channelId: String) {
    val current = publishTargetsByChannel.getValue(channelId)
    publishTargetsByChannel[channelId] = current.copy(
        remote = subscribersByChannel[channelId].frozenMailboxSnapshot(),
    )
}

private fun StreamSessionState.refreshMonitorPublishTargets(channelId: String) {
    val current = publishTargetsByChannel.getValue(channelId)
    publishTargetsByChannel[channelId] = current.copy(
        localMonitors = monitorSubscribersByChannel[channelId].frozenMailboxSnapshot(),
    )
}

private fun StreamSessionState.clearPublishTargets() {
    descriptors.forEach { descriptor ->
        publishTargetsByChannel[descriptor.id] = PublishTargets.EMPTY
    }
}

private fun Map<Long, ListenerMailbox>?.frozenMailboxSnapshot(): List<ListenerMailbox> =
    if (isNullOrEmpty()) emptyList() else Collections.unmodifiableList(ArrayList(values))

private fun StreamSessionState.listenerCounts(): LinkedHashMap<String, Int> =
    descriptors.associateTo(linkedMapOf()) { descriptor ->
        descriptor.id to subscribersByChannel[descriptor.id].orEmpty().size
    }

private fun StreamSessionState.toSnapshot(): AudioStreamObservabilitySnapshot {
    val counts = listenerCounts()
    val drops = descriptors.associateTo(linkedMapOf()) { descriptor ->
        descriptor.id to droppedFramesByChannel.getValue(descriptor.id)
    }
    val webSocketDeliveries = descriptors.associateTo(linkedMapOf()) { descriptor ->
        descriptor.id to webSocketDeliveredFramesByChannel.getValue(descriptor.id)
    }
    val lastDeliveredSequences = descriptors.associateTo(linkedMapOf()) { descriptor ->
        descriptor.id to lastWebSocketDeliveredSequenceByChannel[descriptor.id]
    }
    return AudioStreamObservabilitySnapshot(
        generation = generation,
        isActive = active,
        totalListeners = counts.values.sum(),
        listenersByChannel = Collections.unmodifiableMap(counts),
        droppedFrames = drops.values.sum(),
        droppedFramesByChannel = Collections.unmodifiableMap(drops),
        webSocketDeliveredFrames = webSocketDeliveries.values.sum(),
        webSocketDeliveredFramesByChannel = Collections.unmodifiableMap(webSocketDeliveries),
        lastWebSocketDeliveredSequenceByChannel =
            Collections.unmodifiableMap(lastDeliveredSequences),
    )
}

internal data class MailboxOfferResult(
    val delivered: Boolean,
    val dropped: Boolean,
)

internal class ListenerMailbox(capacity: Int) {
    private val queue = Channel<PcmAudioFrame>(capacity = capacity)
    val frames: ReceiveChannel<PcmAudioFrame> = queue

    @Synchronized
    fun offer(frame: PcmAudioFrame): MailboxOfferResult {
        val initial = queue.trySend(frame)
        if (initial.isSuccess) return MailboxOfferResult(delivered = true, dropped = false)
        if (initial.isClosed) return MailboxOfferResult(delivered = false, dropped = false)

        val discarded = queue.tryReceive().isSuccess
        val replacement = queue.trySend(frame)
        return MailboxOfferResult(
            delivered = replacement.isSuccess,
            dropped = discarded,
        )
    }

    @Synchronized
    fun close() {
        queue.close()
    }

    @Synchronized
    fun cancelForReplacement() {
        queue.cancel(CancellationException("Stream session replaced"))
    }
}

class AudioListenerSubscription internal constructor(
    /** Descriptor captured atomically with this exact generation's subscription. */
    val descriptor: AudioChannelDescriptor,
    val frames: ReceiveChannel<PcmAudioFrame>,
    private val onWebSocketDelivery: (PcmAudioFrame) -> Unit,
    private val onClose: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    /** Call exactly once, immediately after the corresponding WebSocket send succeeds. */
    fun recordWebSocketDelivery(frame: PcmAudioFrame) {
        if (!closed.get()) onWebSocketDelivery(frame)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) onClose()
    }
}

class LocalAudioMonitorSubscription internal constructor(
    val descriptor: AudioChannelDescriptor,
    val frames: ReceiveChannel<PcmAudioFrame>,
    private val onClose: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) onClose()
    }
}

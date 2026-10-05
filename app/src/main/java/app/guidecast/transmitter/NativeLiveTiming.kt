package app.guidecast.transmitter

import org.json.JSONObject
import kotlin.math.abs

/** Fixed-size connection timing. Input energy and device playhead are not human speech/hearing. */
internal class NativeLiveTiming(val nativeAudioSessionId: Long? = null,
    private val nowNanos: () -> Long = System::nanoTime) {
    val stages = NativeAudioStageTrace(nowNanos)
    private val times = linkedMapOf<String, Long?>().apply {
        listOf("ready_ns", "first_input_ns", "last_input_ns", "last_input_signal_ns",
            "first_sent_ns", "last_sent_ns", "last_sent_signal_ns", "first_provider_audio_ns",
            "first_publication_ns", "first_local_write_ns", "first_local_signal_head_ns",
            "first_websocket_send_ns").forEach { put(it, null) }
    }
    private val counts = linkedMapOf("input_bytes" to 0L, "sent_bytes" to 0L,
        "provider_audio_bytes" to 0L, "published_bytes" to 0L, "local_written_samples" to 0L,
        "websocket_sent_bytes" to 0L, "max_callback_ns" to 0L, "interruptions" to 0L)
    private var closed = false
    private fun first(key: String, now: Long) { if (times[key] == null) times[key] = now }
    @Synchronized fun ready() { if (!closed) first("ready_ns", nowNanos()) }
    @Synchronized fun input(size: Int, signal: Boolean) {
        if (closed || size <= 0) return
        stages.input(size, signal)
        val now = nowNanos(); first("first_input_ns", now); times["last_input_ns"] = now
        if (signal) times["last_input_signal_ns"] = now
        counts["input_bytes"] = counts.getValue("input_bytes") + size
    }
    @Synchronized fun sent(size: Int, signal: Boolean) {
        if (closed || size <= 0) return
        stages.sent(size)
        val now = nowNanos(); first("first_sent_ns", now); times["last_sent_ns"] = now
        if (signal) times["last_sent_signal_ns"] = now
        counts["sent_bytes"] = counts.getValue("sent_bytes") + size
    }
    @Synchronized fun providerAudio(size: Int) {
        if (closed || size <= 0) return
        first("first_provider_audio_ns", nowNanos())
        counts["provider_audio_bytes"] = counts.getValue("provider_audio_bytes") + size
    }
    @Synchronized fun callbackFinished(elapsedNanos: Long) {
        if (!closed) counts["max_callback_ns"] = maxOf(counts.getValue("max_callback_ns"), elapsedNanos)
    }
    @Synchronized fun interrupted() {
        if (!closed) counts["interruptions"] = counts.getValue("interruptions") + 1
    }
    @Synchronized fun published(size: Int) {
        if (closed || size <= 0) return
        first("first_publication_ns", nowNanos()); counts["published_bytes"] = counts.getValue("published_bytes") + size
    }
    @Synchronized fun localWrite(samples: Int) {
        if (closed || samples <= 0) return
        first("first_local_write_ns", nowNanos())
        counts["local_written_samples"] = counts.getValue("local_written_samples") + samples
    }
    @Synchronized fun localSignalHead() { if (!closed) first("first_local_signal_head_ns", nowNanos()) }
    @Synchronized fun webSocketSent(size: Int) {
        if (closed || size <= 0) return
        first("first_websocket_send_ns", nowNanos())
        counts["websocket_sent_bytes"] = counts.getValue("websocket_sent_bytes") + size
    }
    @Synchronized fun snapshot(close: Boolean = false): JSONObject {
        if (close) closed = true
        return JSONObject().put("clock", "SYSTEM_NANO_MONOTONIC")
            .put("native_generation", nativeAudioSessionId ?: JSONObject.NULL)
            .put("scope", "CONNECTION_FIRST_OUTPUT_NOT_PER_UTTERANCE_OR_ACOUSTIC").apply {
                times.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
                counts.forEach { (key, value) -> put(key, value) }
            }
    }
}

/** Tracks only accepted sample positions. A flush invalidates the previous playhead goal. */
internal class NativeAudioHeadProgress {
    private var epoch: Long? = null
    private var acceptedSamples = 0L
    private var goal: Long? = null
    private var previousHead: Long? = null
    private var wraps = 0L
    @Synchronized fun accepted(bytes: ByteArray, offset: Int, count: Int, currentEpoch: Long) {
        require(offset >= 0 && count >= 0 && offset % 2 == 0 && count % 2 == 0 && offset <= bytes.size - count)
        if (epoch != currentEpoch) {
            epoch = currentEpoch; acceptedSamples = 0; goal = null; previousHead = null; wraps = 0
        }
        if (goal == null) for (n in offset until offset + count step 2) {
            val sample = ((bytes[n].toInt() and 255) or (bytes[n + 1].toInt() shl 8)).toShort().toInt()
            if (abs(sample) >= 328) { goal = acceptedSamples + (n - offset) / 2 + 1; break }
        }
        acceptedSamples += count / 2
    }
    @Synchronized fun hasGoal(currentEpoch: Long): Boolean = epoch == currentEpoch && goal != null
    @Synchronized fun reached(rawHead: Int, currentEpoch: Long): Boolean {
        if (!hasGoal(currentEpoch)) return false
        return (samplePosition(rawHead, currentEpoch) ?: return false) >= requireNotNull(goal)
    }
    @Synchronized fun samplePosition(rawHead: Int, currentEpoch: Long): Long? {
        if (epoch != currentEpoch) return null
        val head = rawHead.toLong() and 0xffffffffL
        val previous = previousHead
        // Polling is frequent: a modular jump exceeding half the counter range is an
        // outdated observation or an unannounced reset, not evidence of playback.
        if (previous != null) {
            val forward = (head - previous) and 0xffffffffL
            if (forward > 0x7fffffffL) return null
            if (head < previous) wraps += 1L shl 32
        }
        previousHead = head
        return head + wraps
    }
}

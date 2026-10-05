package app.guidecast.transmitter

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Bounded, numbers-only diagnostics. Provider turns are NOT microphone utterance IDs.
 * Explicit byte regions and turn associations are reserved for a controlled synthetic fixture.
 * Real Gemini has no authoritative input/turn association: cross-boundary latency stays null.
 * Output progress intentionally outlives the connection-first NativeLiveTiming receipt.
 */
internal class NativeAudioStageTrace(private val nowNanos: () -> Long = System::nanoTime) {
    private data class Region(val id: Long, val from: Long, val until: Long,
        var inputBytes: Long = 0, var preparedBytes: Long = 0, var sentBytes: Long = 0,
        var firstInput: Long? = null, var lastSignal: Long? = null, var firstReady: Long? = null, var lastReady: Long? = null,
        var lastSent: Long? = null)
    private data class Turn(val id: Long, var region: Long? = null,
        var providerBytes: Long = 0, var publishedBytes: Long = 0, var dequeuedBytes: Long = 0,
        var writtenBytes: Long = 0, var provider: Long? = null, var publication: Long? = null,
        var dequeue: Long? = null, var write: Long? = null, var signalHead: Long? = null,
        var finalHead: Long? = null, var signalGoal: Long? = null, var finalGoal: Long? = null,
        var epoch: Long? = null, var completed: Boolean = false, var invalidated: Boolean = false,
        var queueMax: Long? = null, var queueTotal: Long = 0, var queuePackets: Long = 0)
    private val regions = linkedMapOf<Long, Region>()
    private val turns = linkedMapOf<Long, Turn>()
    private var inputOffset = 0L
    private var preparedOffset = 0L
    private var sentOffset = 0L
    private var inputMappingValid = true
    private var trackEpoch: Long? = null
    private var trackSamples = 0L
    private var omittedTurns = 0L
    private var closed = false
    private fun turn(id: Long?): Turn? {
        if (id == null || id <= 0 || closed) return null
        turns[id]?.let { return it }
        if (turns.size >= 32) { omittedTurns++; return null }
        return Turn(id).also { turns[id] = it }
    }
    @Synchronized fun defineFixtureRegion(id: Long, fromByte: Long, untilByte: Long) {
        require(!closed && id > 0 && regions.size < 32 && id !in regions)
        require(fromByte >= inputOffset && untilByte > fromByte && fromByte % 2 == 0L && untilByte % 2 == 0L)
        require(regions.values.none { fromByte < it.until && untilByte > it.from })
        regions[id] = Region(id, fromByte, untilByte)
    }
    @Synchronized fun bindFixtureTurn(turnId: Long, regionId: Long) {
        require(regionId in regions)
        val row = requireNotNull(turn(turnId))
        require(row.region == null && row.provider == null && turns.values.none { it.region == regionId })
        row.region = regionId
    }
    private fun overlap(region: Region, offset: Long, size: Int) =
        (minOf(region.until, offset + size) - maxOf(region.from, offset)).coerceAtLeast(0)
    @Synchronized fun input(size: Int, signal: Boolean) {
        if (closed || size <= 0) return
        val now = nowNanos()
        for (r in regions.values) {
            val n = overlap(r, inputOffset, size); r.inputBytes += n
            if (n > 0 && r.firstInput == null) r.firstInput = now
            if (n > 0 && n != size.toLong()) inputMappingValid = false // no sub-frame energy attribution
            if (n > 0 && signal) r.lastSignal = now
        }
        inputOffset += size
    }
    @Synchronized fun packetReady(size: Int) {
        if (closed || size <= 0) return
        val now = nowNanos()
        for (r in regions.values) if (overlap(r, preparedOffset, size) > 0) {
            r.preparedBytes += overlap(r, preparedOffset, size)
            if (r.firstReady == null) r.firstReady = now
            r.lastReady = now
        }
        preparedOffset += size
    }
    @Synchronized fun sent(size: Int) {
        if (closed || size <= 0) return
        for (r in regions.values) if (overlap(r, sentOffset, size) > 0) {
            r.sentBytes += overlap(r, sentOffset, size); r.lastSent = nowNanos()
        }
        sentOffset += size
    }
    @Synchronized fun inputLoss() { if (!closed) inputMappingValid = false }
    @Synchronized fun provider(id: Long, size: Int, completed: Boolean, interrupted: Boolean) {
        val r = turn(id) ?: return
        if (size > 0) { if (r.provider == null) r.provider = nowNanos(); r.providerBytes += size }
        r.completed = r.completed || completed
        r.invalidated = r.invalidated || interrupted
    }
    @Synchronized fun published(id: Long?, size: Int, publicationStarted: Long = nowNanos()) {
        val r = turn(id) ?: return
        if (r.publication == null) r.publication = publicationStarted
        r.publishedBytes += size
    }
    @Synchronized fun dequeued(id: Long?, size: Int, enqueuedAt: Long?) {
        val r = turn(id) ?: return
        val now = nowNanos()
        if (r.dequeue == null) r.dequeue = now
        r.dequeuedBytes += size
        if (enqueuedAt != null && now >= enqueuedAt) {
            val wait = now - enqueuedAt
            r.queueMax = maxOf(r.queueMax ?: 0, wait); r.queueTotal += wait; r.queuePackets++
        }
    }
    @Synchronized fun written(id: Long?, bytes: ByteArray, offset: Int, count: Int, epoch: Long) {
        if (closed) return
        require(offset >= 0 && count >= 0 && offset % 2 == 0 && count % 2 == 0 && offset <= bytes.size - count)
        if (trackEpoch != epoch) {
            turns.values.filter { it.finalHead == null && it.writtenBytes > 0 }.forEach { it.invalidated = true }
            trackEpoch = epoch; trackSamples = 0
        }
        val r = turn(id)
        if (r != null) {
            if (r.write == null) r.write = nowNanos()
            if (r.epoch != null && r.epoch != epoch) r.invalidated = true
            r.epoch = epoch
            if (r.signalGoal == null) for (n in offset until offset + count step 2) {
                val sample = ((bytes[n].toInt() and 255) or (bytes[n + 1].toInt() shl 8)).toShort().toInt()
                if (abs(sample) >= 328) { r.signalGoal = trackSamples + (n - offset) / 2 + 1; break }
            }
            r.writtenBytes += count; r.finalGoal = trackSamples + count / 2
        }
        trackSamples += count / 2
    }
    @Synchronized fun head(samples: Long?, epoch: Long) {
        if (closed) return
        if (trackEpoch != epoch) {
            turns.values.filter { it.finalHead == null }.forEach { it.invalidated = true }
            return
        }
        if (samples == null) return
        val now = nowNanos()
        for (r in turns.values) if (!r.invalidated && r.epoch == epoch) {
            if (r.signalHead == null && r.signalGoal?.let { samples >= it } == true) r.signalHead = now
            if (r.finalHead == null && r.completed && r.providerBytes > 0 &&
                r.providerBytes == r.publishedBytes && r.publishedBytes == r.dequeuedBytes &&
                r.dequeuedBytes == r.writtenBytes && r.finalGoal?.let { samples >= it } == true) r.finalHead = now
        }
    }
    @Synchronized fun snapshot(close: Boolean = false): JSONObject {
        if (close) closed = true
        fun difference(a: Long?, b: Long?): Any = if (a != null && b != null && b >= a) b - a else JSONObject.NULL
        val rows = JSONArray()
        for (r in turns.values) {
            val input = regions[r.region]
            val matched = inputMappingValid && input != null && input.inputBytes == input.until - input.from &&
                input.preparedBytes == input.inputBytes && input.sentBytes == input.inputBytes && !r.invalidated
            rows.put(JSONObject().put("output_turn", r.id).put("input_region", r.region ?: JSONObject.NULL)
                .put("association", if (matched) "EXPLICIT_SYNTHETIC_FIXTURE" else "UNKNOWN")
                .put("input_region_from_byte", input?.from ?: JSONObject.NULL)
                .put("input_region_until_byte", input?.until ?: JSONObject.NULL)
                .put("input_region_bytes", input?.inputBytes ?: JSONObject.NULL)
                .put("first_input_ns", input?.firstInput ?: JSONObject.NULL)
                .put("input_last_signal_ns", input?.lastSignal ?: JSONObject.NULL)
                .put("first_packet_ready_ns", input?.firstReady ?: JSONObject.NULL)
                .put("last_packet_ready_ns", input?.lastReady ?: JSONObject.NULL)
                .put("last_packet_sent_ns", input?.lastSent ?: JSONObject.NULL)
                .put("first_provider_pcm_ns", r.provider ?: JSONObject.NULL)
                .put("first_publication_ns", r.publication ?: JSONObject.NULL)
                .put("first_dequeue_ns", r.dequeue ?: JSONObject.NULL).put("first_write_ns", r.write ?: JSONObject.NULL)
                .put("first_signal_playhead_ns", r.signalHead ?: JSONObject.NULL)
                .put("final_sample_playhead_ns", r.finalHead ?: JSONObject.NULL)
                .put("signal_sample_goal", r.signalGoal ?: JSONObject.NULL).put("final_sample_goal", r.finalGoal ?: JSONObject.NULL)
                .put("input_signal_to_provider_ns", if (matched) difference(input?.lastSignal, r.provider) else JSONObject.NULL)
                .put("input_signal_to_playhead_ns", if (matched) difference(input?.lastSignal, r.signalHead) else JSONObject.NULL)
                .put("input_signal_to_packet_ready_ns", if (matched) difference(input?.lastSignal, input?.lastReady) else JSONObject.NULL)
                .put("first_input_to_packet_ready_ns", if (matched) difference(input?.firstInput, input?.firstReady) else JSONObject.NULL)
                .put("last_sent_to_provider_ns", if (matched) difference(input?.lastSent, r.provider) else JSONObject.NULL)
                .put("packet_ready_to_sent_ns", if (matched) difference(input?.lastReady, input?.lastSent) else JSONObject.NULL)
                .put("provider_to_publication_ns", difference(r.provider, r.publication))
                .put("max_queue_residence_ns", r.queueMax ?: JSONObject.NULL)
                .put("queue_residence_total_ns", r.queueTotal).put("queue_packets", r.queuePackets)
                .put("provider_bytes", r.providerBytes).put("published_bytes", r.publishedBytes)
                .put("dequeued_bytes", r.dequeuedBytes).put("written_bytes", r.writtenBytes)
                .put("provider_complete", r.completed).put("invalidated", r.invalidated))
        }
        return JSONObject().put("clock", "SYSTEM_NANO_MONOTONIC").put("scope", "BOUNDED_OUTPUT_TURNS_NOT_SPEECH_OR_HEARING")
            .put("input_mapping_valid", inputMappingValid).put("input_bytes", inputOffset)
            .put("prepared_bytes", preparedOffset).put("sent_bytes", sentOffset)
            .put("retained_turn_limit", 32).put("omitted_turn_events", omittedTurns).put("closed", closed).put("turns", rows)
    }
}

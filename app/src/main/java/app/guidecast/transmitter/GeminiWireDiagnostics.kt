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
    private val shapeCounts = LongArray(GeminiMessageShapeField.entries.size)
    private var setupBuilds = 0L
    private var setupModel: String? = null
    private var setupInputTranscription: Boolean? = null
    private var setupOutputTranscription: Boolean? = null

    @Synchronized fun observeSetup(model: String, inputTranscription: Boolean, outputTranscription: Boolean) {
        setupBuilds++
        setupModel = model.takeIf { it == GEMINI_LIVE_AGENT || it == GEMINI_LIVE_TRANSLATE } ?: "CUSTOM"
        setupInputTranscription = inputTranscription; setupOutputTranscription = outputTranscription
    }

    /** Only fixed paths, types and counts survive this call; payloads and field names do not. */
    @Synchronized fun observeMessageShape(root: JSONObject) {
        fun add(field: GeminiMessageShapeField, amount: Int = 1) { shapeCounts[field.ordinal] += amount.toLong() }
        add(GeminiMessageShapeField.MESSAGES)
        add(GeminiMessageShapeField.UNKNOWN_ROOT, root.length() - ROOT_FIELDS.count(root::has))
        val camel = root.optJSONObject("serverContent")
        val snake = root.optJSONObject("server_content")
        if (camel != null) add(GeminiMessageShapeField.CAMEL_CONTENT)
        if (snake != null) add(GeminiMessageShapeField.SNAKE_CONTENT)
        if (camel?.optJSONObject("inputTranscription") != null) add(GeminiMessageShapeField.CAMEL_INPUT)
        val output = camel?.optJSONObject("outputTranscription")
        if (output != null) {
            add(GeminiMessageShapeField.CAMEL_OUTPUT)
            val text = output.opt("text")
            if (text is String) {
                add(GeminiMessageShapeField.OUTPUT_TEXT_CHARS, text.length)
                if (text.isNotBlank()) add(GeminiMessageShapeField.OUTPUT_TEXT_NONBLANK)
            } else if (output.has("text")) add(GeminiMessageShapeField.OUTPUT_TEXT_WRONG_TYPE)
        }
        if (camel?.optJSONObject("output_transcription") != null || snake?.optJSONObject("output_transcription") != null ||
            snake?.optJSONObject("outputTranscription") != null) add(GeminiMessageShapeField.SNAKE_OUTPUT)
        for (content in listOfNotNull(camel, snake)) {
            add(GeminiMessageShapeField.UNKNOWN_CONTENT, content.length() - CONTENT_FIELDS.count(content::has))
            if (content.optBoolean("generationComplete", false) || content.optBoolean("generation_complete", false)) add(GeminiMessageShapeField.GENERATION_COMPLETE)
            if (content.optBoolean("turnComplete", false) || content.optBoolean("turn_complete", false)) add(GeminiMessageShapeField.TURN_COMPLETE)
            if ((content.optJSONObject("inputTranscription") ?: content.optJSONObject("input_transcription"))?.optBoolean("finished", false) == true)
                add(GeminiMessageShapeField.INPUT_FINISHED)
            if ((content.optJSONObject("outputTranscription") ?: content.optJSONObject("output_transcription"))?.optBoolean("finished", false) == true)
                add(GeminiMessageShapeField.OUTPUT_FINISHED)
            val parts = (content.optJSONObject("modelTurn") ?: content.optJSONObject("model_turn"))?.optJSONArray("parts") ?: continue
            add(GeminiMessageShapeField.PARTS_SKIPPED, maxOf(0, parts.length() - 64))
            for (index in 0 until minOf(parts.length(), 64)) {
                val part = parts.optJSONObject(index) ?: continue
                if (part.optJSONObject("inlineData") != null || part.optJSONObject("inline_data") != null) add(GeminiMessageShapeField.INLINE_PARTS)
                val text = part.opt("text")
                if (text is String && text.isNotBlank()) {
                    if (part.optBoolean("thought", false)) {
                        add(GeminiMessageShapeField.THOUGHT_PARTS); add(GeminiMessageShapeField.THOUGHT_CHARS, text.length)
                    } else { add(GeminiMessageShapeField.MODEL_TEXT_PARTS); add(GeminiMessageShapeField.MODEL_TEXT_CHARS, text.length) }
                }
            }
        }
    }

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
            .put("safe_failure", failure?.name ?: JSONObject.NULL)
            .put("setup_config", JSONObject().put("builds", setupBuilds).put("model", setupModel ?: JSONObject.NULL)
                .put("input", setupInputTranscription ?: JSONObject.NULL).put("output", setupOutputTranscription ?: JSONObject.NULL))
            .put("message_shape", JSONObject().also { shape ->
                GeminiMessageShapeField.entries.forEach { field -> shape.put(field.name.lowercase(java.util.Locale.ROOT), shapeCounts[field.ordinal]) }
            }).also { root ->
                for (kind in GeminiWireMark.entries) root.put(kind.name.lowercase(), JSONObject()
                    .put("count", counts[kind.ordinal]).put("first_ns", first[kind.ordinal] ?: JSONObject.NULL)
                    .put("last_ns", last[kind.ordinal] ?: JSONObject.NULL))
            }
    }
}

private enum class GeminiMessageShapeField {
    MESSAGES, CAMEL_CONTENT, SNAKE_CONTENT, CAMEL_INPUT, CAMEL_OUTPUT, SNAKE_OUTPUT,
    OUTPUT_TEXT_CHARS, OUTPUT_TEXT_NONBLANK, OUTPUT_TEXT_WRONG_TYPE,
    MODEL_TEXT_PARTS, MODEL_TEXT_CHARS, THOUGHT_PARTS, THOUGHT_CHARS, INLINE_PARTS,
    PARTS_SKIPPED, UNKNOWN_ROOT, UNKNOWN_CONTENT, GENERATION_COMPLETE, TURN_COMPLETE,
    INPUT_FINISHED, OUTPUT_FINISHED,
}
private val ROOT_FIELDS = setOf("serverContent", "server_content", "setupComplete", "setup_complete", "usageMetadata", "usage_metadata",
    "error", "toolCall", "tool_call", "toolCallCancellation", "tool_call_cancellation", "goAway", "go_away",
    "sessionResumptionUpdate", "session_resumption_update", "voiceActivity", "voice_activity", "voiceActivityDetectionSignal", "voice_activity_detection_signal")
private val CONTENT_FIELDS = setOf("inputTranscription", "input_transcription", "outputTranscription", "output_transcription",
    "interimInputTranscription", "interim_input_transcription", "modelTurn", "model_turn", "turnComplete", "turn_complete",
    "generationComplete", "generation_complete", "interrupted", "waitingForInput", "waiting_for_input", "interactionStatus", "interaction_status",
    "groundingMetadata", "grounding_metadata", "urlContextMetadata", "url_context_metadata", "speechState", "speech_state")

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
    fun finishAndFlush(): ByteArray {
        check(!ended)
        ended = true
        return pending.toByteArray().also { pending.reset() }
    }
    fun finish(): Int { ended = true; return pending.size().also { pending.reset() } }
}

package app.guidecast.transmitter

import org.json.JSONArray
import org.json.JSONObject

/** Bounded provider observations only; no transcript, input coverage or completion acknowledgement. */
internal class GeminiProviderObservations(private val nowNanos: () -> Long = System::nanoTime) {
    private data class Entry(val sequence: Long, val receivedNs: Long, val fields: Map<String, Any>)
    private val retained = ArrayDeque<Entry>()
    private var messages = 0L
    private var observations = 0L
    private var dropped = 0L
    private var voiceStart = 0L
    private var voiceEnd = 0L
    private var interim = 0L
    private var interimNonblank = 0L
    private var inputNonblank = 0L
    private var idle = 0L
    private var inProgress = 0L

    @Synchronized fun observe(root: JSONObject) {
        messages++
        val content = root.optJSONObject("serverContent")
        val fields = linkedMapOf<String, Any>()
        if (root.has("voiceActivity")) {
            val voice = root.optJSONObject("voiceActivity")
            val kind = when (voice?.opt("type")) {
                "ACTIVITY_START" -> "ACTIVITY_START".also { voiceStart++ }
                "ACTIVITY_END" -> "ACTIVITY_END".also { voiceEnd++ }
                "TYPE_UNSPECIFIED" -> "TYPE_UNSPECIFIED"
                else -> if (voice == null) "INVALID_OBJECT" else "UNKNOWN"
            }
            fields["voice_type"] = kind
            if (voice?.has("audioOffset") == true) {
                val offset = boundedDurationNanos(voice.opt("audioOffset"))
                fields["voice_audio_offset_ns"] = offset ?: JSONObject.NULL
                fields["voice_offset_valid"] = offset != null
            }
        }
        fun transcription(name: String, label: String) {
            if (content?.has(name) != true) return
            val value = content.optJSONObject(name)
            fields["${label}_object_valid"] = value != null
            if (value == null) return
            val text = value.opt("text")
            val nonblank = text is String && text.isNotBlank()
            fields["${label}_text_nonblank"] = nonblank
            fields["${label}_text_chars"] = if (text is String) minOf(text.length, 8_000) else 0
            fields["${label}_text_type"] = when { text is String -> "STRING"; !value.has("text") -> "ABSENT"; else -> "INVALID" }
            if (label == "interim_input") { interim++; if (nonblank) interimNonblank++ }
            if (label == "input" && nonblank) inputNonblank++
            // Optional wire field: presence/strict value is observed, not treated as an ACK.
            if (value.has("finished")) fields["${label}_finished"] = booleanState(value.opt("finished"))
        }
        transcription("inputTranscription", "input")
        transcription("interimInputTranscription", "interim_input")
        transcription("outputTranscription", "output")
        if (content?.has("interactionStatus") == true) {
            fields["interaction_status"] = when (content.opt("interactionStatus")) {
                "IDLE" -> "IDLE".also { idle++ }
                "IN_PROGRESS" -> "IN_PROGRESS".also { inProgress++ }
                "REQUIRES_ACTION" -> "REQUIRES_ACTION"
                "INTERACTION_STATUS_UNSPECIFIED" -> "INTERACTION_STATUS_UNSPECIFIED"
                else -> "UNKNOWN"
            }
        }
        for ((name, label) in listOf("turnComplete" to "turn_complete", "generationComplete" to "generation_complete",
            "waitingForInput" to "waiting_for_input", "interrupted" to "interrupted")) {
            if (content?.has(name) == true) fields[label] = booleanState(content.opt(name))
        }
        if (fields.isEmpty()) return
        observations++
        if (retained.size == 16) { retained.removeFirst(); dropped++ }
        retained.addLast(Entry(messages, nowNanos(), fields.toMap()))
    }

    @Synchronized fun snapshot(): JSONObject = JSONObject()
        .put("semantics", "PROVIDER_OBSERVATIONS_NOT_INPUT_ACK")
        .put("receive_clock", "PROCESS_MONOTONIC_NANOS")
        .put("audio_offset_clock", "PROVIDER_AUDIO_STREAM_RELATIVE_NANOS")
        .put("messages", messages).put("observations", observations).put("dropped", dropped)
        .put("voice_start_count", voiceStart).put("voice_end_count", voiceEnd)
        .put("interim_input_count", interim).put("interim_nonblank_count", interimNonblank)
        .put("input_nonblank_count", inputNonblank).put("idle_count", idle).put("in_progress_count", inProgress)
        .put("recent", JSONArray().also { entries -> retained.forEach { entry ->
            entries.put(JSONObject().put("message_sequence", entry.sequence).put("received_ns", entry.receivedNs)
                .also { value -> entry.fields.forEach { (key, field) -> value.put(key, field) } })
        } })

    private fun booleanState(value: Any?): String = when (value) { true -> "TRUE"; false -> "FALSE"; else -> "INVALID" }
    private fun boundedDurationNanos(value: Any?): Long? {
        if (value !is String || value.length > 18) return null
        val match = DURATION.matchEntire(value) ?: return null
        val seconds = match.groupValues[1].toLongOrNull()?.takeIf { it <= 86_400L } ?: return null
        val nanos = match.groupValues[2].padEnd(9, '0').toLongOrNull() ?: return null
        return (seconds * 1_000_000_000L + nanos).takeIf { it <= 86_400_000_000_000L }
    }
    private companion object { val DURATION = Regex("([0-9]{1,5})(?:\\.([0-9]{1,9}))?s") }
}

/** One bounded record per retained observation, emitted once when a connection closes. */
internal fun geminiProviderObservationRecords(snapshot: JSONObject, sessionId: String, connectionIndex: Int,
    target: String, settingsRevision: Long, drainOrdinal: Long): List<JSONObject> {
    fun bind(record: JSONObject, kind: String): JSONObject = record.put("kind", kind)
        .put("session_id", sessionId).put("connection_index", connectionIndex).put("target", target)
        .put("settings_revision", settingsRevision).put("drain", drainOrdinal)
    val summary = JSONObject(snapshot.toString())
    val recent = summary.remove("recent") as? JSONArray ?: JSONArray()
    return buildList {
        add(bind(summary, "SUMMARY"))
        for (index in 0 until minOf(recent.length(), 16)) {
            add(bind(JSONObject(recent.getJSONObject(index).toString()), "OBSERVATION"))
        }
    }
}

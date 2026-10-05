package app.guidecast.transmitter

import app.guidecast.core.translation.TranslationStyle
import app.guidecast.core.translation.interpretationInstructions
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

internal interface OpenAiAudioWire {
    suspend fun connect(model: String, key: String, authorized: () -> Boolean, block: suspend (RealtimeSocket) -> Unit)
}

/** One selected model, fixed TLS destination, no redirects, retries or audio replay. */
internal class KtorOpenAiAudioWire : OpenAiAudioWire {
    override suspend fun connect(model: String, key: String, authorized: () -> Boolean, block: suspend (RealtimeSocket) -> Unit) {
        val url = openAiRealtimeUrl(model)
        check(authorized())
        val client = HttpClient(CIO) { install(WebSockets) { maxFrameSize = 262_144 }; followRedirects = false; expectSuccess = true }
        try {
            val session = try { withTimeout(6_000) {
                check(authorized())
                client.webSocketSession(urlString = url) { header(HttpHeaders.Authorization, "Bearer $key") }
            } } catch (failure: ResponseException) { throw OnlineProviderFailure(onlineHttpFailure(failure.response.status.value)) }
            try {
                block(object : RealtimeSocket {
                    override suspend fun send(text: String) {
                        currentCoroutineContext().ensureActive(); check(authorized()); session.send(Frame.Text(text))
                    }
                    override suspend fun receive(): String {
                        currentCoroutineContext().ensureActive(); check(authorized())
                        val frame = session.incoming.receive(); check(authorized())
                        check(frame is Frame.Text) { "Unexpected audio frame" }
                        return frame.readText().also { require(it.length <= 262_144) }
                    }
                })
            } finally { session.cancel() }
        } finally { client.close() }
    }
}

internal fun openAiAudioSetup(model: String, source: String, target: String, domain: String = "",
    tone: TranslationStyle = TranslationStyle.CONVERSATIONAL, interpreterInstructions: String = "", references: String = ""): String {
    require(model in OPENAI_REALTIME_MODELS)
    require(source.matches(Regex("[a-z]{2}")))
    val destination = geminiLiveTarget(target)
    require(validInterpreterDomain(domain))
    val format = JSONObject().put("type", "audio/pcm").put("rate", 24_000)
    val input = JSONObject().put("format", format)
        .put("transcription", JSONObject().put("model", "gpt-realtime-whisper").put("language", source))
        // VAD ends input turns. The client makes exactly one explicitly correlated response per item.
        .put("turn_detection", JSONObject().put("type", "server_vad").put("silence_duration_ms", 500)
            .put("create_response", false).put("interrupt_response", false))
    val instructions = nativeInterpreterInstructions(destination, tone, domain, interpreterInstructions, references)
    return JSONObject().put("type", "session.update").put("session", JSONObject()
        .put("type", "realtime").put("model", model).put("output_modalities", JSONArray().put("audio"))
        .put("audio", JSONObject().put("input", input).put("output", JSONObject().put("format", format).put("voice", "marin")))
        .put("instructions", instructions).put("tools", JSONArray()).put("max_output_tokens", 2_048)).toString()
}

/** Stateful linear 16→24 kHz conversion: phase and previous sample survive capture boundaries. */
internal class LivePcm16To24 {
    private var previous: Int? = null
    private var inputIndex = 0L
    private var nextOutputThirds = 0L
    fun convert(bytes: ByteArray): ByteArray {
        require(bytes.size % 2 == 0 && bytes.size <= 32_000)
        val output = java.io.ByteArrayOutputStream(bytes.size * 3 / 2 + 4)
        fun write(sample: Int) { output.write(sample and 255); output.write((sample shr 8) and 255) }
        for (offset in bytes.indices step 2) {
            val current = ((bytes[offset].toInt() and 255) or (bytes[offset + 1].toInt() shl 8)).toShort().toInt()
            val before = previous
            if (before == null) { write(current); nextOutputThirds = 2 }
            else while (nextOutputThirds <= inputIndex * 3) {
                val fraction = (nextOutputThirds - (inputIndex - 1) * 3).toInt()
                write(before + (current - before) * fraction / 3)
                nextOutputThirds += 2
            }
            previous = current; inputIndex++
        }
        return output.toByteArray()
    }
}

/** Raw provider numbers remain nullable. Cache is already part of input; never add it again. */
internal data class OpenAiAudioUsage(val input: Long?, val output: Long?, val total: Long?,
    val inputAudio: Long?, val inputText: Long?, val cached: Long?, val outputAudio: Long?, val outputText: Long?,
    val cachedAudio: Long? = null, val cachedText: Long? = null, val durationSeconds: Double? = null) {
    fun countsOnly(): JSONObject = JSONObject().put("input_tokens", input ?: JSONObject.NULL)
        .put("output_tokens", output ?: JSONObject.NULL).put("total_tokens", total ?: JSONObject.NULL)
        .put("input_audio_tokens", inputAudio ?: JSONObject.NULL).put("input_text_tokens", inputText ?: JSONObject.NULL)
        .put("cached_input_tokens", cached ?: JSONObject.NULL).put("output_audio_tokens", outputAudio ?: JSONObject.NULL)
        .put("output_text_tokens", outputText ?: JSONObject.NULL)
        .put("cached_audio_tokens", cachedAudio ?: JSONObject.NULL).put("cached_text_tokens", cachedText ?: JSONObject.NULL)
        .put("measurement_type", if (durationSeconds != null) "DURATION" else "TOKENS")
        .put("duration_seconds", durationSeconds ?: JSONObject.NULL)
}
internal fun openAiAudioUsage(raw: JSONObject?): OpenAiAudioUsage? {
    if (raw == null) return null
    fun number(row: JSONObject?, field: String): Long? {
        val value = row?.opt(field) ?: return null
        if (value !is Number || value.toDouble() < 0 || value.toDouble() != value.toLong().toDouble()) return null
        return value.toLong()
    }
    val duration = if (raw.optString("type") == "duration") (raw.opt("seconds") as? Number)?.toDouble()
        ?.takeIf { it.isFinite() && it >= 0 } else null
    if (raw.optString("type") == "duration") return duration?.let {
        OpenAiAudioUsage(null, null, null, null, null, null, null, null, durationSeconds = it)
    }
    val input = raw.optJSONObject("input_token_details"); val output = raw.optJSONObject("output_token_details")
    val result = OpenAiAudioUsage(number(raw, "input_tokens"), number(raw, "output_tokens"), number(raw, "total_tokens"),
        number(input, "audio_tokens"), number(input, "text_tokens"), number(input, "cached_tokens"),
        number(output, "audio_tokens"), number(output, "text_tokens"),
        number(input?.optJSONObject("cached_tokens_details"), "audio_tokens"),
        number(input?.optJSONObject("cached_tokens_details"), "text_tokens"))
    return result.takeIf { listOf(it.input, it.output, it.total, it.inputAudio, it.inputText, it.cached,
        it.outputAudio, it.outputText, it.cachedAudio, it.cachedText).any { count -> count != null } }
}

internal enum class NativeAudioUsageKind { RESPONSE, TRANSCRIPTION }
internal class NativeAudioOverload : IllegalStateException("Native audio backlog exceeded")
internal class NativeAudioIdentityUncertain : IllegalStateException("Expired audio identity cannot be safely admitted")

/** Recent exact tombstones and a fixed-size older history prevent replay after turn pruning.
 * A filter hit outside the exact window never authorizes another paid response.
 */
internal class NativeAudioExpiredIdentities {
    private val recent = NativeAudioRecentIdentities()
    private val bits = java.util.BitSet(524288)
    private fun positions(id: String): IntArray {
        val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        return IntArray(4) { i -> ((bytes[i * 3].toInt() and 255) shl 16 or
            ((bytes[i * 3 + 1].toInt() and 255) shl 8) or (bytes[i * 3 + 2].toInt() and 255)) and 524287 }
    }
    fun add(id: String) { recent.add(id); positions(id).forEach(bits::set) }
    fun recent(id: String) = id in recent
    fun possible(id: String) = positions(id).all(bits::get)
}

/** Transcript snapshots use input item identity; a late source transcript cannot relabel a newer turn. */
internal data class OpenAiAudioEvent(val inputId: String, val source: String = "", val translation: String = "",
    val finished: Boolean = false, val interrupted: Boolean = false, val audio: List<ByteArray> = emptyList(),
    val responseId: String? = null, val status: String? = null, val usage: OpenAiAudioUsage? = null,
    val usageKind: NativeAudioUsageKind = NativeAudioUsageKind.RESPONSE, val inputSequence: Long? = null,
    val sourceFinal: Boolean = false, val sourceFailed: Boolean = false, val sourceExpired: Boolean = false,
    val translationFinal: Boolean = false)

/** One audio upload and one response per committed input. Bounded tombstones prevent paid replay. */
internal class OpenAiAudioTransport(private val wire: OpenAiAudioWire = KtorOpenAiAudioWire(),
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L }) {
    suspend fun run(key: String, model: String, source: String, target: String, input: Flow<ByteArray>,
        authorized: () -> Boolean, onReady: () -> Unit, onEvent: suspend (OpenAiAudioEvent) -> Unit,
        domain: String = "", tone: TranslationStyle = TranslationStyle.CONVERSATIONAL,
        onRequest: (inputId: String, sequence: Long, sent: Boolean) -> Unit = { _, _, _ -> },
        durationLimitMillis: Long? = null, interpreterInstructions: String = "", references: String = ""): Unit = nativeLiveSessionWindow(durationLimitMillis) {
        val setup = openAiAudioSetup(model, source, target, domain, tone, interpreterInstructions, references)
        check(authorized())
        wire.connect(model, key, authorized) { socket ->
            suspend fun send(event: JSONObject) { check(authorized()); socket.send(event.toString()) }
            socket.send(setup)
            withTimeout(6_000) {
                var acknowledged = false
                repeat(32) {
                    if (!acknowledged) {
                        check(authorized()); val raw = socket.receive(); requireBoundedJson(raw, 262_144)
                        val event = JSONObject(raw)
                        when (event.optString("type")) {
                            "error" -> throw onlineProviderFailure(event)
                            "session.created" -> Unit
                            "session.updated" -> {
                                event.optJSONObject("session")?.let { session ->
                                    check(session.getString("model") == model) { "Audio model acknowledgement mismatch" }
                                    check(session.getJSONArray("output_modalities").toString() == "[\"audio\"]") { "Audio modality acknowledgement mismatch" }
                                }
                                acknowledged = true
                            }
                            else -> error("Unexpected audio setup event")
                        }
                    }
                }
                check(authorized() && acknowledged) { "Audio setup incomplete" }
            }
            onReady()
            coroutineScope {
                val sender = launch {
                    val converter = LivePcm16To24()
                    val pending = java.io.ByteArrayOutputStream(4_800)
                    input.collect { frame ->
                        check(authorized())
                        val bytes = converter.convert(frame); var offset = 0
                        while (offset < bytes.size) {
                            val count = minOf(4_800 - pending.size(), bytes.size - offset)
                            pending.write(bytes, offset, count); offset += count
                            if (pending.size() == 4_800) {
                                send(JSONObject().put("type", "input_audio_buffer.append")
                                    .put("audio", Base64.getEncoder().encodeToString(pending.toByteArray())))
                                pending.reset()
                            }
                        }
                    }
                    // End/stop never commits a partial old capture or resends it on a new session.
                    error("Audio capture ended; restart required")
                }
                val revocation = launch { while (isActive) { if (!authorized()) error("Audio consent revoked"); delay(25) } }
                data class Turn(val sequence: Long, var source: String = "", var translation: String = "", var response: String? = null,
                    var retired: Boolean = false, var finished: Boolean = false, var committed: Boolean = false,
                    var sourceFinal: Boolean = false, var translationFinal: Boolean = false, var status: String = "queued",
                    var sourceFailed: Boolean = false, var sourceExpired: Boolean = false, var finishedAt: Long? = null)
                val turns = linkedMapOf<String, Turn>()
                // Finished audio and pending transcription have separate, finite lifetimes.
                val lateSources = linkedMapOf<String, Turn>()
                val responses = mutableMapOf<String, String>()
                val pending = java.util.ArrayDeque<String>()
                var activeInput: String? = null
                val seenEvents = NativeAudioRecentIdentities()
                val expiredInputs = NativeAudioExpiredIdentities()
                val expiredResponses = NativeAudioExpiredIdentities()
                var nextSequence = 1L
                fun identity(event: JSONObject, field: String) = event.getString(field).also {
                    require(it.length in 1..200 && it.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid audio identity" }
                }
                suspend fun emit(id: String, row: Turn, audio: List<ByteArray> = emptyList(), interrupted: Boolean = row.retired,
                    status: String? = row.status, usage: OpenAiAudioUsage? = null,
                    usageKind: NativeAudioUsageKind = NativeAudioUsageKind.RESPONSE) {
                    check(authorized())
                    onEvent(OpenAiAudioEvent(id, row.source, row.translation, row.finished, interrupted, audio, row.response,
                        status, usage, usageKind, row.sequence, row.sourceFinal, row.sourceFailed, row.sourceExpired, row.translationFinal))
                }
                suspend fun expireSource(id: String, row: Turn) {
                    row.sourceExpired = true
                    emit(id, row, usageKind = NativeAudioUsageKind.TRANSCRIPTION)
                }
                suspend fun expireSources() {
                    val now = monotonicMillis()
                    turns.forEach { (id, row) ->
                        if (!row.sourceFinal && !row.sourceFailed && !row.sourceExpired &&
                            row.finishedAt?.let { now - it >= 120_000L } == true) expireSource(id, row)
                    }
                    val expired = lateSources.filterValues { row ->
                        row.finishedAt?.let { now - it >= 120_000L } == true
                    }.keys.toList()
                    expired.forEach { id -> expireSource(id, requireNotNull(lateSources.remove(id))) }
                    while (lateSources.size > 512) {
                        val id = lateSources.keys.first()
                        expireSource(id, requireNotNull(lateSources.remove(id)))
                    }
                }
                suspend fun turn(id: String): Turn {
                    turns[id]?.let { return it }
                    if (expiredInputs.possible(id)) throw NativeAudioIdentityUncertain()
                    if (turns.size >= 128) {
                        val oldest = turns.entries.firstOrNull { it.value.finished && it.key != activeInput && it.key !in pending }
                            ?: throw NativeAudioOverload()
                        if (!oldest.value.sourceFinal && !oldest.value.sourceFailed && !oldest.value.sourceExpired)
                            lateSources[oldest.key] = oldest.value
                        turns.remove(oldest.key); expiredInputs.add(oldest.key)
                        oldest.value.response?.let { responses.remove(it); expiredResponses.add(it) }
                    }
                    expireSources()
                    return Turn(nextSequence++).also { turns[id] = it }
                }
                suspend fun startNext() {
                    if (activeInput != null) return
                    val id = pending.pollFirst() ?: return
                    val row = turns.getValue(id)
                    activeInput = id
                    row.status = "generating"
                    emit(id, row, status = "generating")
                    onRequest(id, row.sequence, false)
                    send(JSONObject().put("type", "response.create").put("response", JSONObject()
                        .put("conversation", "none").put("output_modalities", JSONArray().put("audio"))
                        .put("input", JSONArray().put(JSONObject().put("type", "item_reference").put("id", id)))
                        .put("metadata", JSONObject().put("input_item_id", id)).put("max_output_tokens", 2_048)))
                    onRequest(id, row.sequence, true)
                }
                var incoming = async { socket.receive() }
                try {
                    while (currentCoroutineContext().isActive) {
                        check(authorized()); expireSources()
                        // Waiting for metadata expiry never cancels a partially received provider event.
                        val raw = withTimeoutOrNull(1_000L) { incoming.await() } ?: continue
                        incoming = async { socket.receive() }
                        requireBoundedJson(raw, 262_144); check(authorized())
                        val event = JSONObject(raw)
                        if (event.has("event_id")) {
                            if (!seenEvents.add(identity(event, "event_id"))) continue
                        }
                        when (event.getString("type")) {
                            "error" -> throw onlineProviderFailure(event)
                            "session.updated" -> error("Unexpected audio reconfiguration")
                            // Ordinary source speech is never an instruction to discard earlier translation.
                            "input_audio_buffer.speech_started" -> Unit
                            "input_audio_buffer.committed" -> {
                                val id = identity(event, "item_id")
                                if (expiredInputs.recent(id)) continue
                                val row = turn(id)
                                if (!row.committed) {
                                    row.committed = true
                                    if (pending.size >= 8) throw NativeAudioOverload()
                                    pending.addLast(id)
                                    emit(id, row, status = "queued")
                                    startNext()
                                }
                            }
                            "conversation.item.input_audio_transcription.delta", "conversation.item.input_audio_transcription.completed" -> {
                                val id = identity(event, "item_id")
                                val row = turns[id] ?: lateSources[id] ?: if (expiredInputs.possible(id)) continue else turn(id)
                                if (!row.sourceFinal && !row.sourceFailed && !row.sourceExpired) {
                                    row.sourceFinal = event.getString("type").endsWith("completed")
                                    row.source = if (row.sourceFinal) event.getString("transcript") else row.source + event.getString("delta")
                                    require(row.source.length <= 8_000)
                                    emit(id, row, usage = if (row.sourceFinal) openAiAudioUsage(event.optJSONObject("usage")) else null,
                                        usageKind = NativeAudioUsageKind.TRANSCRIPTION)
                                    if (row.sourceFinal) lateSources.remove(id)
                                }
                            }
                            "conversation.item.input_audio_transcription.failed" -> {
                                val id = identity(event, "item_id")
                                val row = turns[id] ?: lateSources[id] ?: if (expiredInputs.possible(id)) continue else turn(id)
                                if (!row.sourceFinal && !row.sourceFailed && !row.sourceExpired) {
                                    row.sourceFailed = true
                                    emit(id, row, usageKind = NativeAudioUsageKind.TRANSCRIPTION)
                                    lateSources.remove(id)
                                }
                            }
                            "response.created" -> {
                                val response = event.getJSONObject("response")
                                val rid = identity(response, "id"); val id = identity(response.getJSONObject("metadata"), "input_item_id")
                                val row = turns[id] ?: if (expiredInputs.possible(id)) continue else error("Unrequested audio response")
                                if (row.response == rid) continue
                                check(row.committed && activeInput == id && row.response == null && rid !in responses)
                                row.response = rid; responses[rid] = id
                                if (row.retired) send(JSONObject().put("type", "response.cancel").put("response_id", rid))
                            }
                            "response.output_audio.delta", "response.output_audio_transcript.delta", "response.output_audio_transcript.done" -> {
                                val rid = identity(event, "response_id")
                                val id = responses[rid] ?: if (expiredResponses.possible(rid)) continue else error("Uncorrelated audio response")
                                val row = turns.getValue(id)
                                if (!row.retired && !row.finished) {
                                    when (event.getString("type")) {
                                        "response.output_audio.delta" -> {
                                            val bytes = Base64.getDecoder().decode(event.getString("delta"))
                                            require(bytes.isNotEmpty() && bytes.size % 2 == 0 && bytes.size <= 96_000)
                                            emit(id, row, (bytes.indices step 4_800).map { bytes.copyOfRange(it, minOf(it + 4_800, bytes.size)) })
                                        }
                                        else -> {
                                            if (!row.translationFinal) {
                                                row.translationFinal = event.getString("type").endsWith(".done")
                                                row.translation = if (row.translationFinal) event.getString("transcript") else row.translation + event.getString("delta")
                                                require(row.translation.length <= 8_000); emit(id, row)
                                            }
                                        }
                                    }
                                }
                            }
                            "response.done" -> {
                                val response = event.getJSONObject("response")
                                val rid = identity(response, "id")
                                val id = responses[rid] ?: if (expiredResponses.possible(rid)) continue else error("Uncorrelated audio completion")
                                val row = turns.getValue(id)
                                if (!row.finished) {
                                    row.finished = true
                                    row.finishedAt = monotonicMillis()
                                    val status = response.getString("status")
                                    require(status in setOf("completed", "cancelled", "failed", "incomplete"))
                                    row.status = status
                                    if (status != "completed") row.retired = true
                                    emit(id, row, interrupted = row.retired, status = status, usage = openAiAudioUsage(response.optJSONObject("usage")))
                                    check(activeInput == id) { "Audio scheduling mismatch" }
                                    activeInput = null
                                    startNext()
                                }
                            }
                            "response.output_item.added" -> {
                                check(event.getJSONObject("item").getString("type") == "message") { "Unexpected audio tool response" }
                            }
                        }
                    }
                } finally { incoming.cancel(); sender.cancel(); revocation.cancel() }
            }
        }
    }
}

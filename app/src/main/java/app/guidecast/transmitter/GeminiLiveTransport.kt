package app.guidecast.transmitter

import app.guidecast.core.translation.TranslationStyle
import app.guidecast.core.audio.pcmS16LeSignalStats
import app.guidecast.core.translation.interpretationInstructions

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

internal const val GEMINI_LIVE_TRANSLATE = "gemini-3.5-live-translate-preview"
internal const val GEMINI_LIVE_AGENT = "gemini-3.8-live"
internal fun geminiLiveTarget(tag: String): String = when (tag.lowercase()) {
    "zh-tw", "zh-hant" -> "zh-Hant"
    "zh-cn", "zh-hans", "zh" -> "zh-Hans"
    else -> tag.substringBefore('-').lowercase().also { require(it.matches(Regex("[a-z]{2,3}"))) }
}
internal fun geminiLiveSetup(model: String, target: String, domainPrompt: String = "", tone: TranslationStyle = TranslationStyle.CONVERSATIONAL,
    interpreterInstructions: String = "", references: String = ""): String {
    require(model in setOf(GEMINI_LIVE_TRANSLATE, GEMINI_LIVE_AGENT))
    require(validInterpreterDomain(domainPrompt))
    require(model != GEMINI_LIVE_TRANSLATE || domainPrompt.isEmpty()) { "Live Translate does not support domain instructions" }
    require(model != GEMINI_LIVE_TRANSLATE || (interpreterInstructions.isEmpty() && references.isEmpty())) {
        "Live Translate does not support reference text or instructions"
    }
    val generation = JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
    val setup = JSONObject().put("model", "models/$model").put("generationConfig", generation)
    if (model == GEMINI_LIVE_TRANSLATE) {
        generation.put("inputAudioTranscription", JSONObject()).put("outputAudioTranscription", JSONObject())
            .put("translationConfig", JSONObject().put("targetLanguageCode", geminiLiveTarget(target)).put("echoTargetLanguage", false))
    } else {
        generation.put("maxOutputTokens", 2_048)
        setup.put("inputAudioTranscription", JSONObject()).put("outputAudioTranscription", JSONObject())
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                nativeInterpreterInstructions(geminiLiveTarget(target), tone, domainPrompt, interpreterInstructions, references)))))
    }
    return JSONObject().put("setup", setup).toString()
}

internal interface GeminiLiveWire {
    suspend fun connect(key: String, authorized: () -> Boolean, block: suspend (RealtimeSocket) -> Unit)
}
/** No URL credentials, redirects, SDK telemetry or logging. Socket lifetime belongs to the caller. */
internal class KtorGeminiLiveWire : GeminiLiveWire {
    override suspend fun connect(key: String, authorized: () -> Boolean, block: suspend (RealtimeSocket) -> Unit) {
        check(authorized())
        val client = HttpClient(CIO) { install(WebSockets) { maxFrameSize = 262_144 }; followRedirects = false; expectSuccess = true }
        try {
            val socket = try { withTimeout(6_000) {
                check(authorized())
                client.webSocketSession(urlString = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent") {
                    header("x-goog-api-key", key)
                }
            } } catch (failure: ResponseException) {
                // Provider bodies may echo private data. Discard the body and original cause here.
                throw OnlineProviderFailure(onlineHttpFailure(failure.response.status.value))
            }
            try {
                block(object : RealtimeSocket {
                    override suspend fun send(text: String) { currentCoroutineContext().ensureActive(); check(authorized()); socket.send(Frame.Text(text)) }
                    override suspend fun receive(): String {
                        currentCoroutineContext().ensureActive(); check(authorized())
                        val frame = socket.incoming.receive(); check(authorized())
                        return when (frame) {
                            is Frame.Text -> frame.readText()
                            is Frame.Binary -> frame.data.toString(Charsets.UTF_8)
                            else -> error("Live connection closed")
                        }.also { require(it.length <= 262_144) }
                    }
                })
            } finally { socket.cancel() }
        } finally { client.close() }
    }
}
internal data class GeminiLiveEvent(val source: String?, val translation: String?, val finished: Boolean,
    val interrupted: Boolean, val audio: List<ByteArray>, val usage: JSONObject?, val timingTurn: Long? = null,
    val serverContent: Boolean = false)
internal fun parseGeminiLiveEvent(raw: String): GeminiLiveEvent {
    requireBoundedJson(raw, maximumChars = 262_144)
    val root = JSONObject(raw)
    if (root.has("error")) throw onlineProviderFailure(root)
    check(!root.has("toolCall") && !root.has("goAway")) { "Live session requires restart" }
    val content = root.optJSONObject("serverContent")
    val interrupted = content?.optBoolean("interrupted", false) == true
    val parts = content?.optJSONObject("modelTurn")?.optJSONArray("parts")
    val audio = mutableListOf<ByteArray>()
    if (!interrupted && parts != null) for (index in 0 until parts.length()) {
        parts.getJSONObject(index).optJSONObject("inlineData")?.let { blob ->
            require(blob.getString("mimeType") in setOf("audio/pcm;rate=24000", "audio/pcm;rate=24000;channels=1"))
            val bytes = Base64.getDecoder().decode(blob.getString("data"))
            require(bytes.isNotEmpty() && bytes.size <= 96_000 && bytes.size % 2 == 0)
            audio += bytes
        }
    }
    fun transcript(field: String) = content?.optJSONObject(field)?.optString("text")?.also { require(it.length <= 8_000) }
    return GeminiLiveEvent(transcript("inputTranscription"), transcript("outputTranscription"),
        content?.optBoolean("turnComplete", false) == true, interrupted, audio, root.optJSONObject("usageMetadata"),
        serverContent = content != null)
}

/** Receive and capture proceed concurrently. No resumption/replay can duplicate audible output. */
internal class GeminiLiveTransport(private val wire: GeminiLiveWire = KtorGeminiLiveWire(),
    private val nowNanos: () -> Long = System::nanoTime) {
    suspend fun run(key: String, model: String, target: String, input: Flow<ByteArray>, authorized: () -> Boolean,
        onReady: () -> Unit, onEvent: suspend (GeminiLiveEvent) -> Unit, domainPrompt: String = "", tone: TranslationStyle = TranslationStyle.CONVERSATIONAL,
        durationLimitMillis: Long? = null, onAudioSent: (Int) -> Unit = {},
        timing: NativeLiveTiming? = null, diagnostics: GeminiWireDiagnostics? = null,
        interpreterInstructions: String = "", references: String = ""): Unit = nativeLiveSessionWindow(durationLimitMillis) {
        try {
        check(authorized())
        wire.connect(key, authorized) { socket ->
            socket.send(geminiLiveSetup(model, target, domainPrompt, tone, interpreterInstructions, references))
            withTimeout(6_000) {
                val rawAck = socket.receive()
                requireBoundedJson(rawAck, maximumChars = 262_144)
                val ack = JSONObject(rawAck)
                if (ack.has("error")) throw onlineProviderFailure(ack)
                check(authorized() && ack.has("setupComplete")) { "Live setup incomplete" }
            }
            diagnostics?.mark(GeminiWireMark.SETUP_COMPLETE)
            timing?.ready(); onReady()
            coroutineScope {
                val providerFailed = java.util.concurrent.atomic.AtomicBoolean(false)
                val sender = launch {
                    val packetizer = GeminiPcmPacketizer()
                    var end = GeminiInputEnd.FAILURE
                    try {
                    input.collect { frame ->
                        check(authorized())
                        for (packet in packetizer.accept(frame)) {
                                timing?.stages?.packetReady(packet.size)
                                socket.send(JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject()
                                    .put("mimeType", "audio/pcm;rate=16000")
                                    .put("data", Base64.getEncoder().encodeToString(packet)))).toString())
                                timing?.sent(packet.size, packet.pcmS16LeSignalStats().let { it.rms >= 0.002f || it.peak >= 0.01f })
                                diagnostics?.sent(packet)
                                onAudioSent(packet.size)
                        }
                    }
                    end = GeminiInputEnd.NORMAL_EOS
                    error("Live capture ended; restart required")
                    } catch (cancelled: CancellationException) {
                        end = if (providerFailed.get()) GeminiInputEnd.FAILURE else GeminiInputEnd.STOP
                        throw cancelled
                    } finally {
                        if (!authorized()) end = GeminiInputEnd.CONSENT_REVOKED
                        val tail = packetizer.finish()
                        if (tail > 0) timing?.stages?.inputLoss()
                        diagnostics?.endInput(tail, end)
                    }
                }
                val revocation = launch { while (isActive) { if (!authorized()) error("Live consent revoked"); delay(25) } }
                try {
                    var outputTurn = 1L
                    while (isActive) {
                        check(authorized())
                        val event = parseGeminiLiveEvent(socket.receive()).copy(timingTurn = outputTurn)
                        diagnostics?.received(event)
                        if (event.interrupted) timing?.interrupted()
                        timing?.providerAudio(event.audio.sumOf { it.size })
                        if (event.audio.isNotEmpty() || event.finished || event.interrupted)
                            timing?.stages?.provider(outputTurn, event.audio.sumOf { it.size }, event.finished, event.interrupted)
                        check(authorized())
                        val callbackStarted = nowNanos()
                        try { onEvent(event) } finally { timing?.callbackFinished(nowNanos() - callbackStarted) }
                        if (event.finished || event.interrupted) outputTurn++
                    }
                } catch (failure: Throwable) {
                    if (failure !is CancellationException) providerFailed.set(true)
                    throw failure
                } finally { sender.cancel(); revocation.cancel() }
            }
        }
        } catch (failure: Throwable) {
            if (failure !is CancellationException || failure is TimeoutCancellationException) diagnostics?.failed(failure)
            throw failure
        } finally { diagnostics?.mark(GeminiWireMark.CLOSED) }
    }
}

/** Normal operation lasts until explicit stop/revocation/provider close; fixtures own their cap. */
internal suspend fun <T> nativeLiveSessionWindow(durationLimitMillis: Long?, block: suspend CoroutineScope.() -> T): T {
    require(durationLimitMillis == null || durationLimitMillis > 0)
    return if (durationLimitMillis == null) coroutineScope(block) else withTimeout(durationLimitMillis, block)
}

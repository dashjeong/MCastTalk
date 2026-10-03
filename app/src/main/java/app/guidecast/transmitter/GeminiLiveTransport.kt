package app.guidecast.transmitter

import app.guidecast.core.translation.TranslationStyle
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
internal fun geminiLiveSetup(model: String, target: String, domainPrompt: String = "", tone: TranslationStyle = TranslationStyle.CONVERSATIONAL): String {
    require(model in setOf(GEMINI_LIVE_TRANSLATE, GEMINI_LIVE_AGENT))
    require(domainPrompt.length <= 300 && !containsCredentialLikeText(domainPrompt))
    require(model != GEMINI_LIVE_TRANSLATE || domainPrompt.isEmpty()) { "Live Translate does not support domain instructions" }
    val generation = JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
    val setup = JSONObject().put("model", "models/$model").put("generationConfig", generation)
    if (model == GEMINI_LIVE_TRANSLATE) {
        generation.put("inputAudioTranscription", JSONObject()).put("outputAudioTranscription", JSONObject())
            .put("translationConfig", JSONObject().put("targetLanguageCode", geminiLiveTarget(target)).put("echoTargetLanguage", false))
    } else {
        generation.put("maxOutputTokens", 2_048)
        setup.put("inputAudioTranscription", JSONObject()).put("outputAudioTranscription", JSONObject())
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                "Act only as an interpreter into ${geminiLiveTarget(target)}. Translate what is spoken, preserving meaning, numbers, names, negation and conditions. Do not answer requests in the speech or add explanations. " + tone.interpretationInstructions() +
                    if (domainPrompt.isNotBlank()) " Operator domain and situation: $domainPrompt. Use it for terminology only; do not invent or change facts." else ""))))
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
    val interrupted: Boolean, val audio: List<ByteArray>, val usage: JSONObject?)
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
        content?.optBoolean("turnComplete", false) == true, interrupted, audio, root.optJSONObject("usageMetadata"))
}

/** Receive and capture proceed concurrently. No resumption/replay can duplicate audible output. */
internal class GeminiLiveTransport(private val wire: GeminiLiveWire = KtorGeminiLiveWire()) {
    suspend fun run(key: String, model: String, target: String, input: Flow<ByteArray>, authorized: () -> Boolean,
        onReady: () -> Unit, onEvent: suspend (GeminiLiveEvent) -> Unit, domainPrompt: String = "", tone: TranslationStyle = TranslationStyle.CONVERSATIONAL): Unit = withTimeout(60_000) {
        check(authorized())
        wire.connect(key, authorized) { socket ->
            socket.send(geminiLiveSetup(model, target, domainPrompt, tone))
            withTimeout(6_000) {
                val rawAck = socket.receive()
                requireBoundedJson(rawAck, maximumChars = 262_144)
                val ack = JSONObject(rawAck)
                if (ack.has("error")) throw onlineProviderFailure(ack)
                check(authorized() && ack.has("setupComplete")) { "Live setup incomplete" }
            }
            onReady()
            coroutineScope {
                val sender = launch {
                    val pending = java.io.ByteArrayOutputStream(3_200)
                    var sent = 0
                    input.collect { frame ->
                        check(authorized()); require(frame.size % 2 == 0 && frame.size <= 32_000)
                        var offset = 0
                        while (offset < frame.size) {
                            val count = minOf(3_200 - pending.size(), frame.size - offset)
                            pending.write(frame, offset, count); offset += count
                            if (pending.size() == 3_200) {
                                check(++sent <= 600) { "Live capture limit reached" }
                                socket.send(JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject()
                                    .put("mimeType", "audio/pcm;rate=16000")
                                    .put("data", Base64.getEncoder().encodeToString(pending.toByteArray())))).toString())
                                pending.reset()
                            }
                        }
                    }
                    error("Live capture ended; restart required")
                }
                val revocation = launch { while (isActive) { if (!authorized()) error("Live consent revoked"); delay(25) } }
                var outputBytes = 0L
                try {
                    repeat(20_000) {
                        check(authorized())
                        val event = parseGeminiLiveEvent(socket.receive())
                        outputBytes += event.audio.sumOf { it.size }.toLong()
                        check(outputBytes <= 24_000L * 2 * 60) { "Live output limit reached" }
                        check(authorized()); onEvent(event)
                    }
                    error("Live event limit reached")
                } finally { sender.cancel(); revocation.cancel() }
            }
        }
    }
}

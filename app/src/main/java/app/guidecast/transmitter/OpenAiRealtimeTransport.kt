package app.guidecast.transmitter

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

internal interface RealtimeSocket {
    suspend fun send(text: String)
    suspend fun receive(): String
}
internal data class RealtimeTranslation(val text: String, val rawResponse: String)
internal val OPENAI_REALTIME_MODELS = setOf("gpt-realtime-2.1-mini", "gpt-realtime-2")
internal fun openAiRealtimeUrl(model: String): String {
    require(model in OPENAI_REALTIME_MODELS) { "Unsupported Realtime model" }
    return "wss://api.openai.com/v1/realtime?model=$model"
}
internal interface RealtimeWire {
    suspend fun exchange(key: String, authorized: () -> Boolean,
        conversation: suspend (RealtimeSocket) -> RealtimeTranslation): RealtimeTranslation
    suspend fun exchangeModel(model: String, key: String, authorized: () -> Boolean,
        conversation: suspend (RealtimeSocket) -> RealtimeTranslation): RealtimeTranslation {
        require(model == "gpt-realtime-2.1-mini") { "Selected model transport unavailable" }
        return exchange(key, authorized, conversation)
    }
}

/** Fixed TLS destination; one bounded request per socket, no redirects, logging, or reconnect billing. */
internal class KtorRealtimeWire : RealtimeWire {
    override suspend fun exchange(key: String, authorized: () -> Boolean,
        conversation: suspend (RealtimeSocket) -> RealtimeTranslation): RealtimeTranslation =
        exchangeModel("gpt-realtime-2.1-mini", key, authorized, conversation)
    override suspend fun exchangeModel(model: String, key: String, authorized: () -> Boolean,
        conversation: suspend (RealtimeSocket) -> RealtimeTranslation): RealtimeTranslation {
        val selectedUrl = openAiRealtimeUrl(model)
        check(authorized()) { "Realtime consent revoked" }
        val client = HttpClient(CIO) { install(WebSockets) { maxFrameSize = 65_536 }; followRedirects = false; expectSuccess = true }
        try {
            check(authorized()) { "Realtime consent revoked" }
            val session = try {
                client.webSocketSession(urlString = selectedUrl) { header(HttpHeaders.Authorization, "Bearer $key") }
            } catch (failure: ResponseException) {
                // Provider bodies may echo private data. Discard the body and original cause here.
                throw OnlineProviderFailure(onlineHttpFailure(failure.response.status.value))
            }
            try {
                return conversation(object : RealtimeSocket {
                    override suspend fun send(text: String) {
                        currentCoroutineContext().ensureActive()
                        check(authorized()) { "Realtime consent revoked" }
                        session.send(Frame.Text(text))
                    }
                    override suspend fun receive(): String {
                        currentCoroutineContext().ensureActive()
                        check(authorized()) { "Realtime consent revoked" }
                        val frame = session.incoming.receive()
                        check(frame is Frame.Text) { "Unexpected Realtime frame" }
                        check(authorized()) { "Realtime consent revoked" }
                        return frame.readText()
                    }
                })
            } finally { session.cancel() }
        } finally { client.close() }
    }
    companion object { const val URL = "wss://api.openai.com/v1/realtime?model=gpt-realtime-2.1-mini" }
}

internal class OpenAiRealtimeTransport(private val wire: RealtimeWire = KtorRealtimeWire()) {
    suspend fun translate(key: String, instructions: String, input: String,
        authorized: () -> Boolean): RealtimeTranslation =
        translateModel("gpt-realtime-2.1-mini", key, instructions, input, authorized)
    suspend fun translateModel(model: String, key: String, instructions: String, input: String,
        authorized: () -> Boolean): RealtimeTranslation = withTimeout(6_000L) {
        require(model in OPENAI_REALTIME_MODELS) { "Unsupported Realtime model" }
        check(authorized()) { "Realtime consent revoked" }
        wire.exchangeModel(model, key, authorized) { socket ->
            suspend fun send(event: JSONObject) {
                currentCoroutineContext().ensureActive()
                check(authorized()) { "Realtime consent revoked" }
                socket.send(event.toString())
            }
            send(JSONObject().put("type", "session.update").put("session", JSONObject()
                .put("type", "realtime").put("model", model)
                .put("output_modalities", JSONArray().put("text"))
                .put("instructions", instructions).put("tools", JSONArray())))
            var requested = false
            var responseId: String? = null
            var streamedCharacters = 0
            repeat(1_024) {
                currentCoroutineContext().ensureActive()
                check(authorized()) { "Realtime consent revoked" }
                val raw = socket.receive()
                requireBoundedJson(raw)
                check(authorized()) { "Realtime consent revoked" }
                val event = JSONObject(raw)
                when (event.getString("type")) {
                    "error" -> throw onlineProviderFailure(event)
                    "session.updated" -> {
                        check(!requested) { "Duplicate Realtime session update" }
                        requested = true
                        send(JSONObject().put("type", "conversation.item.create").put("item", JSONObject()
                            .put("type", "message").put("role", "user")
                            .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", input)))))
                        send(JSONObject().put("type", "response.create").put("response", JSONObject()
                            .put("output_modalities", JSONArray().put("text")).put("max_output_tokens", 2_048)))
                    }
                    "response.created" -> {
                        check(requested && responseId == null) { "Unexpected Realtime response" }
                        responseId = event.getJSONObject("response").getString("id")
                    }
                    "response.output_text.delta" -> {
                        check(responseId != null && event.getString("response_id") == responseId)
                        streamedCharacters += event.getString("delta").length
                        check(streamedCharacters <= 8_000) { "Realtime output exceeded limit" }
                    }
                    "response.done" -> {
                        val response = event.getJSONObject("response")
                        check(responseId != null && response.getString("id") == responseId)
                        check(response.getString("status") == "completed") { "Realtime response incomplete" }
                        val output = response.getJSONArray("output")
                        check(output.length() == 1)
                        val message = output.getJSONObject(0)
                        check(message.getString("type") == "message" && message.getString("role") == "assistant")
                        val content = message.getJSONArray("content")
                        check(content.length() == 1 && content.getJSONObject(0).getString("type") == "output_text")
                        val text = content.getJSONObject(0).getString("text").trim()
                        check(text.length in 1..8_000 && "```" !in text && text.none { c -> c.code < 32 && c !in "\n\r\t" })
                        return@exchangeModel RealtimeTranslation(text, response.toString())
                    }
                }
            }
            error("Realtime event limit exceeded")
        }
    }
}

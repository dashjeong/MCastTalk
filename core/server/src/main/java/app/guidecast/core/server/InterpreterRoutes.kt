package app.guidecast.core.server

import android.content.Context
import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.stream.StreamSession
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.request.receiveText
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*

internal const val INTERPRETER_SESSION_COOKIE = "guidecast_interpreter_session"

data class InterpreterReferences(val briefing: String = "", val glossary: List<Pair<String, String>> = emptyList())

/** 서버는 권한/입력 형식을 맡고 발언권과 게시 시점은 방송의 문장 제어에 맡긴다. */
class InterpreterSupport(
    val sessions: InterpreterSessions,
    val state: (String) -> String,
    val connected: (String) -> Unit,
    val disconnected: (String) -> Unit,
    val pause: (String) -> Unit,
    val frame: (String, PcmAudioFrame, Boolean) -> Boolean,
    val references: suspend (String, String) -> InterpreterReferences = { _, _ -> InterpreterReferences() },
)

internal data class InterpreterAssets(val html: ByteArray, val js: ByteArray, val css: ByteArray) {
    companion object {
        fun load(context: Context) = InterpreterAssets(
            context.assets.open("interpreter/index.html").use { it.readBytes() },
            context.assets.open("interpreter/interpreter.js").use { it.readBytes() },
            context.assets.open("interpreter/interpreter.css").use { it.readBytes() })
    }
}

internal fun Route.interpreterRoutes(
    host: String, stream: StreamSession, support: InterpreterSupport?, assets: InterpreterAssets?,
    transcriptProvider: () -> List<GuideCastTranscriptLine>, admissionController: LocalRequestAdmissionController,
    security: GuideCastTransportSecurity, httpsPort: Int?, requireTls: Boolean,
    liveAudioEnabled: () -> Boolean,
) {
    suspend fun ApplicationCall.validApi(browserOriginRequired: Boolean = false): Boolean {
        secureApiResponse()
        if (!requireValidHost(host)) return false
        if (requireTls && !requireHttpsMicRequest(security)) return false
        if (!(if (browserOriginRequired) hasValidBrowserOrigin(security, httpsPort) else hasValidOrigin(security, httpsPort))) {
            respond(HttpStatusCode.Forbidden); return false
        }
        if (support == null || !stream.isActive()) { respond(HttpStatusCode.Gone); return false }
        return true
    }
    for ((path, type, content) in listOf(
        Triple("/interpreter", ContentType.Text.Html, assets?.html),
        Triple("/interpreter/interpreter.js", ContentType.Application.JavaScript, assets?.js),
        Triple("/interpreter/interpreter.css", ContentType.Text.CSS, assets?.css))) {
        get(path) {
            val admission = call.acquireAdmission(admissionController, LocalRequestKind.PAGE_ASSET) ?: return@get
            try {
                if (!call.requireValidHost(host)) return@get
                call.secureBrowserResponse(security, httpsPort, allowMicrophone = true)
                if (content == null) call.respond(HttpStatusCode.NotFound) else call.respondBytes(content, type)
            } finally { admission.close() }
        }
    }
    route("/api/interpreter/join") {
        install(RequestBodyLimit) { bodyLimit { 128 } }
        post {
            val admission = call.acquireAdmission(admissionController, LocalRequestKind.ONBOARDING) ?: return@post
            try {
                if (!call.validApi(browserOriginRequired = true)) return@post
                val language = call.request.queryParameters["lang"].orEmpty()
                val credential = support!!.sessions.exchange(language, call.receiveText().trim())
                if (credential == null) { call.respond(HttpStatusCode.Unauthorized); return@post }
                val secure = if (requireTls) "Secure; " else ""
                call.response.header(HttpHeaders.SetCookie, "$INTERPRETER_SESSION_COOKIE=$credential; Path=/; Max-Age=14400; ${secure}HttpOnly; SameSite=Strict")
                call.respondText("{\"authenticated\":true}", ContentType.Application.Json)
            } finally { admission.close() }
        }
    }
    get("/api/interpreter/session") {
        val admission = call.acquireAdmission(admissionController, LocalRequestKind.READ_API) ?: return@get
        try {
            if (!call.validApi()) return@get
            val language = support!!.sessions.language(call.request.cookies[INTERPRETER_SESSION_COOKIE])
            if (language == null || language != call.request.queryParameters["lang"]) {
                call.respond(HttpStatusCode.Unauthorized); return@get
            }
            val lines = transcriptProvider().takeLast(24)
            val latest = lines.lastOrNull()?.sourceText.orEmpty()
            val references = withTimeoutOrNull(500) { support.references(language, latest) } ?: InterpreterReferences()
            val terms = references.glossary.take(12).joinToString(",") { (source, target) ->
                "{\"source\":\"${source.jsonEscaped()}\",\"target\":\"${target.jsonEscaped()}\"}" }
            val descriptor = stream.descriptor(language) ?: return@get call.respond(HttpStatusCode.Gone)
            call.respondText("{\"language\":\"${language.jsonEscaped()}\",\"name\":\"${descriptor.displayName.jsonEscaped()}\",\"state\":\"${support.state(language)}\",\"live\":${liveAudioEnabled()}," +
                "\"briefing\":\"${references.briefing.take(1200).jsonEscaped()}\",\"glossary\":[$terms],\"captions\":${lines.toTranscriptsJson(language)}}", ContentType.Application.Json)
        } finally { admission.close() }
    }
    webSocket("/ws/interpreter-input") {
        val admission = call.acquireAdmission(admissionController, LocalRequestKind.WEBSOCKET_HANDSHAKE) ?: return@webSocket
        try {
            val language = support?.sessions?.language(call.request.cookies[INTERPRETER_SESSION_COOKIE])
            val channel = call.request.queryParameters["lang"]
            if (!call.hasValidHost(host) || !call.hasValidBrowserOrigin(security, httpsPort) ||
                requireTls && !call.isHttpsMicRequest(security) || language == null || language != channel ||
                channel == "source" || stream.descriptor(language) == null || !stream.isActive()) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "통역사 QR로 다시 연결해 주세요")); return@webSocket
            }
            val lease = support!!.sessions.acquire(language)
            if (lease == null) {
                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "이미 다른 통역사가 맡고 있어요")); return@webSocket
            }
            try {
                support.connected(language)
                val converter = InterpreterPcmConverter(requireNotNull(stream.descriptor(language)).sampleRateHz)
                val lastHeartbeat = AtomicLong(System.nanoTime())
                coroutineScope {
                    val heartbeat = launch {
                        while (isActive) {
                            delay(1000)
                            if (!stream.isActive() || System.nanoTime() - lastHeartbeat.get() > 5_000_000_000L) {
                                close(CloseReason(CloseReason.Codes.GOING_AWAY, "연결이 끊겼어요")); break
                            }
                            send(Frame.Text("{\"state\":\"${support.state(language)}\",\"live\":${liveAudioEnabled()}}"))
                        }
                    }
                    try {
                        send(Frame.Text("{\"state\":\"${support.state(language)}\",\"live\":${liveAudioEnabled()}}"))
                        var expected = 0L
                        var lastInput = System.nanoTime()
                        var availableSamples = 3200.0
                        for (frame in incoming) {
                            lastHeartbeat.set(System.nanoTime())
                            when (frame) {
                                is Frame.Text -> if (frame.readText() == "pause") support.pause(language)
                                is Frame.Binary -> {
                                    val bytes = frame.readBytes()
                                    // 화자 입력과 같은 4바이트 순번 + 16kHz 모노 16비트 소리.
                                    if (bytes.size !in 6..3204 || (bytes.size - 4) % 2 != 0) {
                                        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "소리 형식을 확인해 주세요")); break
                                    }
                                    val sequence = java.nio.ByteBuffer.wrap(bytes, 0, 4).int.toLong() and 0xffffffffL
                                    if (sequence < expected) continue
                                    expected = sequence + 1
                                    val now = System.nanoTime()
                                    availableSamples = minOf(3200.0, availableSamples + (now - lastInput) * 16_000.0 / 1_000_000_000.0)
                                    lastInput = now
                                    availableSamples -= (bytes.size - 4) / 2
                                    if (availableSamples < 0) {
                                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "소리 전송이 너무 빨라요")); break
                                    }
                                    if (!liveAudioEnabled()) { support.pause(language); continue }
                                    val pcm = bytes.copyOfRange(4, bytes.size)
                                    val output = converter.convert(pcm)
                                    if (output.isNotEmpty()) support.frame(language, PcmAudioFrame(output, now), interpreterPcmHasVoice(pcm))
                                }
                                else -> Unit
                            }
                        }
                    } finally { heartbeat.cancelAndJoin() }
                }
            } finally {
                support.disconnected(language)
                lease.close()
            }
        } finally { admission.close() }
    }
}

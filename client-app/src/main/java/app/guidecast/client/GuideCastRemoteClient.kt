package app.guidecast.client

import app.guidecast.core.stream.PcmAudioFrame
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import java.io.Closeable
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

data class RemoteAudioConfig(
    val sourceChannelId: String,
    val sampleRateHz: Int,
)

data class GuideCastEndpoint(
    val baseUri: URI,
    val token: String?,
) {
    val displayUrl: String
        get() = URI(baseUri.scheme, null, baseUri.host, baseUri.port, "/", null, null).toString()
}

class GuideCastRemoteClient : Closeable {
    private val httpClient = HttpClient(CIO) {
        install(WebSockets) {
            pingIntervalMillis = 15_000
            maxFrameSize = MAX_PCM_FRAME_BYTES.toLong()
        }
    }

    suspend fun listen(
        address: String,
        pin: String,
        onConnected: (RemoteAudioConfig) -> Unit,
        onFrame: suspend (PcmAudioFrame) -> Unit,
    ) {
        val endpoint = parseGuideCastEndpoint(address)
        val accessMode = readAccessMode(endpoint)
        val token = when {
            !endpoint.token.isNullOrBlank() -> endpoint.token
            accessMode == "pin" -> joinWithPin(endpoint, pin)
            else -> null
        }
        val config = readSourceConfig(endpoint, token)
        val websocketUri = websocketUri(endpoint, config.sourceChannelId, token)
        onConnected(config)
        httpClient.webSocket(urlString = websocketUri.toString()) {
            for (frame in incoming) {
                when (frame) {
                    is Frame.Binary -> {
                        val bytes = try {
                            frame.readBoundedPcmBytes()
                        } catch (failure: NetworkPayloadLimitException) {
                            close(
                                CloseReason(
                                    CloseReason.Codes.TOO_BIG,
                                    "PCM frame exceeds 64 KiB",
                                ),
                            )
                            throw failure
                        }
                        if (bytes != null) {
                            onFrame(
                                PcmAudioFrame(
                                    bytes = bytes,
                                    capturedAtElapsedRealtimeNanos = android.os.SystemClock
                                        .elapsedRealtimeNanos(),
                                ),
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private suspend fun readAccessMode(endpoint: GuideCastEndpoint): String = withTimeout(8.seconds) {
        val response = httpClient.get(endpoint.resolve("api/session").toString())
        check(response.status.value in 200..299) { "방송 정보를 읽지 못했습니다." }
        JSONObject(response.readBoundedUtf8Body()).optString("access", "qr_token")
    }

    private suspend fun joinWithPin(endpoint: GuideCastEndpoint, pin: String): String {
        require(pin.matches(Regex("[0-9]{4,8}"))) { "방송 PIN 4~8자리를 입력하세요." }
        return withTimeout(8.seconds) {
            val response = httpClient.post(endpoint.resolve("api/join").toString()) {
                contentType(ContentType.Text.Plain)
                setBody(pin)
            }
            check(response.status.value in 200..299) {
                if (response.status.value == 429) "PIN 입력 횟수가 많습니다. 잠시 후 다시 시도하세요."
                else "방송 PIN이 올바르지 않습니다."
            }
            response.readBoundedUtf8Body().trim().also { token ->
                check(token.length in 16..256) { "방송 입장 토큰 형식이 올바르지 않습니다." }
            }
        }
    }

    private suspend fun readSourceConfig(
        endpoint: GuideCastEndpoint,
        token: String?,
    ): RemoteAudioConfig = withTimeout(8.seconds) {
        val response = httpClient.get(endpoint.resolve("api/status").toString()) {
            if (!token.isNullOrBlank()) header(HttpHeaders.Authorization, "Bearer $token")
        }
        check(response.status.value in 200..299) {
            if (response.status.value == 401) "방송 입장 정보가 만료되었거나 올바르지 않습니다."
            else "방송 채널을 읽지 못했습니다."
        }
        val channels = JSONObject(response.readBoundedUtf8Body()).getJSONArray("channels")
        val source = (0 until channels.length())
            .map(channels::getJSONObject)
            .firstOrNull { channel ->
                channel.optString("id") == "source" ||
                    channel.optString("languageTag").startsWith("ko", ignoreCase = true)
            } ?: error("송출기에 한국어 원음 채널이 없습니다. 송출 모드를 '원음'으로 설정하세요.")
        val channelId = source.getString("id")
        require(channelId.matches(Regex("[a-z0-9][a-z0-9_-]{0,23}"))) {
            "원음 채널 식별자가 올바르지 않습니다."
        }
        RemoteAudioConfig(
            sourceChannelId = channelId,
            sampleRateHz = source.optInt("sampleRate", 16_000).also {
                require(it in 8_000..48_000) { "지원하지 않는 원음 샘플레이트입니다." }
            },
        )
    }

    override fun close() {
        httpClient.close()
    }
}

fun parseGuideCastEndpoint(rawAddress: String): GuideCastEndpoint {
    val trimmed = rawAddress.trim()
    require(trimmed.length in 8..2_048) { "송출기 주소를 입력하세요." }
    val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
    val supplied = URI(withScheme)
    require(supplied.scheme == "http" || supplied.scheme == "https") {
        "http 또는 https 송출기 주소만 사용할 수 있습니다."
    }
    require(supplied.userInfo == null && !supplied.host.isNullOrBlank()) {
        "송출기 주소 형식이 올바르지 않습니다."
    }
    val port = when {
        supplied.port in 1..65_535 -> supplied.port
        supplied.scheme == "https" -> 443
        else -> 80
    }
    val baseUri = URI(supplied.scheme, null, supplied.host, port, "/", null, null)
    val token = supplied.rawFragment
        ?.split('&')
        ?.firstOrNull { it.substringBefore('=') == "token" }
        ?.substringAfter('=', "")
        ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
        ?.takeIf { it.length in 16..256 }
    return GuideCastEndpoint(baseUri, token)
}

private fun GuideCastEndpoint.resolve(path: String): URI = baseUri.resolve(path)

private fun websocketUri(
    endpoint: GuideCastEndpoint,
    channelId: String,
    token: String?,
): URI {
    val scheme = if (endpoint.baseUri.scheme == "https") "wss" else "ws"
    val path = "/ws/${URLEncoder.encode(channelId, StandardCharsets.UTF_8.name())}"
    val query = token?.let {
        "token=${URLEncoder.encode(it, StandardCharsets.UTF_8.name())}"
    }
    return URI(scheme, null, endpoint.baseUri.host, endpoint.baseUri.port, path, query, null)
}

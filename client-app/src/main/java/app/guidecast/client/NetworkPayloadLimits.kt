package app.guidecast.client

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

internal const val MAX_API_RESPONSE_BYTES: Int = 64 * 1024
internal const val MAX_PCM_FRAME_BYTES: Int = 64 * 1024

internal class NetworkPayloadLimitException(message: String) : IllegalStateException(message)

internal suspend fun HttpResponse.readBoundedUtf8Body(
    maxBytes: Int = MAX_API_RESPONSE_BYTES,
): String {
    val channel = bodyAsChannel()
    val declaredLength = headers[HttpHeaders.ContentLength]?.let { rawLength ->
        rawLength.toLongOrNull()?.takeIf { it >= 0L } ?: run {
            val failure = NetworkPayloadLimitException(
                "송출기 응답의 Content-Length가 올바르지 않습니다.",
            )
            channel.cancel(failure)
            throw failure
        }
    }
    return readBoundedUtf8Body(
        channel = channel,
        declaredLength = declaredLength,
        maxBytes = maxBytes,
    )
}

internal suspend fun readBoundedUtf8Body(
    channel: ByteReadChannel,
    declaredLength: Long?,
    maxBytes: Int = MAX_API_RESPONSE_BYTES,
): String {
    require(maxBytes in 1 until Int.MAX_VALUE) { "응답 크기 제한이 올바르지 않습니다." }
    if (declaredLength != null && declaredLength > maxBytes.toLong()) {
        val failure = NetworkPayloadLimitException(
            "송출기 응답이 허용 크기(${maxBytes}바이트)를 초과했습니다.",
        )
        channel.cancel(failure)
        throw failure
    }

    val initialCapacity = declaredLength
        ?.coerceAtMost(8 * 1024L)
        ?.toInt()
        ?: 1024
    val output = ByteArrayOutputStream(initialCapacity)
    val buffer = ByteArray(8 * 1024)
    var totalBytes = 0

    while (true) {
        val bytesWithOverflowSentinel = maxBytes - totalBytes + 1
        val readCount = channel.readAvailable(
            buffer,
            0,
            minOf(buffer.size, bytesWithOverflowSentinel),
        )
        when {
            readCount < 0 -> break
            readCount == 0 -> {
                if (!channel.awaitContent()) break
            }
            totalBytes + readCount > maxBytes -> {
                val failure = NetworkPayloadLimitException(
                    "송출기 응답이 허용 크기(${maxBytes}바이트)를 초과했습니다.",
                )
                channel.cancel(failure)
                throw failure
            }
            else -> {
                output.write(buffer, 0, readCount)
                totalBytes += readCount
            }
        }
    }

    return String(output.toByteArray(), StandardCharsets.UTF_8)
}

internal fun Frame.Binary.readBoundedPcmBytes(
    maxBytes: Int = MAX_PCM_FRAME_BYTES,
): ByteArray? {
    require(maxBytes > 0) { "PCM 프레임 크기 제한이 올바르지 않습니다." }
    if (data.size > maxBytes) {
        throw NetworkPayloadLimitException(
            "PCM 프레임이 허용 크기(${maxBytes}바이트)를 초과했습니다.",
        )
    }
    return if (data.isNotEmpty() && data.size % Short.SIZE_BYTES == 0) readBytes() else null
}

package app.guidecast.client

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteChannel
import io.ktor.websocket.Frame
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPayloadLimitsTest {
    @Test
    fun rejectsOversizedDeclaredApiBodyBeforeReadingIt() = runTest {
        val channel = ByteChannel()

        val failure = assertFailsWithSuspend<NetworkPayloadLimitException> {
            readBoundedUtf8Body(
                channel = channel,
                declaredLength = MAX_API_RESPONSE_BYTES.toLong() + 1L,
            )
        }

        assertTrue(failure.message.orEmpty().contains("초과"))
        assertTrue(channel.isClosedForRead)
    }

    @Test
    fun acceptsApiBodyAtLimitWhenLengthIsUnknown() = runTest {
        val payload = ByteArray(MAX_API_RESPONSE_BYTES) { 'a'.code.toByte() }

        val body = readBoundedUtf8Body(
            channel = ByteReadChannel(payload),
            declaredLength = null,
        )

        assertEquals(MAX_API_RESPONSE_BYTES, body.length)
    }

    @Test
    fun rejectsStreamedApiBodyThatExceedsLimit() = runTest {
        val payload = ByteArray(MAX_API_RESPONSE_BYTES + 1) { 'a'.code.toByte() }

        val failure = assertFailsWithSuspend<NetworkPayloadLimitException> {
            readBoundedUtf8Body(
                channel = ByteReadChannel(payload),
                declaredLength = null,
            )
        }

        assertTrue(failure.message.orEmpty().contains("초과"))
    }

    @Test
    fun acceptsPcmFrameAt64KiBAndPreservesOddFrameBehavior() {
        val accepted = Frame.Binary(true, ByteArray(MAX_PCM_FRAME_BYTES))
        val odd = Frame.Binary(true, byteArrayOf(1))

        assertEquals(MAX_PCM_FRAME_BYTES, accepted.readBoundedPcmBytes()?.size)
        assertNull(odd.readBoundedPcmBytes())
    }

    @Test
    fun rejectsPcmFrameLargerThan64KiB() {
        val oversized = Frame.Binary(true, ByteArray(MAX_PCM_FRAME_BYTES + 1))

        val failure = try {
            oversized.readBoundedPcmBytes()
            throw AssertionError("64 KiB를 넘는 PCM 프레임이 허용되었습니다.")
        } catch (expected: NetworkPayloadLimitException) {
            expected
        }

        assertTrue(failure.message.orEmpty().contains("초과"))
    }
}

private suspend inline fun <reified T : Throwable> assertFailsWithSuspend(
    crossinline block: suspend () -> Unit,
): T {
    try {
        block()
    } catch (failure: Throwable) {
        if (failure is T) return failure
        throw AssertionError("${T::class.java.simpleName} 대신 ${failure::class.java.simpleName} 발생", failure)
    }
    throw AssertionError("${T::class.java.simpleName}이 발생하지 않았습니다.")
}

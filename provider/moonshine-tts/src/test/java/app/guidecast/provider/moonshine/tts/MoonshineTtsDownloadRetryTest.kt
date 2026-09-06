package app.guidecast.provider.moonshine.tts

import java.io.IOException
import java.net.SocketException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class MoonshineTtsDownloadRetryTest {
    @Test
    fun transientIoFailuresResumeWithinTheSamePreparation() = runBlocking {
        var attempts = 0
        val retries = mutableListOf<Int>()

        val result = retryMoonshineTtsDownload(
            maxAttempts = 3,
            retryDelayMillis = { 0L },
            onRetry = { nextAttempt, _ -> retries += nextAttempt },
        ) {
            attempts += 1
            if (attempts < 3) throw SocketException("connection reset")
            "ready"
        }

        assertEquals("ready", result)
        assertEquals(3, attempts)
        assertEquals(listOf(2, 3), retries)
    }

    @Test
    fun nativeOrManifestFailureIsNotRetried() {
        var attempts = 0
        val failure = IllegalStateException("manifest changed")

        val actual = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                retryMoonshineTtsDownload(retryDelayMillis = { 0L }) {
                    attempts += 1
                    throw failure
                }
            }
        }

        assertSame(failure, actual)
        assertEquals(1, attempts)
    }

    @Test
    fun finalIoFailureIsReturnedAfterTheBoundedAttempts() {
        var attempts = 0
        val finalFailure = assertThrows(IOException::class.java) {
            runBlocking {
                retryMoonshineTtsDownload(
                    maxAttempts = 2,
                    retryDelayMillis = { 0L },
                ) {
                    attempts += 1
                    throw SocketException("connection-reset-$attempts")
                }
            }
        }

        assertEquals("connection-reset-2", finalFailure.message)
        assertEquals(2, attempts)
    }

    @Test
    fun permanentHttpAndStorageFailuresDoNotDelayFallback() {
        listOf(
            IOException("HTTP 404 fetching official voice"),
            IOException("Not enough disk space for voice"),
            IOException("Invalid download manifest"),
        ).forEach { failure ->
            var attempts = 0
            val actual = assertThrows(IOException::class.java) {
                runBlocking {
                    retryMoonshineTtsDownload(
                        maxAttempts = 3,
                        retryDelayMillis = { 0L },
                    ) {
                        attempts += 1
                        throw failure
                    }
                }
            }

            assertSame(failure, actual)
            assertEquals(1, attempts)
        }
    }

    @Test
    fun retryableHttpServerFailureUsesTheBoundedRetry() = runBlocking {
        var attempts = 0

        val result = retryMoonshineTtsDownload(
            maxAttempts = 2,
            retryDelayMillis = { 0L },
        ) {
            attempts += 1
            if (attempts == 1) throw IOException("HTTP 503 fetching official voice")
            "ready"
        }

        assertEquals("ready", result)
        assertEquals(2, attempts)
    }
}

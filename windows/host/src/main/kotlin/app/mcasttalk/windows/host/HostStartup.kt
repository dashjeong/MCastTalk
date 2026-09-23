package app.mcasttalk.windows.host

import io.ktor.server.engine.EmbeddedServer
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal fun startAndAwaitReady(
    server: EmbeddedServer<*, *>,
    expectedInstanceId: String,
    timeoutMillis: Long = 15_000,
    engineFailure: () -> Throwable? = { null },
    readinessUri: URI? = null,
    sslContext: SSLContext? = null,
): URI {
    require(timeoutMillis > 0)
    var lastReadinessFailure: Exception? = null
    try {
        return runBlocking {
            withTimeout(timeoutMillis) {
                server.startSuspend(wait = false)
                val connectors = server.engine.resolvedConnectors()
                check(connectors.size == 1) { "Expected one listening connector, found ${connectors.size}" }
                val connector = connectors.single()
                val baseUri = readinessUri ?: URI("http", null, connector.host, connector.port, "/", null, null)
                while (true) {
                    engineFailure()?.let { throw it }
                    try {
                        withContext(Dispatchers.IO) {
                            requireReadyResponse(baseUri.resolve("health/ready"), expectedInstanceId, sslContext)
                        }
                        return@withTimeout baseUri
                    } catch (error: Exception) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        lastReadinessFailure = error
                        delay(100)
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                error("Readiness loop ended unexpectedly")
            }
        }
    } catch (error: Throwable) {
        runCatching { server.stop(0, 1_000, TimeUnit.MILLISECONDS) }
        val cause = engineFailure()
            ?: if (error is TimeoutCancellationException) lastReadinessFailure ?: error else error
        throw IllegalStateException(
            "MCastTalk server did not become ready within ${timeoutMillis}ms: ${cause.message}",
            cause,
        )
    }
}

private fun requireReadyResponse(uri: URI, expectedInstanceId: String, sslContext: SSLContext?) {
    val connection = uri.toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
    try {
        if (connection is HttpsURLConnection && sslContext != null) connection.sslSocketFactory = sslContext.socketFactory
        connection.connectTimeout = 500
        connection.readTimeout = 500
        connection.instanceFollowRedirects = false
        check(connection.responseCode == 200) { "Readiness returned HTTP ${connection.responseCode}" }
        val bytes = connection.inputStream.use { it.readNBytes(16 * 1024 + 1) }
        check(bytes.size <= 16 * 1024) { "Readiness response is too large" }
        val response = parseFlatJsonObject(bytes.toString(Charsets.UTF_8))
        check(response.optionalString("status") == "ready") { "Readiness status was not ready" }
        check(response.optionalString("instanceId") == expectedInstanceId) {
            "Readiness response belongs to a different workspace instance"
        }
    } finally {
        connection.disconnect()
    }
}

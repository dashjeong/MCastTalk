package app.mcasttalk.windows.host

import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.net.HttpURLConnection
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HostStartupTest {
    @Test(timeout = 15_000)
    fun reportsReadyOnlyAfterTheRealNetworkEndpointResponds() {
        val root = Files.createTempDirectory("mcasttalk-startup-")
        val identity = WorkspaceSetup.initialize(root)
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { mcastTalkModule(root) }
        try {
            val base = startAndAwaitReady(server, identity.instanceId, timeoutMillis = 5_000)
            assertTrue(base.port > 0)
            val connection = base.resolve("health/live").toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 1_000
                connection.readTimeout = 1_000
                assertEquals(200, connection.responseCode)
                assertTrue(connection.inputStream.bufferedReader().use { it.readText() }.contains("mcasttalk-windows-host"))
            } finally {
                connection.disconnect()
            }
        } finally {
            server.stop(0, 1_000, TimeUnit.MILLISECONDS)
            root.toFile().deleteRecursively()
        }
    }

    @Test(timeout = 15_000)
    fun occupiedPortFailsInsteadOfEnteringTheLifetimeWait() {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { occupied ->
            val asynchronousFailure = CompletableFuture<Throwable>()
            val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, error ->
                asynchronousFailure.complete(error)
            })
            val server = scope.embeddedServer(CIO, host = "127.0.0.1", port = occupied.localPort) { }
            try {
                val error = assertThrows(IllegalStateException::class.java) {
                    startAndAwaitReady(
                        server,
                        "unused",
                        timeoutMillis = 3_000,
                        engineFailure = { asynchronousFailure.getNow(null) },
                    )
                }
                assertTrue(error.message.orEmpty().contains("did not become ready"))
                assertTrue(error.stackTraceToString(), generateSequence(error as Throwable) { it.cause }.any { it is BindException })
                val captured = asynchronousFailure.get(2, TimeUnit.SECONDS)
                assertTrue(captured.stackTraceToString(), generateSequence(captured) { it.cause }.any { it is BindException })
            } finally {
                server.stop(0, 1_000, TimeUnit.MILLISECONDS)
                scope.cancel()
            }
        }
    }

    @Test(timeout = 15_000)
    fun wrongWorkspaceResponseFailsReadiness() {
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            routing {
                get("/health/ready") {
                    call.respondText("""{"status":"ready","instanceId":"other-workspace"}""")
                }
            }
        }
        try {
            val error = assertThrows(IllegalStateException::class.java) {
                startAndAwaitReady(server, "expected-workspace", timeoutMillis = 1_000)
            }
            assertTrue(error.cause?.message.orEmpty().contains("different workspace instance"))
        } finally {
            server.stop(0, 1_000, TimeUnit.MILLISECONDS)
        }
    }
}

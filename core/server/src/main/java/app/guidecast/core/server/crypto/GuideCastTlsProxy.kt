package app.guidecast.core.server.crypto

import java.io.Closeable
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lightweight, high-performance on-device TLS termination reverse proxy.
 *
 * Terminates incoming TLS (HTTPS/WSS) connections on [httpsPort] using the dynamic
 * [keyStore] (with IP SAN), and forwards the decrypted TCP byte stream to the local
 * Ktor server on [targetPort] (127.0.0.1).
 *
 * Complies strictly with AGENTS.md:
 * - Uses standard Android JSSE (javax.net.ssl) with zero external dependencies.
 * - Hardware-accelerated Conscrypt/BoringSSL TLS encryption on Android.
 * - Non-blocking streaming bridge supporting full-duplex HTTP and WebSocket frames.
 */
internal class GuideCastTlsProxy(
    val bindAddress: InetAddress,
    val httpsPort: Int,
    /** Address where the plain HTTP Ktor server is actually listening. */
    private val targetAddress: InetAddress = bindAddress,
    val targetPort: Int,
    keyStore: KeyStore,
    private val backendAuthenticator: GuideCastTlsBackendAuthenticator,
    keyPassword: CharArray = ServerCertificateBundle.KEYSTORE_PASSWORD,
    private val targetSocketFactory: (InetAddress, Int) -> Socket = { address, port ->
        Socket(address, port)
    },
    maxConcurrentConnections: Int = DEFAULT_MAX_CONCURRENT_CONNECTIONS,
    maxConnectionsPerPeer: Int = DEFAULT_MAX_CONNECTIONS_PER_PEER,
    private val handshakeTimeoutMillis: Int = DEFAULT_HANDSHAKE_TIMEOUT_MILLIS,
    private val idleTimeoutMillis: Int = DEFAULT_IDLE_TIMEOUT_MILLIS,
    private val maximumRequestHeaderBytes: Int = DEFAULT_MAX_REQUEST_HEADER_BYTES,
) : Closeable {

    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serverSocket: ServerSocket
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val connectionLimiter = GuideCastTlsConnectionLimiter(
        maxConcurrentConnections = maxConcurrentConnections,
        maxConnectionsPerPeer = maxConnectionsPerPeer,
    )

    init {
        require(handshakeTimeoutMillis > 0)
        require(idleTimeoutMillis > 0)
        require(maximumRequestHeaderBytes in 1_024..MAX_SAFE_REQUEST_HEADER_BYTES)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, keyPassword)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, null, SecureRandom())

        val sslServerSocket = sslContext.serverSocketFactory.createServerSocket(
            httpsPort,
            maxConcurrentConnections.coerceAtLeast(MINIMUM_LISTEN_BACKLOG),
            bindAddress,
        ) as SSLServerSocket
        sslServerSocket.needClientAuth = false
        sslServerSocket.reuseAddress = true
        serverSocket = sslServerSocket
    }

    val actualPort: Int
        get() = serverSocket.localPort

    fun start() {
        scope.launch {
            while (isActive && !closed.get()) {
                val clientSocket = try {
                    serverSocket.accept()
                } catch (_: Throwable) {
                    break
                }
                val peerAddress = clientSocket.inetAddress?.hostAddress.orEmpty()
                val admission = connectionLimiter.tryAcquire(peerAddress)
                if (admission == null) {
                    runCatching { clientSocket.close() }
                    continue
                }
                activeSockets.add(clientSocket)
                scope.launch {
                    try {
                        handleConnection(clientSocket, peerAddress)
                    } finally {
                        admission.close()
                    }
                }
            }
        }
    }

    private fun handleConnection(clientSocket: Socket, peerAddress: String) {
        var targetSocket: Socket? = null
        try {
            val secureClient = clientSocket as? SSLSocket
                ?: throw IOException("TLS proxy accepted a non-TLS socket")
            clientSocket.tcpNoDelay = true
            clientSocket.soTimeout = handshakeTimeoutMillis
            // Do not allocate a backend socket until the peer proves that it can complete TLS.
            // This keeps raw idle TCP connections outside Ktor and bounds their lifetime.
            secureClient.startHandshake()
            clientSocket.soTimeout = idleTimeoutMillis

            val clientInput = BufferedInputStream(clientSocket.getInputStream(), PIPE_BUFFER_BYTES)
            val requestHead = readInitialRequestHead(clientInput)
            val attestation = backendAuthenticator.attest(peerAddress)
            val authenticatedRequestHead = authenticateInitialRequest(requestHead, attestation)

            val backendSocket = targetSocketFactory(targetAddress, targetPort).apply {
                tcpNoDelay = true
                soTimeout = idleTimeoutMillis
            }
            targetSocket = backendSocket
            activeSockets.add(backendSocket)
            backendSocket.getOutputStream().apply {
                write(authenticatedRequestHead)
                flush()
            }

            val targetToClient = scope.launch {
                try {
                    pipe(backendSocket.getInputStream(), clientSocket.getOutputStream())
                } finally {
                    // A complete HTTP response/backend close or a terminated WebSocket must wake
                    // the opposite blocking read immediately instead of occupying a slot to its
                    // idle deadline.
                    runCatching { backendSocket.close() }
                    runCatching { clientSocket.close() }
                }
            }
            try {
                pipe(clientInput, backendSocket.getOutputStream())
            } finally {
                targetToClient.cancel()
                runCatching { backendSocket.close() }
                runCatching { clientSocket.close() }
            }
        } catch (_: Throwable) {
            runCatching { targetSocket?.close() }
            runCatching { clientSocket.close() }
        } finally {
            targetSocket?.let { activeSockets.remove(it) }
            activeSockets.remove(clientSocket)
        }
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(PIPE_BUFFER_BYTES)
        try {
            while (true) {
                val bytesRead = input.read(buffer)
                if (bytesRead < 0) break
                output.write(buffer, 0, bytesRead)
                output.flush()
            }
        } catch (_: IOException) {
            // Socket closed or reset
        }
    }

    private fun readInitialRequestHead(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream(minOf(maximumRequestHeaderBytes, PIPE_BUFFER_BYTES))
        var delimiterState = 0
        while (output.size() < maximumRequestHeaderBytes) {
            val next = input.read()
            if (next < 0) throw IOException("TLS peer closed before sending an HTTP request")
            output.write(next)
            delimiterState = when {
                delimiterState == 0 && next == '\r'.code -> 1
                delimiterState == 1 && next == '\n'.code -> 2
                delimiterState == 2 && next == '\r'.code -> 3
                delimiterState == 3 && next == '\n'.code -> return output.toByteArray()
                next == '\r'.code -> 1
                else -> 0
            }
        }
        throw IOException("TLS request headers exceeded $maximumRequestHeaderBytes bytes")
    }

    private fun authenticateInitialRequest(
        requestHead: ByteArray,
        attestation: GuideCastTlsBackendAuthenticator.Attestation,
    ): ByteArray {
        val text = requestHead.toString(Charsets.ISO_8859_1)
        if (!text.endsWith("\r\n\r\n") || '\u0000' in text) {
            throw IOException("Malformed HTTP request headers")
        }
        val lines = text.dropLast(4).split("\r\n")
        val requestLine = lines.firstOrNull()
            ?.takeIf { it.isNotBlank() && !it.startsWith(' ') && !it.startsWith('\t') }
            ?: throw IOException("Missing HTTP request line")
        if (lines.drop(1).any { it.startsWith(' ') || it.startsWith('\t') || ':' !in it }) {
            throw IOException("Malformed or folded HTTP request header")
        }

        val forwardedHeaders = lines.drop(1).filterNot { line ->
            line.substringBefore(':').trim().lowercase()
                .startsWith(GuideCastTlsBackendAuthenticator.INTERNAL_HEADER_PREFIX)
        }
        val websocketUpgrade = forwardedHeaders.any { line ->
            line.substringBefore(':').trim().equals("upgrade", ignoreCase = true) &&
                line.substringAfter(':').trim().equals("websocket", ignoreCase = true)
        }
        val connectionSafeHeaders = if (websocketUpgrade) {
            forwardedHeaders
        } else {
            // One authenticated request per backend connection makes the per-connection marker an
            // unambiguous trust boundary. WebSocket upgrade connections remain full-duplex.
            forwardedHeaders.filterNot { line ->
                val name = line.substringBefore(':').trim()
                name.equals("connection", ignoreCase = true) ||
                    name.equals("proxy-connection", ignoreCase = true)
            } + "Connection: close"
        }
        return buildString {
            append(requestLine).append("\r\n")
            connectionSafeHeaders.forEach { append(it).append("\r\n") }
            append(GuideCastTlsBackendAuthenticator.VERSION_HEADER)
                .append(": ").append(GuideCastTlsBackendAuthenticator.PROTOCOL_VERSION).append("\r\n")
            append(GuideCastTlsBackendAuthenticator.PEER_HEADER)
                .append(": ").append(attestation.peerAddress).append("\r\n")
            append(GuideCastTlsBackendAuthenticator.NONCE_HEADER)
                .append(": ").append(attestation.nonce).append("\r\n")
            append(GuideCastTlsBackendAuthenticator.MAC_HEADER)
                .append(": ").append(attestation.mac).append("\r\n\r\n")
        }.toByteArray(Charsets.ISO_8859_1)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { serverSocket.close() }
        activeSockets.forEach { socket -> runCatching { socket.close() } }
        activeSockets.clear()
        scope.cancel()
    }

    private companion object {
        const val DEFAULT_MAX_CONCURRENT_CONNECTIONS = 32
        const val DEFAULT_MAX_CONNECTIONS_PER_PEER = 8
        const val DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 8_000
        const val DEFAULT_IDLE_TIMEOUT_MILLIS = 45_000
        const val DEFAULT_MAX_REQUEST_HEADER_BYTES = 32 * 1_024
        const val MAX_SAFE_REQUEST_HEADER_BYTES = 64 * 1_024
        const val MINIMUM_LISTEN_BACKLOG = 8
        const val PIPE_BUFFER_BYTES = 16 * 1_024
    }
}

/** Bounds both aggregate and per-peer sockets before a coroutine or backend connection is made. */
internal class GuideCastTlsConnectionLimiter(
    maxConcurrentConnections: Int,
    private val maxConnectionsPerPeer: Int,
) {
    private val globalPermits = Semaphore(maxConcurrentConnections)
    private val peerCounts = ConcurrentHashMap<String, AtomicInteger>()

    init {
        require(maxConcurrentConnections > 0)
        require(maxConnectionsPerPeer in 1..maxConcurrentConnections)
    }

    fun tryAcquire(peerAddress: String): Closeable? {
        if (peerAddress.isBlank() || !globalPermits.tryAcquire()) return null
        val peerCount = peerCounts.computeIfAbsent(peerAddress) { AtomicInteger() }
        while (true) {
            val current = peerCount.get()
            if (current >= maxConnectionsPerPeer) {
                globalPermits.release()
                if (current == 0) peerCounts.remove(peerAddress, peerCount)
                return null
            }
            if (peerCount.compareAndSet(current, current + 1)) break
        }
        return object : Closeable {
            private val closed = AtomicBoolean(false)

            override fun close() {
                if (!closed.compareAndSet(false, true)) return
                if (peerCount.decrementAndGet() == 0) peerCounts.remove(peerAddress, peerCount)
                globalPermits.release()
            }
        }
    }
}

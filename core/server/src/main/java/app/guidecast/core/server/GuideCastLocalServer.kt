package app.guidecast.core.server

import android.content.Context
import app.guidecast.core.server.crypto.GuideCastCertificateAuthority
import app.guidecast.core.server.crypto.GuideCastTlsBackendAuthenticator
import app.guidecast.core.server.crypto.GuideCastTlsProxy
import app.guidecast.core.server.crypto.verifyGuideCastCaDerFingerprint
import app.guidecast.core.stream.AudioChannelDescriptor
import app.guidecast.core.stream.AudioStreamRegistry
import app.guidecast.core.stream.ListenerLimitExceededException
import app.guidecast.core.stream.StreamSession
import app.guidecast.core.stream.StreamSessionSupersededException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.request.host
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import io.ktor.websocket.send
import io.ktor.util.AttributeKey
import java.io.Closeable
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select

private const val SPEAKER_SESSION_COOKIE = "guidecast_speaker_session"
private const val SPEAKER_SESSION_MAX_AGE_SECONDS = 4 * 60 * 60
private val VERIFIED_TLS_PEER_ADDRESS = AttributeKey<String>("GuideCastVerifiedTlsPeerAddress")

/**
 * Selected by the listening server socket, never inferred from client-controlled headers.
 */
internal enum class GuideCastTransportSecurity {
    PLAIN_HTTP,
    TLS_TERMINATED,
}

data class GuideCastServerConfig(
    val port: Int = 8787,
    val httpsPort: Int = 8443,
    val enableHttps: Boolean = true,
    val access: BroadcastAccess = BroadcastAccess.QrToken,
    val speakerAccess: SpeakerAccess = SpeakerAccess.Open,
) {
    init {
        require(port in 1024..65535) { "Use an unprivileged TCP port" }
        require(httpsPort in 1024..65535 && httpsPort != port) {
            "HTTPS port must be an unprivileged TCP port different from HTTP port"
        }
    }
}

data class GuideCastTranscriptLine(
    val sequence: Long,
    val sourceText: String,
    val isFinal: Boolean,
    val capturedAtElapsedRealtimeNanos: Long,
    val translations: Map<String, String>,
    val translationLatencyMillis: Map<String, Long>,
    val firstAudioLatencyMillis: Map<String, Long>,
    val synthesisLatencyMillis: Map<String, Long>,
)

/**
 * Immutable transcript publication for one server generation.
 *
 * [revision] must change whenever any field in [lines] changes. The local server can then reuse a
 * previously frozen, serialized response for all listener polls without scanning or copying up to
 * one thousand transcript rows on every request.
 */
data class GuideCastTranscriptSnapshot(
    val revision: Long,
    val lines: List<GuideCastTranscriptLine>,
) {
    init {
        require(revision >= 0L) { "Transcript revision must not be negative" }
    }
}

class GuideCastLocalServer(
    context: Context,
    private val streams: AudioStreamRegistry,
    private val transcriptProvider: () -> List<GuideCastTranscriptLine> = { emptyList() },
    private val bindAllInterfacesForDebug: Boolean = false,
    private val onSpeakerFrameReceived: (ByteArray, Long) -> Unit = { _, _ -> },
    private val isSpeakerInputReady: () -> Boolean = { true },
    private val onSpeakerSessionOpened: () -> Long = { 0L },
    private val onSpeakerSessionClosed: (Long) -> Unit = {},
    private val transcriptSnapshotProvider: (() -> GuideCastTranscriptSnapshot)? = null,
) {
    private val appContext = context.applicationContext
    private val started = AtomicBoolean(false)

    fun start(
        bindAddress: Inet4Address,
        config: GuideCastServerConfig = GuideCastServerConfig(),
        streamSession: StreamSession = streams.currentSession(),
    ): RunningGuideCastServer {
        require(streamSession.channels.isNotEmpty()) {
            "Configure stream channels before starting the local server"
        }
        require(streamSession.isActive()) { "Cannot start a server for a replaced stream session" }
        check(started.compareAndSet(false, true)) { "GuideCast server is already running" }
        val host = bindAddress.hostAddress ?: error("Missing bind address")
        val authenticator = BroadcastSessionAuthenticator.create(
            access = config.access,
            speakerAccess = config.speakerAccess,
        )
        val assets = ListenerAssets.load(appContext)
        val speakerAssets = runCatching { SpeakerAssets.load(appContext) }.getOrNull()
        val ca = runCatching {
            GuideCastCertificateAuthority.getOrCreate(File(appContext.filesDir, "ca"))
        }.getOrNull()
        val caFingerprintVerification = ca?.let { authority ->
            verifyGuideCastCaDerFingerprint(
                expectedFingerprint = authority.caSha256Fingerprint(),
                certificateDer = authority.caCertificate.encoded,
            )
        }
        val certBundle = if (config.enableHttps && ca != null) {
            runCatching {
                ca.issueServerCertificate(hostAddress = bindAddress, dnsName = "guidecast.local")
            }.getOrNull()
        } else null
        val tlsBackendAuthenticator = certBundle?.let {
            GuideCastTlsBackendAuthenticator.create()
        }

        var stopSecureBackend: (() -> Unit)? = null
        val tlsProxy = if (certBundle != null) {
            runCatching {
                val loopback = InetAddress.getByName("127.0.0.1")
                val secureBackend = embeddedServer(
                    CIO,
                    host = requireNotNull(loopback.hostAddress),
                    port = 0,
                ) {
                    guideCastModule(
                        expectedHost = host,
                        authenticator = authenticator,
                        assets = assets,
                        streamSession = streamSession,
                        transcriptProvider = transcriptProvider,
                        speakerAssets = speakerAssets,
                        onSpeakerFrameReceived = onSpeakerFrameReceived,
                        isSpeakerInputReady = isSpeakerInputReady,
                        onSpeakerSessionOpened = onSpeakerSessionOpened,
                        onSpeakerSessionClosed = onSpeakerSessionClosed,
                        ca = ca,
                        httpsPort = config.httpsPort,
                        transportSecurity = GuideCastTransportSecurity.TLS_TERMINATED,
                        requireTlsSpeakerTransport = config.enableHttps,
                        tlsBackendAuthenticator = tlsBackendAuthenticator,
                        transcriptSnapshotProvider = transcriptSnapshotProvider,
                    )
                }.start(wait = false)
                stopSecureBackend = {
                    secureBackend.stop(gracePeriodMillis = 500, timeoutMillis = 1_500)
                }
                try {
                    val secureBackendPort = runBlocking {
                        secureBackend.engine.resolvedConnectors().single().port
                    }
                    GuideCastTlsProxy(
                        bindAddress = bindAddress,
                        httpsPort = config.httpsPort,
                        targetAddress = loopback,
                        targetPort = secureBackendPort,
                        keyStore = certBundle.toKeyStore(),
                        backendAuthenticator = requireNotNull(tlsBackendAuthenticator),
                    ).apply { start() }
                } catch (error: Throwable) {
                    stopSecureBackend.invoke()
                    stopSecureBackend = null
                    throw error
                }
            }.getOrNull()
        } else null

        val engine = try {
            val listenHost = if (bindAllInterfacesForDebug) "0.0.0.0" else host
            embeddedServer(CIO, host = listenHost, port = config.port) {
                guideCastModule(
                    expectedHost = host,
                    authenticator = authenticator,
                    assets = assets,
                    streamSession = streamSession,
                    transcriptProvider = transcriptProvider,
                    speakerAssets = speakerAssets,
                    onSpeakerFrameReceived = onSpeakerFrameReceived,
                    isSpeakerInputReady = isSpeakerInputReady,
                    onSpeakerSessionOpened = onSpeakerSessionOpened,
                    onSpeakerSessionClosed = onSpeakerSessionClosed,
                    ca = ca,
                    httpsPort = tlsProxy?.let { config.httpsPort },
                    transportSecurity = GuideCastTransportSecurity.PLAIN_HTTP,
                    // A certificate/proxy startup failure may remove the advertised HTTPS port,
                    // but it must never reopen the legacy plaintext query-token microphone path.
                    requireTlsSpeakerTransport = config.enableHttps,
                    transcriptSnapshotProvider = transcriptSnapshotProvider,
                )
            }.start(wait = false)
        } catch (error: Throwable) {
            tlsProxy?.close()
            stopSecureBackend?.invoke()
            started.set(false)
            throw error
        }

        val baseUrl = "http://$host:${config.port}/"
        val qrToken = authenticator.tokenForQr()
        val listenerUrl = qrToken?.let { "$baseUrl#token=$it" } ?: baseUrl
        // `/mic` is the only user-facing entry. HTTP provides onboarding; after trust is installed,
        // the same path on HTTPS performs PIN authentication and microphone capture.
        val speakerUrl = "$baseUrl" + "mic"
        return RunningGuideCastServer(
            bindAddress = bindAddress,
            port = config.port,
            httpsPort = tlsProxy?.let { config.httpsPort },
            listenerUrl = listenerUrl,
            speakerUrl = speakerUrl,
            accessMode = authenticator.mode,
            streamSession = streamSession,
            caSha256Fingerprint = caFingerprintVerification?.expectedFingerprint,
            caFingerprintWarning = caFingerprintVerification?.warning,
            channelUrlFactory = { channelId ->
                buildString {
                    append(baseUrl).append(channelId)
                    if (qrToken != null) append("#token=").append(qrToken)
                }
            },
        ) {
            try {
                tlsProxy?.close()
                stopSecureBackend?.invoke()
                engine.stop(gracePeriodMillis = 500, timeoutMillis = 1_500)
            } finally {
                // A failed Ktor stop must not permanently poison this server owner. The caller
                // still receives the exception and may retry cleanup or create a replacement.
                started.set(false)
            }
        }
    }
}

class RunningGuideCastServer internal constructor(
    val bindAddress: Inet4Address,
    val port: Int,
    val httpsPort: Int?,
    val listenerUrl: String,
    val speakerUrl: String,
    val accessMode: BroadcastAccessMode,
    val streamSession: StreamSession,
    /** Installation-local Root CA fingerprint shown out-of-band on the transmitter UI. */
    val caSha256Fingerprint: String? = null,
    /** Non-null only when the exact downloadable DER bytes do not match the advertised digest. */
    val caFingerprintWarning: String? = null,
    private val channelUrlFactory: (String) -> String,
    private val stopServer: () -> Unit,
) : Closeable {
    internal constructor(
        bindAddress: Inet4Address,
        port: Int,
        listenerUrl: String,
        speakerUrl: String,
        accessMode: BroadcastAccessMode,
        streamSession: StreamSession,
        channelUrlFactory: (String) -> String,
        stopServer: () -> Unit,
    ) : this(
        bindAddress = bindAddress,
        port = port,
        httpsPort = null,
        listenerUrl = listenerUrl,
        speakerUrl = speakerUrl,
        accessMode = accessMode,
        streamSession = streamSession,
        caSha256Fingerprint = null,
        caFingerprintWarning = null,
        channelUrlFactory = channelUrlFactory,
        stopServer = stopServer,
    )

    internal constructor(
        bindAddress: Inet4Address,
        port: Int,
        listenerUrl: String,
        accessMode: BroadcastAccessMode,
        streamSession: StreamSession,
        channelUrlFactory: (String) -> String,
        stopServer: () -> Unit,
    ) : this(
        bindAddress = bindAddress,
        port = port,
        httpsPort = null,
        listenerUrl = listenerUrl,
        speakerUrl = channelUrlFactory("speaker"),
        accessMode = accessMode,
        streamSession = streamSession,
        caSha256Fingerprint = null,
        caFingerprintWarning = null,
        channelUrlFactory = channelUrlFactory,
        stopServer = stopServer,
    )
    private val closed = AtomicBoolean(false)

    /** Language-pinned URLs frozen to this server's exact stream generation. */
    val listenerUrlsByChannel: Map<String, String> = streamSession.channels.associate { channel ->
        channel.id to channelUrlFactory(channel.id)
    }

    fun listenerUrlFor(channelId: String): String? =
        streamSession.channels
            .firstOrNull { it.id == channelId }
            ?.let { channelUrlFactory(it.id) }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            stopServer()
        } catch (error: Throwable) {
            // Keep a stop handle retryable when the underlying engine throws before releasing
            // its socket. BroadcastService deliberately detaches all other resources regardless.
            closed.set(false)
            throw error
        }
    }
}

/** Compatibility entry point that freezes the registry's current generation at module install. */
internal fun Application.guideCastModule(
    expectedHost: String,
    authenticator: BroadcastSessionAuthenticator,
    assets: ListenerAssets,
    streams: AudioStreamRegistry,
    transcriptProvider: () -> List<GuideCastTranscriptLine> = { emptyList() },
    admissionController: LocalRequestAdmissionController = LocalRequestAdmissionController(),
    speakerAssets: SpeakerAssets? = null,
    transcriptSnapshotProvider: (() -> GuideCastTranscriptSnapshot)? = null,
) = guideCastModule(
    expectedHost = expectedHost,
    authenticator = authenticator,
    assets = assets,
    streamSession = streams.currentSession(),
    transcriptProvider = transcriptProvider,
    admissionController = admissionController,
    speakerAssets = speakerAssets,
    transcriptSnapshotProvider = transcriptSnapshotProvider,
)

internal fun Application.guideCastModule(
    expectedHost: String,
    authenticator: BroadcastSessionAuthenticator,
    assets: ListenerAssets,
    streamSession: StreamSession,
    transcriptProvider: () -> List<GuideCastTranscriptLine> = { emptyList() },
    admissionController: LocalRequestAdmissionController = LocalRequestAdmissionController(),
    speakerAssets: SpeakerAssets? = null,
    onSpeakerFrameReceived: (ByteArray, Long) -> Unit = { _, _ -> },
    isSpeakerInputReady: () -> Boolean = { true },
    onSpeakerSessionOpened: () -> Long = { 0L },
    onSpeakerSessionClosed: (Long) -> Unit = {},
    speakerLeaseManager: SpeakerLeaseManager = SpeakerLeaseManager(),
    ca: GuideCastCertificateAuthority? = null,
    httpsPort: Int? = null,
    transportSecurity: GuideCastTransportSecurity = GuideCastTransportSecurity.PLAIN_HTTP,
    requireTlsSpeakerTransport: Boolean = httpsPort != null,
    tlsBackendAuthenticator: GuideCastTlsBackendAuthenticator? = null,
    transcriptSnapshotProvider: (() -> GuideCastTranscriptSnapshot)? = null,
) {
    install(WebSockets) {
        pingPeriodMillis = 15_000
        timeoutMillis = 15_000
        maxFrameSize = 64 * 1024
        masking = false
    }
    if (transportSecurity == GuideCastTransportSecurity.TLS_TERMINATED) {
        val authenticator = requireNotNull(tlsBackendAuthenticator) {
            "TLS-terminated backend requires an authenticated proxy channel"
        }
        intercept(ApplicationCallPipeline.Plugins) {
            val socketPeer = call.request.local.remoteAddress
            val verifiedPeer = authenticator.verify { headerName ->
                call.request.headers.getAll(headerName)
            }
            if (!socketPeer.isLoopbackSocketPeer() || verifiedPeer == null) {
                call.secureApiResponse()
                call.respondText(
                    "Untrusted TLS backend transport",
                    status = HttpStatusCode(421, "Misdirected Request"),
                )
                finish()
                return@intercept
            }
            call.attributes.put(VERIFIED_TLS_PEER_ADDRESS, verifiedPeer)
        }
    }
    val transcriptResponseCache = TranscriptResponseCache()

    routing {
        get("/ca.crt") {
            val admission = call.acquireAdmission(
                admissionController,
                LocalRequestKind.PAGE_ASSET,
            ) ?: return@get
            try {
                if (!call.requireValidHost(expectedHost)) return@get
                call.secureApiResponse()
                val certBytes = ca?.caCertificatePem()
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.response.header(
                    "Content-Disposition",
                    "attachment; filename=\"guidecast-local-root-ca.crt\"",
                )
                call.respondBytes(certBytes, ContentType("application", "x-x509-ca-cert"))
            } finally {
                admission.close()
            }
        }

        get("/ca.der") {
            val admission = call.acquireAdmission(
                admissionController,
                LocalRequestKind.PAGE_ASSET,
            ) ?: return@get
            try {
                if (!call.requireValidHost(expectedHost)) return@get
                call.secureApiResponse()
                val certBytes = ca?.caCertificate?.encoded
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.response.header(
                    "Content-Disposition",
                    "attachment; filename=\"guidecast-local-root-ca.der\"",
                )
                call.respondBytes(certBytes, ContentType("application", "pkix-cert"))
            } finally {
                admission.close()
            }
        }

        get("/guidecast.mobileconfig") {
            val admission = call.acquireAdmission(
                admissionController,
                LocalRequestKind.PAGE_ASSET,
            ) ?: return@get
            try {
                if (!call.requireValidHost(expectedHost)) return@get
                call.secureApiResponse()
                val configStr = ca?.generateAppleMobileConfig()
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.response.header(
                    "Content-Disposition",
                    "attachment; filename=\"guidecast.mobileconfig\"",
                )
                call.respondBytes(
                    configStr.toByteArray(StandardCharsets.UTF_8),
                    ContentType("application", "x-apple-aspen-config"),
                )
            } finally {
                admission.close()
            }
        }

        get("/setup-trust") {
            val admission = call.acquireAdmission(
                admissionController,
                LocalRequestKind.PAGE_ASSET,
            ) ?: return@get
            try {
                if (!call.requireValidHost(expectedHost)) return@get
                call.secureBrowserResponse(transportSecurity, httpsPort)
                call.response.header(HttpHeaders.Location, "/mic")
                call.respondText("Microphone setup moved to /mic", status = HttpStatusCode.PermanentRedirect)
            } finally {
                admission.close()
            }
        }

        get("/setup-trust.js") {
            val admission = call.acquireAdmission(
                admissionController,
                LocalRequestKind.PAGE_ASSET,
            ) ?: return@get
            try {
                if (!call.requireValidHost(expectedHost)) return@get
                call.secureBrowserResponse(transportSecurity, httpsPort)
                val javascript = speakerAssets?.setupTrustJavascript
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respondBytes(javascript, ContentType.Application.JavaScript)
            } finally {
                admission.close()
            }
        }

        get("/speaker") {
            val admission = call.acquireAdmission(
                admissionController,
                LocalRequestKind.PAGE_ASSET,
            ) ?: return@get
            try {
                if (!call.requireValidHost(expectedHost)) return@get
                call.secureBrowserResponse(transportSecurity, httpsPort, allowMicrophone = true)
                call.response.header(HttpHeaders.Location, "/mic")
                call.respondText("Instructor microphone moved to /mic", status = HttpStatusCode.PermanentRedirect)
            } finally {
                admission.close()
            }
        }

        get("/mic") {
            val admission = call.acquireAdmission(
                admissionController,
                LocalRequestKind.PAGE_ASSET,
            ) ?: return@get
            try {
                if (!call.requireValidHost(expectedHost)) return@get
                call.secureBrowserResponse(transportSecurity, httpsPort, allowMicrophone = true)
                val html = speakerAssets?.html ?: return@get call.respond(HttpStatusCode.NotFound)
                val rendered = html.toString(StandardCharsets.UTF_8)
                    .replace("__GUIDECAST_HTTPS_PORT__", httpsPort?.toString().orEmpty())
                    .replace("__GUIDECAST_CA_FINGERPRINT__", ca?.caSha256Fingerprint().orEmpty())
                call.respondBytes(rendered.toByteArray(StandardCharsets.UTF_8), ContentType.Text.Html)
            } finally {
                admission.close()
            }
        }

        get("/speaker.js") {
            val admission = call.acquireAdmission(
                admissionController,
                LocalRequestKind.PAGE_ASSET,
            ) ?: return@get
            try {
                if (!call.requireValidHost(expectedHost)) return@get
                call.secureBrowserResponse(transportSecurity, httpsPort, allowMicrophone = true)
                val js = speakerAssets?.javascript ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respondBytes(js, ContentType.Application.JavaScript)
            } finally {
                admission.close()
            }
        }

        get("/speaker.css") {
            val admission = call.acquireAdmission(
                admissionController,
                LocalRequestKind.PAGE_ASSET,
            ) ?: return@get
            try {
                if (!call.requireValidHost(expectedHost)) return@get
                call.secureBrowserResponse(transportSecurity, httpsPort, allowMicrophone = true)
                val css = speakerAssets?.css ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respondBytes(css, ContentType.Text.CSS)
            } finally {
                admission.close()
            }
        }

        get("/api/mic/session") {
            val admission = call.acquireAdmission(
                admissionController,
                LocalRequestKind.ONBOARDING,
            ) ?: return@get
            try {
                if (!call.requireValidHost(expectedHost)) return@get
                if (!call.requireHttpsMicRequest(transportSecurity)) return@get
                if (!call.requireValidOrigin(transportSecurity, httpsPort)) return@get
                call.secureApiResponse()
                val authenticated = authenticator.authorizeSpeaker(
                    call.request.cookies[SPEAKER_SESSION_COOKIE],
                )
                call.respondText(
                    "{\"requiresPin\":${authenticator.speakerRequiresPin}," +
                        "\"authenticated\":$authenticated}",
                    ContentType.Application.Json,
                )
            } finally {
                admission.close()
            }
        }

        route("/api/mic/join") {
            install(RequestBodyLimit) { bodyLimit { 32 } }
            post {
                val admission = call.acquireAdmission(
                    admissionController,
                    LocalRequestKind.ONBOARDING,
                ) ?: return@post
                try {
                    if (!call.requireValidHost(expectedHost)) return@post
                    if (!call.requireHttpsMicRequest(transportSecurity)) return@post
                    if (!call.hasValidBrowserOrigin(transportSecurity, httpsPort)) {
                        call.secureApiResponse()
                        call.respondText("Invalid Origin", status = HttpStatusCode.Forbidden)
                        return@post
                    }
                    call.secureApiResponse()
                    val suppliedPin = call.receiveText().trim().toCharArray()
                    when (val result = authenticator.joinSpeaker(
                        suppliedPin,
                        call.guideCastRemoteAddress(),
                    )) {
                        is PinJoinResult.Success -> {
                            // Keep the bearer out of page URLs, JavaScript memory and logs. The
                            // browser attaches this host-only cookie only to the TLS origin.
                            call.response.header(
                                HttpHeaders.SetCookie,
                                "$SPEAKER_SESSION_COOKIE=${result.token}; Path=/; " +
                                    "Max-Age=$SPEAKER_SESSION_MAX_AGE_SECONDS; Secure; " +
                                    "HttpOnly; SameSite=Strict",
                            )
                            call.respondText(
                                "{\"authenticated\":true}",
                                ContentType.Application.Json,
                            )
                        }

                        PinJoinResult.Invalid -> call.respondText(
                            "Invalid PIN",
                            status = HttpStatusCode.Unauthorized,
                        )

                        PinJoinResult.RateLimited -> call.respondText(
                            "Too many attempts",
                            status = HttpStatusCode.TooManyRequests,
                        )
                    }
                } finally {
                    admission.close()
                }
            }
        }

        webSocket("/ws/speaker-input") {
            val admission = when (val decision = admissionController.tryAcquire(
                call.guideCastRemoteAddress(),
                LocalRequestKind.WEBSOCKET_HANDSHAKE,
            )) {
                is LocalRequestAdmission.Allowed -> decision.lease
                is LocalRequestAdmission.Rejected -> {
                    close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Request limit reached"))
                    return@webSocket
                }
            }
            try {
                if (!call.hasValidHost(expectedHost)) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid host"))
                    return@webSocket
                }
                if (requireTlsSpeakerTransport && !call.isHttpsMicRequest(transportSecurity)) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "HTTPS required"))
                    return@webSocket
                }
                if (!call.hasValidBrowserOrigin(transportSecurity, httpsPort)) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid or missing origin"))
                    return@webSocket
                }
                val candidateToken = call.request.cookies[SPEAKER_SESSION_COOKIE]
                    // Unit/debug modules without the TLS proxy retain the old query handshake so
                    // their in-process transport can exercise PCM framing. Production HTTPS never
                    // accepts a speaker token in the URL.
                    ?: if (!requireTlsSpeakerTransport) {
                        call.request.queryParameters["speakerToken"]
                            ?: call.request.queryParameters["token"]
                    } else null
                if (!authenticator.authorizeSpeaker(candidateToken)) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized speaker"))
                    return@webSocket
                }

                val lease = when (val leaseResult = speakerLeaseManager.tryAcquire(call.guideCastRemoteAddress())) {
                    is SpeakerLeaseResult.Granted -> leaseResult.lease
                    is SpeakerLeaseResult.Busy -> {
                        close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Speaker session active"))
                        return@webSocket
                    }
                }
                try {
                    val sessionGeneration = onSpeakerSessionOpened()
                    try {
                        suspend fun sendInputState() {
                            send(
                                Frame.Text(
                                    """{"type":"input-state","ready":${isSpeakerInputReady()}}""",
                                ),
                            )
                        }
                        sendInputState()
                        var expectedSeq = 0
                        var windowStartNanos = System.nanoTime()
                        var framesInWindow = 0
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                if (frame.readText() == "probe") sendInputState()
                            } else if (frame is Frame.Binary) {
                                val bytes = frame.readBytes()
                                if (bytes.size >= 6 && (bytes.size - 4) % 2 == 0 && (bytes.size - 4) <= 16384) {
                                    val now = System.nanoTime()
                                    if (!speakerLeaseManager.recordActivity(lease.generation, now)) {
                                        close(
                                            CloseReason(
                                                CloseReason.Codes.VIOLATED_POLICY,
                                                "Speaker lease expired",
                                            ),
                                        )
                                        return@webSocket
                                    }

                                    if (now - windowStartNanos >= 1_000_000_000L) {
                                        windowStartNanos = now
                                        framesInWindow = 0
                                    }
                                    framesInWindow++
                                    if (framesInWindow > 60) {
                                        continue
                                    }

                                    val seq = ((bytes[0].toInt() and 0xFF) shl 24) or
                                        ((bytes[1].toInt() and 0xFF) shl 16) or
                                        ((bytes[2].toInt() and 0xFF) shl 8) or
                                        (bytes[3].toInt() and 0xFF)

                                    if (seq >= expectedSeq) {
                                        expectedSeq = seq + 1
                                        val pcmBytes = bytes.copyOfRange(4, bytes.size)
                                        val inputReady = isSpeakerInputReady()
                                        if (inputReady) {
                                            onSpeakerFrameReceived(pcmBytes, sessionGeneration)
                                        }

                                        if (seq % 25 == 0) {
                                            send(
                                                Frame.Text(
                                                    """{"type":"ack","seq":$seq,"accepted":$inputReady}""",
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        onSpeakerSessionClosed(sessionGeneration)
                    }
                } finally {
                    lease.close()
                }
            } finally {
                admission.close()
            }
        }
        get("/") {
            val admission = call.acquireAdmission(
                admissionController,
                LocalRequestKind.PAGE_ASSET,
            ) ?: return@get
            try {
                if (!call.requireValidHost(expectedHost)) return@get
                call.secureBrowserResponse(transportSecurity, httpsPort)
                call.respondBytes(assets.html, ContentType.Text.Html)
            } finally {
                admission.close()
            }
        }

        get("/{channel}") {
            val admission = call.acquireAdmission(
                admissionController,
                LocalRequestKind.PAGE_ASSET,
            ) ?: return@get
            try {
                if (!call.requireValidHost(expectedHost)) return@get
                val requestedChannel = call.parameters["channel"].orEmpty()
                if (requestedChannel == "jp") {
                    if (!streamSession.hasConfiguredChannel("ja")) {
                        call.secureBrowserResponse(transportSecurity, httpsPort)
                        call.respondText("Unknown channel", status = HttpStatusCode.NotFound)
                        return@get
                    }
                    call.secureBrowserResponse(transportSecurity, httpsPort)
                    call.response.header(HttpHeaders.Location, "/ja")
                    call.respondText(
                        "Japanese channel moved to /ja",
                        status = HttpStatusCode.PermanentRedirect,
                    )
                    return@get
                }
                if (!streamSession.hasConfiguredChannel(requestedChannel)) {
                    call.secureBrowserResponse(transportSecurity, httpsPort)
                    call.respondText("Unknown channel", status = HttpStatusCode.NotFound)
                    return@get
                }
                call.secureBrowserResponse(transportSecurity, httpsPort)
                call.respondBytes(assets.html, ContentType.Text.Html)
            } finally {
                admission.close()
            }
        }

        get("/player.js") {
            val admission = call.acquireAdmission(
                admissionController,
                LocalRequestKind.PAGE_ASSET,
            ) ?: return@get
            try {
                if (!call.requireValidHost(expectedHost)) return@get
                call.secureBrowserResponse(transportSecurity, httpsPort)
                call.respondBytes(assets.javascript, ContentType.parse("application/javascript"))
            } finally {
                admission.close()
            }
        }

        get("/player.css") {
            val admission = call.acquireAdmission(
                admissionController,
                LocalRequestKind.PAGE_ASSET,
            ) ?: return@get
            try {
                if (!call.requireValidHost(expectedHost)) return@get
                call.secureBrowserResponse(transportSecurity, httpsPort)
                call.respondBytes(assets.css, ContentType.Text.CSS)
            } finally {
                admission.close()
            }
        }

        get("/api/session") {
            val admission = call.acquireAdmission(
                admissionController,
                LocalRequestKind.ONBOARDING,
            ) ?: return@get
            try {
                if (!call.requireValidHost(expectedHost)) return@get
                if (!call.requireValidOrigin(transportSecurity, httpsPort)) return@get
                call.secureApiResponse()
                call.respondText(
                    "{\"access\":\"${authenticator.mode.name.lowercase()}\"}",
                    ContentType.Application.Json,
                )
            } finally {
                admission.close()
            }
        }

        route("/api/join") {
            install(RequestBodyLimit) { bodyLimit { 32 } }
            post {
                val admission = call.acquireAdmission(
                    admissionController,
                    LocalRequestKind.ONBOARDING,
                ) ?: return@post
                try {
                    if (!call.requireValidHost(expectedHost)) return@post
                    if (!call.requireValidOrigin(transportSecurity, httpsPort)) return@post
                    call.secureApiResponse()
                    if (authenticator.mode != BroadcastAccessMode.PIN) {
                        call.respondText("Not available", status = HttpStatusCode.NotFound)
                        return@post
                    }

                    val suppliedPin = call.receiveText().trim().toCharArray()
                    when (val result = authenticator.joinWithPin(
                        suppliedPin,
                        call.guideCastRemoteAddress(),
                    )) {
                        is PinJoinResult.Success -> call.respondText(
                            result.token,
                            ContentType.Text.Plain,
                        )

                        PinJoinResult.Invalid -> call.respondText(
                            "Invalid PIN",
                            status = HttpStatusCode.Unauthorized,
                        )

                        PinJoinResult.RateLimited -> call.respondText(
                            "Too many attempts",
                            status = HttpStatusCode.TooManyRequests,
                        )
                    }
                } finally {
                    admission.close()
                }
            }
        }

        get("/api/status") {
            val admission = call.acquireAdmission(
                admissionController,
                LocalRequestKind.READ_API,
            ) ?: return@get
            try {
                if (!call.requireValidHost(expectedHost)) return@get
                if (!call.requireValidOrigin(transportSecurity, httpsPort)) return@get
                call.secureApiResponse()
                if (!call.requireAuthorized(authenticator)) return@get
                val requestedChannel = call.request.queryParameters["channel"]
                if (requestedChannel != null && !streamSession.hasConfiguredChannel(requestedChannel)) {
                    call.respondText("Unknown channel", status = HttpStatusCode.NotFound)
                    return@get
                }
                call.respondText(
                    streamSession.toStatusJson(requestedChannel),
                    ContentType.Application.Json,
                )
            } finally {
                admission.close()
            }
        }

        get("/api/transcripts") {
            val admission = call.acquireAdmission(
                admissionController,
                LocalRequestKind.READ_API,
            ) ?: return@get
            try {
                if (!call.requireValidHost(expectedHost)) return@get
                if (!call.requireValidOrigin(transportSecurity, httpsPort)) return@get
                call.secureApiResponse()
                if (!call.requireAuthorized(authenticator)) return@get
                val requestedChannel = call.request.queryParameters["channel"]
                if (requestedChannel != null && !streamSession.hasConfiguredChannel(requestedChannel)) {
                    call.respondText("Unknown channel", status = HttpStatusCode.NotFound)
                    return@get
                }
                val versionedSnapshot = transcriptSnapshotProvider?.invoke()
                val representation = if (versionedSnapshot != null) {
                    transcriptResponseCache.responseFor(versionedSnapshot, requestedChannel)
                } else {
                    // Compatibility path for embedders that have not adopted revisioned snapshots.
                    transcriptResponseCache.responseFor(transcriptProvider(), requestedChannel)
                }
                call.response.header(HttpHeaders.ETag, representation.etag)
                if (
                    call.request.headers[HttpHeaders.IfNoneMatch]
                        .matchesIfNoneMatch(representation.etag)
                ) {
                    call.respond(HttpStatusCode.NotModified)
                    return@get
                }
                call.respondBytes(representation.body, ContentType.Application.Json)
            } finally {
                admission.close()
            }
        }

        webSocket("/ws/{channel}") {
            val admission = when (val decision = admissionController.tryAcquire(
                call.guideCastRemoteAddress(),
                LocalRequestKind.WEBSOCKET_HANDSHAKE,
            )) {
                is LocalRequestAdmission.Allowed -> decision.lease
                is LocalRequestAdmission.Rejected -> {
                    close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Request limit reached"))
                    return@webSocket
                }
            }
            try {
                if (!call.hasValidHost(expectedHost)) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid host"))
                    return@webSocket
                }
                if (!call.hasValidOrigin(transportSecurity, httpsPort)) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid origin"))
                    return@webSocket
                }
                if (!authenticator.authorize(call.request.queryParameters["token"])) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                    return@webSocket
                }

                val channelId = call.parameters["channel"] ?: run {
                    close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing channel"))
                    return@webSocket
                }
                val subscription = try {
                    streamSession.subscribe(channelId)
                } catch (_: ListenerLimitExceededException) {
                    close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Listener limit reached"))
                    return@webSocket
                } catch (_: StreamSessionSupersededException) {
                    close(CloseReason(CloseReason.Codes.SERVICE_RESTART, "Broadcast session replaced"))
                    return@webSocket
                } catch (_: IllegalStateException) {
                    close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Unknown channel"))
                    return@webSocket
                }

                // Keep the remote-scoped lease for the full socket lifetime. StreamSession still
                // enforces the global 50-listener/channel-fairness limit, while this lease stops
                // one handset (or one abusive hotspot peer) from occupying every listener slot.
                try {
                    send(Frame.Text(subscription.descriptor.toConfigJson(streamSession.generation)))
                    coroutineScope {
                        val outgoingAudio = launch {
                            for (audioFrame in subscription.frames) {
                                send(Frame.Binary(fin = true, data = audioFrame.bytes))
                                // Queue admission only proves that the server accepted a frame.
                                // Record delivery after the WebSocket send completes successfully.
                                subscription.recordWebSocketDelivery(audioFrame)
                            }
                        }
                        val peerLifetime = launch {
                            // Ktor closes `incoming` when the peer sends Close or the transport is
                            // lost. Consuming it is essential: an audio-only loop otherwise stays
                            // suspended forever during silence and leaks both listener and IP lease.
                            for (frame in incoming) {
                                // Listener clients do not send application data. Drain any benign
                                // frame so protocol close/EOF remains observable.
                                if (frame is Frame.Close) break
                            }
                        }
                        try {
                            select<Unit> {
                                outgoingAudio.onJoin { }
                                peerLifetime.onJoin { }
                            }
                        } finally {
                            outgoingAudio.cancelAndJoin()
                            peerLifetime.cancelAndJoin()
                        }
                    }
                } finally {
                    subscription.close()
                }
            } finally {
                admission.close()
            }
        }
    }
}

private suspend fun ApplicationCall.acquireAdmission(
    controller: LocalRequestAdmissionController,
    kind: LocalRequestKind,
): Closeable? = when (val decision = controller.tryAcquire(
    guideCastRemoteAddress(),
    kind,
)) {
    is LocalRequestAdmission.Allowed -> decision.lease
    is LocalRequestAdmission.Rejected -> {
        secureApiResponse()
        response.header("Retry-After", decision.retryAfterSeconds.toString())
        respondText("Too many requests", status = HttpStatusCode.TooManyRequests)
        null
    }
}

/** Uses the proxy-attested socket peer on TLS and the accepted socket peer on plain HTTP. */
private fun ApplicationCall.guideCastRemoteAddress(): String =
    attributes.getOrNull(VERIFIED_TLS_PEER_ADDRESS) ?: request.local.remoteAddress

private fun String.isLoopbackSocketPeer(): Boolean {
    if (equals("localhost", ignoreCase = true)) return true
    return runCatching { InetAddress.getByName(this).isLoopbackAddress }.getOrDefault(false)
}

private suspend fun ApplicationCall.requireValidHost(expectedHost: String): Boolean {
    if (hasValidHost(expectedHost)) return true
    secureApiResponse()
    respondText("Invalid Host", status = HttpStatusCode(421, "Misdirected Request"))
    return false
}

private fun ApplicationCall.hasValidHost(expectedHost: String): Boolean {
    val supplied = request.host()
    return supplied.equals(expectedHost, ignoreCase = true) ||
        supplied == "127.0.0.1" ||
        supplied.equals("localhost", ignoreCase = true)
}

private suspend fun ApplicationCall.requireValidOrigin(
    transportSecurity: GuideCastTransportSecurity,
    httpsPort: Int?,
): Boolean {
    if (hasValidOrigin(transportSecurity, httpsPort)) return true
    secureApiResponse()
    respondText("Invalid Origin", status = HttpStatusCode.Forbidden)
    return false
}

private suspend fun ApplicationCall.requireHttpsMicRequest(
    transportSecurity: GuideCastTransportSecurity,
): Boolean {
    if (isHttpsMicRequest(transportSecurity)) return true
    secureApiResponse()
    respondText(
        "Browser microphone authentication requires HTTPS",
        status = HttpStatusCode(426, "Upgrade Required"),
    )
    return false
}

private fun ApplicationCall.isHttpsMicRequest(
    transportSecurity: GuideCastTransportSecurity,
): Boolean = transportSecurity == GuideCastTransportSecurity.TLS_TERMINATED

/**
 * Browser requests must come from the same serialized origin as the accepted Host. A missing
 * Origin is intentionally accepted for navigation and non-browser clients on the local network.
 */
private fun ApplicationCall.hasValidOrigin(
    transportSecurity: GuideCastTransportSecurity,
    httpsPort: Int?,
): Boolean {
    val suppliedHeaders = request.headers.getAll(HttpHeaders.Origin) ?: return true
    if (suppliedHeaders.size != 1) return false
    val serializedOrigin = suppliedHeaders.single()
    if (serializedOrigin.isBlank() || serializedOrigin != serializedOrigin.trim()) return false
    if (serializedOrigin.equals("null", ignoreCase = true)) return false

    val origin = runCatching { URI(serializedOrigin) }.getOrNull() ?: return false
    if (!origin.isAbsolute || origin.isOpaque) return false
    if (origin.rawUserInfo != null || !origin.rawPath.isNullOrEmpty()) return false
    if (origin.rawQuery != null || origin.rawFragment != null) return false
    val originHost = origin.host ?: return false

    val requestScheme = when (transportSecurity) {
        GuideCastTransportSecurity.PLAIN_HTTP -> "http"
        GuideCastTransportSecurity.TLS_TERMINATED -> "https"
    }
    val originScheme = origin.scheme?.lowercase() ?: return false
    if (originScheme != requestScheme) return false
    if (!originHost.equals(request.host(), ignoreCase = true)) return false

    val originPort = when {
        origin.port >= 0 -> origin.port
        originScheme == "https" -> 443
        else -> 80
    }
    val externalPort = when (transportSecurity) {
        GuideCastTransportSecurity.PLAIN_HTTP -> request.local.localPort
        GuideCastTransportSecurity.TLS_TERMINATED -> httpsPort ?: return false
    }
    return originPort == externalPort
}

/**
 * Browser WebSocket requests MUST include an Origin header that matches the request's host/port.
 * Missing Origin is strictly rejected on the browser speaker endpoint.
 */
private fun ApplicationCall.hasValidBrowserOrigin(
    transportSecurity: GuideCastTransportSecurity,
    httpsPort: Int?,
): Boolean {
    val suppliedHeaders = request.headers.getAll(HttpHeaders.Origin) ?: return false
    if (suppliedHeaders.size != 1) return false
    return hasValidOrigin(transportSecurity, httpsPort)
}

private suspend fun ApplicationCall.requireAuthorized(
    authenticator: BroadcastSessionAuthenticator,
): Boolean {
    val bearer = request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substringAfter(' ')
    if (authenticator.authorize(bearer)) return true
    respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
    return false
}

private fun ApplicationCall.secureBrowserResponse(
    transportSecurity: GuideCastTransportSecurity,
    httpsPort: Int?,
    allowMicrophone: Boolean = false,
) {
    secureApiResponse()
    val host = request.host()
    val isHttps = transportSecurity == GuideCastTransportSecurity.TLS_TERMINATED
    val port = if (isHttps) httpsPort ?: request.local.localPort else request.local.localPort
    val wsScheme = if (isHttps) "wss" else "ws"
    val wsTarget = if ((wsScheme == "ws" && port == 80) || (wsScheme == "wss" && port == 443)) {
        "$wsScheme://$host"
    } else {
        "$wsScheme://$host:$port"
    }
    response.header(
        "Content-Security-Policy",
        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
            "connect-src 'self' $wsTarget; img-src 'self' data:; object-src 'none'; " +
            "base-uri 'none'; frame-ancestors 'none'; form-action 'self'",
    )
    response.header("X-Frame-Options", "DENY")
    val microphonePolicy = if (allowMicrophone) "microphone=(self)" else "microphone=()"
    response.header("Permissions-Policy", "camera=(), $microphonePolicy, geolocation=()")
}

private fun ApplicationCall.secureApiResponse() {
    response.header(HttpHeaders.CacheControl, "no-store, max-age=0")
    response.header(HttpHeaders.Pragma, "no-cache")
    response.header("X-Content-Type-Options", "nosniff")
    response.header("Referrer-Policy", "no-referrer")
}

private fun StreamSession.hasConfiguredChannel(channelId: String): Boolean =
    descriptor(channelId) != null

private fun StreamSession.toStatusJson(requestedChannel: String? = null): String = buildString {
    val snapshot = observabilitySnapshot()
    // A language URL scopes translated content, but always offers the live original input too.
    val visibleChannels = channels.filter {
        requestedChannel == null || it.id == requestedChannel || it.id == "source"
    }
    val listenerCount = requestedChannel?.let { snapshot.listenersByChannel[it] ?: 0 }
        ?: snapshot.totalListeners
    val droppedFrames = requestedChannel?.let { snapshot.droppedFramesByChannel[it] ?: 0L }
        ?: snapshot.droppedFrames
    val webSocketDeliveredFrames = requestedChannel?.let {
        snapshot.webSocketDeliveredFramesByChannel[it] ?: 0L
    } ?: snapshot.webSocketDeliveredFrames
    append("{\"generation\":")
    append(generation)
    append(",\"active\":")
    append(snapshot.isActive)
    append(",\"scope\":\"")
    append(if (requestedChannel == null) "session" else "channel")
    append('"')
    append(",\"listenerCount\":")
    append(listenerCount)
    append(",\"maxListeners\":")
    append(maxListeners)
    append(",\"droppedFrames\":")
    append(droppedFrames)
    append(",\"webSocketDeliveredFrames\":")
    append(webSocketDeliveredFrames)
    append(",\"channels\":[")
    visibleChannels.forEachIndexed { index, channel ->
        if (index > 0) append(',')
        append("{\"id\":\"")
        append(channel.id.jsonEscaped())
        append("\",\"name\":\"")
        append(channel.displayName.jsonEscaped())
        append("\",\"languageTag\":\"")
        append(channel.languageTag.jsonEscaped())
        append("\",\"sampleRate\":")
        append(channel.sampleRateHz)
        append(",\"listenerCount\":")
        append(snapshot.listenersByChannel[channel.id] ?: 0)
        append(",\"droppedFrames\":")
        append(snapshot.droppedFramesByChannel[channel.id] ?: 0)
        append(",\"webSocketDeliveredFrames\":")
        append(snapshot.webSocketDeliveredFramesByChannel[channel.id] ?: 0)
        append(",\"lastWebSocketDeliveredSequence\":")
        append(snapshot.lastWebSocketDeliveredSequenceByChannel[channel.id] ?: "null")
        append(",\"listenerPath\":\"/")
        append(channel.id.jsonEscaped())
        append('"')
        append('}')
    }
    append("]}")
}

internal fun List<GuideCastTranscriptLine>.toTranscriptsJson(
    requestedChannel: String? = null,
): String {
    val transcriptLines = this
    return buildString {
        append('{').append("\"transcripts\":").append('[')
        var index = 0
        for (unfilteredLine in transcriptLines) {
            if (index > 0) append(',')
            val line = if (requestedChannel == null) {
                unfilteredLine
            } else {
                unfilteredLine.forChannel(requestedChannel)
            }
            append(toTranscriptJson(line))
            index += 1
        }
        append("],\"count\":").append(size)
        append('}')
    }
}

private fun GuideCastTranscriptLine.forChannel(channelId: String): GuideCastTranscriptLine = copy(
    translations = translations.filterKeys { it == channelId },
    translationLatencyMillis = translationLatencyMillis.filterKeys { it == channelId },
    firstAudioLatencyMillis = firstAudioLatencyMillis.filterKeys { it == channelId },
    synthesisLatencyMillis = synthesisLatencyMillis.filterKeys { it == channelId },
)

private fun toTranscriptJson(line: GuideCastTranscriptLine): String = buildString {
    append('{')
    append("\"sequence\":").append(line.sequence).append(',')
    append("\"sourceText\":\"").append(line.sourceText.jsonEscaped()).append('\"').append(',')
    append("\"isFinal\":").append(line.isFinal).append(',')
    append("\"capturedAtElapsedRealtimeNanos\":").append(line.capturedAtElapsedRealtimeNanos).append(',')
    append("\"translations\":").append(line.translations.toJsonObject()).append(',')
    append("\"translationLatencyMillis\":").append(line.translationLatencyMillis.toJsonLongMap()).append(',')
    append("\"firstAudioLatencyMillis\":").append(line.firstAudioLatencyMillis.toJsonLongMap()).append(',')
    append("\"synthesisLatencyMillis\":").append(line.synthesisLatencyMillis.toJsonLongMap())
    append('}')
}

private fun Map<String, String>.toJsonObject(): String = buildString {
    append('{')
    entries.forEachIndexed { index, entry ->
        if (index > 0) append(',')
        append('"').append(entry.key.jsonEscaped()).append('"')
        append(':')
        append('"').append(entry.value.jsonEscaped()).append('"')
    }
    append('}')
}

private fun Map<String, Long>.toJsonLongMap(): String = buildString {
    append('{')
    entries.forEachIndexed { index, entry ->
        if (index > 0) append(',')
        append('"').append(entry.key.jsonEscaped()).append('"')
        append(':').append(entry.value)
    }
    append('}')
}

private fun AudioChannelDescriptor.toConfigJson(generation: Long): String =
    "{\"type\":\"config\",\"format\":\"pcm_s16le\",\"channels\":1," +
        "\"sampleRate\":$sampleRateHz,\"generation\":$generation}"

private fun String.jsonEscaped(): String = buildString(length) {
    for (character in this@jsonEscaped) {
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '<' -> append("\\u003c")
            '>' -> append("\\u003e")
            '&' -> append("\\u0026")
            '\u2028' -> append("\\u2028")
            '\u2029' -> append("\\u2029")
            else -> if (character.code < 0x20) {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
}

internal data class ListenerAssets(
    val html: ByteArray,
    val javascript: ByteArray,
    val css: ByteArray,
) {
    companion object {
        fun load(context: Context) = ListenerAssets(
            html = context.assets.readBytes("listener/index.html"),
            javascript = context.assets.readBytes("listener/i18n.js") + "\n".toByteArray() +
                context.assets.readBytes("listener/player.js"),
            css = context.assets.readBytes("listener/player.css"),
        )

        private fun android.content.res.AssetManager.readBytes(path: String): ByteArray =
            open(path).use { it.readBytes() }
    }
}

internal data class SpeakerAssets(
    val html: ByteArray,
    val javascript: ByteArray,
    val css: ByteArray,
    val setupTrustHtml: ByteArray? = null,
    val setupTrustJavascript: ByteArray? = null,
) {
    companion object {
        fun load(context: Context) = SpeakerAssets(
            html = context.assets.readBytes("speaker/speaker.html"),
            javascript = context.assets.readBytes("speaker/speaker.js"),
            css = context.assets.readBytes("speaker/speaker.css"),
            setupTrustHtml = runCatching { context.assets.readBytes("speaker/setup-trust.html") }.getOrNull(),
            setupTrustJavascript = runCatching {
                context.assets.readBytes("speaker/setup-trust.js")
            }.getOrNull(),
        )

        private fun android.content.res.AssetManager.readBytes(path: String): ByteArray =
            open(path).use { it.readBytes() }
    }
}

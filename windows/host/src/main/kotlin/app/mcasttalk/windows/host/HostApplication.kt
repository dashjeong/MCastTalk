package app.mcasttalk.windows.host

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.respondText
import io.ktor.server.request.path
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeout
import io.ktor.util.AttributeKey

private data class SocketGrant(val token: String, val account: LocalAccount)
private val socketGrantKey = AttributeKey<SocketGrant>("mcasttalk.auth.socket")

fun Application.mcastTalkModule(
    dataRoot: Path,
    directory: RoomDirectory = RoomDirectory(),
    socketHub: RoomSocketHub = RoomSocketHub(),
    accountServices: AccountServices? = null,
    lanHosts: Set<String> = emptySet(),
) {
    attributes.put(allowedLanHostsKey, lanHosts)
    val identity = DataRootGate.requireInitialized(dataRoot)
    val services = accountServices ?: AccountServices.open(dataRoot)
    val authorizations = SocketAuthorizations(services, this)
    monitor.subscribe(ApplicationStopped) {
        authorizations.close()
        if (accountServices == null) services.close()
    }
    val capacity = LanguageCapacity()
    val inferenceConfigured = java.nio.file.Files.exists(dataRoot.resolve("config/inference.properties"))
    val membership = RoomMembershipCoordinator(directory, socketHub) { people -> if(inferenceConfigured)capacity.validatePlan(people) }
    val interpreter = MeetingInterpreter(
        if (inferenceConfigured) ProcessMeetingInference(dataRoot) else null,
        membership, this, capacity,
    )
    monitor.subscribe(ApplicationStopped) { interpreter.close() }
    val signalLimiter = AccountRequestLimiter()
    val diagnostics = HostDiagnostics(dataRoot, membership, interpreter, lanHosts)

    intercept(ApplicationCallPipeline.Call) {
        val upgrade = call.request.headers.getAll(HttpHeaders.Upgrade).orEmpty()
        if (upgrade.any { it.contains("websocket", ignoreCase = true) } &&
            !isTrustedWebSocketOrigin(
                call.request.headers.getAll(HttpHeaders.Host),
                call.request.headers.getAll(HttpHeaders.Origin),
                call.request.local.scheme,
                call.request.local.localPort,
                lanHosts,
            )) {
            call.respondText("WebSocket origin is not allowed", status = HttpStatusCode.Forbidden)
            finish()
            return@intercept
        }
        if (upgrade.any { it.contains("websocket", ignoreCase = true) }) {
            if (!services.accounts.isInitialized()) {
                call.apiError(HttpStatusCode.ServiceUnavailable, "SETUP_REQUIRED", "Administrator setup is required")
                finish()
                return@intercept
            }
            val path = call.request.path()
            val isListenerSocket = path.startsWith("/ws/v1/rooms/") && path.endsWith("/listen")
            val candidateRoomId = if (isListenerSocket) {
                path.removePrefix("/ws/v1/rooms/").removeSuffix("/listen")
            } else {
                path.removePrefix("/ws/v1/rooms/")
            }
            try {
                requireValidIdentifier(candidateRoomId, "roomId")
            } catch (error: IllegalArgumentException) {
                call.apiError(HttpStatusCode.BadRequest, "INVALID_ROOM", "Room identifier is invalid")
                finish()
                return@intercept
            }
            val token = call.sessionToken()
            val account = token?.let { services.sessions.resolve(it, touch = false) }
            if (token == null || account == null) {
                call.apiError(HttpStatusCode.Unauthorized, "UNAUTHENTICATED", "Sign in is required")
                finish()
                return@intercept
            }
            if (!account.canEnterRoom(candidateRoomId)) {
                call.apiError(HttpStatusCode.Forbidden, "FORBIDDEN", "This account cannot enter the room")
                finish()
                return@intercept
            }
            call.attributes.put(socketGrantKey, SocketGrant(token, account))
        }
        call.response.headers.append(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self'; style-src 'self'; " +
                "connect-src 'self' ws://127.0.0.1:* ws://localhost:*; " +
                "img-src 'self' data:; media-src 'self' blob:; object-src 'none'; base-uri 'none'; " +
                "frame-ancestors 'none'; form-action 'self'",
        )
        call.response.headers.append("X-Content-Type-Options", "nosniff")
        call.response.headers.append("X-Frame-Options", "DENY")
        call.response.headers.append("Referrer-Policy", "no-referrer")
        call.response.headers.append(
            "Permissions-Policy",
            "camera=(self), microphone=(self), display-capture=(self), geolocation=()",
        )
        call.response.headers.append(HttpHeaders.CacheControl, "no-store, max-age=0")
    }

    install(WebSockets) {
        pingPeriodMillis = 20_000
        timeoutMillis = 15_000
        maxFrameSize = 256 * 1024
        masking = false
    }

    routing {
        accountRoutes(services)
        diagnosticRoutes(services, diagnostics)
        languageCapacityRoutes(services, interpreter, membership)
        get("/health/live") {
            call.respondText(
                text = encodeJson(
                    linkedMapOf(
                        "status" to "live",
                        "service" to "mcasttalk-windows-host",
                        "protocolVersion" to CONTROL_PROTOCOL_VERSION,
                    )
                ),
                contentType = ContentType.Application.Json,
            )
        }

        get("/health/ready") {
            call.respondText(
                text = encodeJson(
                    linkedMapOf(
                        "status" to "ready",
                        "dataSchemaVersion" to identity.schemaVersion,
                        "instanceId" to identity.instanceId,
                    )
                ),
                contentType = ContentType.Application.Json,
            )
        }

        get("/api/v1/capabilities") {
            call.respondText(
                text = encodeJson(hostCapabilities(interpreter.enabled, lanHosts)),
                contentType = ContentType.Application.Json,
            )
        }

        webSocket("/ws/v1/rooms/{roomId}") {
            val roomId = call.parameters["roomId"]
            if (roomId == null) {
                close(
                    CloseReason(
                        CloseReason.Codes.CANNOT_ACCEPT,
                        "roomId is required",
                    )
                )
                return@webSocket
            }
            try {
                requireValidIdentifier(roomId, "roomId")
            } catch (error: IllegalArgumentException) {
                send(Frame.Text(errorMessage("INVALID_ROOM", error.message.orEmpty())))
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "invalid room"))
                return@webSocket
            }

            var participant: Participant? = null
            var connection: RoomSocketHub.Connection? = null
            var lastVoiceSequence = -1
            val grant = call.attributes[socketGrantKey]
            val authorization = authorizations.register(grant.account, grant.token, roomId, this)
            try {
                val firstFrame = withTimeout(10.seconds) { incoming.receive() }
                val joinObject = requireTextObject(firstFrame)
                val account = authorization.account(touch = true) ?: return@webSocket
                require(joinObject.requiredString("type") == "JOIN_ROOM") { "First message must be JOIN_ROOM" }
                require(!interpreter.enabled || joinObject.optionalBoolean("aiTermsAccepted") == true) { "Read and accept the AI voice model usage conditions before joining" }
                val joinedParticipant = Participant(
                    id = joinObject.requiredString("participantId"),
                    displayName = account.displayName,
                    preferences = joinObject.toPreferences(),
                    accountId = account.id,
                    username = account.username,
                )
                val joinedConnection = membership.join(
                    roomId, joinedParticipant, this,
                    isAuthorized = { authorization.account(touch = false) != null },
                    onUnauthorized = authorization::reject,
                )
                // Failed joins never acquire cleanup ownership of another participant.
                participant = joinedParticipant
                connection = joinedConnection

                for (frame in incoming) {
                    if (authorization.account(touch = false) == null) break
                    when (frame) {
                        is Frame.Text -> {
                            if (frame.readText().length > MAX_CONTROL_MESSAGE_CHARS) {
                                send(
                                    Frame.Text(
                                        errorMessage(
                                            "MESSAGE_TOO_LARGE",
                                            "Control message exceeds $MAX_CONTROL_MESSAGE_CHARS characters",
                                        )
                                    )
                                )
                                continue
                            }
                            val message = parseObject(frame.readText())
                            val type = message.requiredString("type")
                            if (type in setOf("CHAT_SEND", "UPDATE_PREFERENCES") && authorization.account(touch = true) == null) break
                            when (type) {
                                "RTC_SIGNAL" -> {
                                    require(signalLimiter.allow("rtc:${grant.account.id}", 120)) { "RTC rate limit reached" }
                                    membership.signal(roomId, checkNotNull(participant), joinedConnection, message)
                                }
                                "PING" -> joinedConnection.send(pongMessage())
                                "CHAT_SEND" -> {
                                    try {
                                        val chat = parseChatSend(message)
                                        val sender = checkNotNull(participant)
                                        if (interpreter.enabled) interpreter.submit(roomId, sender, joinedConnection, text = chat)
                                        else membership.sendChat(roomId, sender, joinedConnection, chat)
                                    } catch (error: IllegalArgumentException) {
                                        joinedConnection.send(errorMessage("INVALID_CHAT", error.message ?: "Invalid chat message"))
                                    }
                                }
                                "UPDATE_PREFERENCES" -> {
                                  try {
                                    val preferences = message.toPreferences()
                                    participant = membership.updatePreferences(
                                        roomId,
                                        checkNotNull(participant),
                                        preferences,
                                    )
                                  } catch(error:IllegalArgumentException) {
                                    joinedConnection.send(errorMessage("PREFERENCES_REJECTED",error.message ?: "언어 설정을 적용하지 못했습니다. 기존 설정을 유지합니다."))
                                  }
                                }
                                "SUBTITLE_CHUNK" -> {
                                    // Only a future trusted inference worker may publish inference events.
                                    joinedConnection.send(errorMessage("INFERENCE_NOT_READY", "Server inference is not integrated"))
                                }
                                else -> send(
                                    Frame.Text(
                                        errorMessage(
                                            "UNSUPPORTED_MESSAGE",
                                            "Message type is not supported",
                                        )
                                    )
                                )
                            }
                        }
                        is Frame.Binary -> {
                            if (!interpreter.enabled) joinedConnection.send(errorMessage("MEDIA_NOT_READY", "Configure local inference models first"))
                            else try {
                                val voice = parseVoiceUtterance(frame.data)
                                require(voice.sequence > lastVoiceSequence) { "Duplicate or stale voice sequence" }
                                lastVoiceSequence = voice.sequence
                                interpreter.submit(roomId, checkNotNull(participant), joinedConnection, voice = voice)
                            } catch (error: IllegalArgumentException) {
                                joinedConnection.send(errorMessage("VOICE_REJECTED", error.message ?: "Invalid voice frame"))
                            }
                        }
                        is Frame.Close -> break
                        else -> Unit
                    }
                }
            } catch (_: ClosedReceiveChannelException) {
                // Normal browser disconnect.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                runCatching {
                    send(
                        Frame.Text(
                            errorMessage(
                                "INVALID_MESSAGE",
                                error.message ?: "Invalid control message",
                            )
                        )
                    )
                }
            } finally {
                authorization.close()
                participant?.let { current ->
                    val ownedConnection = connection
                    if (ownedConnection != null) {
                        membership.leave(roomId, current, ownedConnection)
                    }
                }
            }
        }

        webSocket("/ws/v1/rooms/{roomId}/listen") {
            val roomId = call.parameters["roomId"]
            if (roomId == null) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "roomId is required"))
                return@webSocket
            }
            try {
                requireValidIdentifier(roomId, "roomId")
            } catch (error: IllegalArgumentException) {
                send(Frame.Text(errorMessage("INVALID_ROOM", error.message.orEmpty())))
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "invalid room"))
                return@webSocket
            }

            var participant: Participant? = null
            var connection: RoomSocketHub.Connection? = null
            val grant = call.attributes[socketGrantKey]
            val authorization = authorizations.register(grant.account, grant.token, roomId, this)
            try {
                val firstFrame = withTimeout(10.seconds) { incoming.receive() }
                val joinObject = requireTextObject(firstFrame)
                val account = authorization.account(touch = true) ?: return@webSocket
                require(joinObject.requiredString("type") == "JOIN_ROOM") { "First message must be JOIN_ROOM" }
                require(!interpreter.enabled || joinObject.optionalBoolean("aiTermsAccepted") == true) { "Read and accept the AI voice model usage conditions before joining" }
                val joinedParticipant = Participant(
                    id = joinObject.requiredString("participantId"),
                    displayName = account.displayName,
                    preferences = joinObject.toPreferences(),
                    role = ParticipantRole.LISTENER,
                    accountId = account.id,
                    username = account.username,
                )
                val joinedConnection = membership.join(
                    roomId, joinedParticipant, this,
                    isAuthorized = { authorization.account(touch = false) != null },
                    onUnauthorized = authorization::reject,
                )
                participant = joinedParticipant
                connection = joinedConnection

                for (frame in incoming) {
                    if (authorization.account(touch = false) == null) break
                    when (frame) {
                        is Frame.Text -> {
                            if (frame.readText().length > MAX_CONTROL_MESSAGE_CHARS) {
                                send(
                                    Frame.Text(
                                        errorMessage(
                                            "MESSAGE_TOO_LARGE",
                                            "Control message exceeds $MAX_CONTROL_MESSAGE_CHARS characters",
                                        )
                                    )
                                )
                                continue
                            }
                            val message = parseObject(frame.readText())
                            when (message.requiredString("type")) {
                                "RTC_SIGNAL" -> {
                                    require(signalLimiter.allow("rtc:${grant.account.id}", 120)) { "RTC rate limit reached" }
                                    membership.signal(roomId, checkNotNull(participant), joinedConnection, message)
                                }
                                "PING" -> joinedConnection.send(pongMessage())
                                "UPDATE_PREFERENCES" -> {
                                    if (authorization.account(touch = true) == null) break
                                  try {
                                    val preferences = message.toPreferences()
                                    participant = membership.updatePreferences(
                                        roomId,
                                        checkNotNull(participant),
                                        preferences,
                                    )
                                  } catch(error:IllegalArgumentException) {
                                    joinedConnection.send(errorMessage("PREFERENCES_REJECTED",error.message ?: "언어 설정을 적용하지 못했습니다. 기존 설정을 유지합니다."))
                                  }
                                }
                                else -> send(
                                    Frame.Text(
                                        errorMessage(
                                            "NOT_PERMITTED",
                                            "Message type is not supported for listeners",
                                        )
                                    )
                                )
                            }
                        }
                        is Frame.Binary -> {
                            send(
                                Frame.Text(
                                    errorMessage(
                                        "NOT_PERMITTED",
                                        "Listeners cannot broadcast media",
                                    )
                                )
                            )
                        }
                        is Frame.Close -> break
                        else -> Unit
                    }
                }
            } catch (_: ClosedReceiveChannelException) {
                // Normal browser disconnect.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                runCatching {
                    send(
                        Frame.Text(
                            errorMessage(
                                "INVALID_MESSAGE",
                                error.message ?: "Invalid control message",
                            )
                        )
                    )
                }
            } finally {
                authorization.close()
                participant?.let { current ->
                    val ownedConnection = connection
                    if (ownedConnection != null) {
                        membership.leave(roomId, current, ownedConnection)
                    }
                }
            }
        }

        get("/listen/{roomId}") {
            val stream = this::class.java.classLoader.getResourceAsStream("web/index.html")
            if (stream != null) {
                call.respondText(stream.bufferedReader().use { it.readText() }, ContentType.Text.Html)
            } else {
                call.respondText("Not found", status = HttpStatusCode.NotFound)
            }
        }

        staticResources("/", "web", index = "index.html")
    }
}

private fun requireTextObject(frame: Frame): FlatJsonObject {
    require(frame is Frame.Text) { "Control messages must be text frames" }
    val text = frame.readText()
    require(text.length <= MAX_CONTROL_MESSAGE_CHARS) { "Control message is too large" }
    return parseObject(text)
}

private fun parseObject(text: String): FlatJsonObject =
    parseFlatJsonObject(text)

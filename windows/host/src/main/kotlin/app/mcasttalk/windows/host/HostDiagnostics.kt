package app.mcasttalk.windows.host

import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** Product capability declarations, not model discovery or inference benchmark results. */
internal fun hostCapabilities(inferenceConfigured: Boolean = false, lanHosts: Set<String> = emptySet()): Map<String, Any> = linkedMapOf(
    "controlProtocolVersion" to CONTROL_PROTOCOL_VERSION,
    "controlTransport" to "websocket-json",
    "roomChat" to true, "privateChat" to true, "chatTranslation" to inferenceConfigured,
    "authenticationRequired" to true, "accountManagement" to true,
    "authenticatedListener" to true, "anonymousListener" to false,
    "subtitleIngress" to "disabled", "serviceWorkspaces" to "planned",
    "operatorDiagnostics" to true,
    "mediaTransport" to "webrtc-lan-mesh", "publicNetworkReady" to false,
    "videoIntegrated" to true, "lanHttps" to lanHosts.isNotEmpty(), "lanHosts" to lanHosts.toList(),
    "maxMediaParticipants" to 8,
    "offlineInferenceRequired" to true,
    "sttIntegrated" to inferenceConfigured, "translationIntegrated" to inferenceConfigured, "ttsIntegrated" to inferenceConfigured,
    "inferenceReadiness" to if (inferenceConfigured) "see-authenticated-language-capacity" else "not-configured",
)

internal class HostDiagnostics(
    private val dataRoot: Path,
    private val membership: RoomMembershipCoordinator,
    private val interpreter: MeetingInterpreter? = null,
    private val lanHosts: Set<String> = emptySet(),
) {
    private val startedAtNanos = System.nanoTime()

    fun snapshot(): Map<String, Any?> {
        val runtime = Runtime.getRuntime()
        val committed = runtime.totalMemory()
        return linkedMapOf(
            "schemaVersion" to 1,
            "observedAt" to Instant.now().toString(),
            "deployment" to if (lanHosts.isEmpty()) "loopback-development-preview" else "lan-https-development-preview",
            "uptimeSeconds" to ((System.nanoTime() - startedAtNanos) / 1_000_000_000).coerceAtLeast(0),
            "membership" to membership.statistics(),
            "runtime" to mapOf(
                "availableProcessors" to runtime.availableProcessors(),
                "jvmHeapUsedBytes" to (committed - runtime.freeMemory()).coerceAtLeast(0),
                "jvmHeapCommittedBytes" to committed,
                "jvmHeapMaxBytes" to runtime.maxMemory(),
                // Unavailable is null, not a fictitious zero-byte disk.
                "workspaceUsableBytes" to runCatching { Files.getFileStore(dataRoot).usableSpace }.getOrNull(),
            ),
            "capabilities" to hostCapabilities(interpreter?.enabled == true, lanHosts),
            "inference" to mapOf("status" to (interpreter?.status ?: "not-configured"), "gpuCalibration" to "unverified",
                "translationBackend" to interpreter?.backend, "lastRequestLatencyMs" to interpreter?.lastLatencyMs,
                "lastAsrMs" to interpreter?.lastAsrMs,"firstCaptionMs" to interpreter?.firstCaptionMs,"firstAudioMs" to interpreter?.firstAudioMs,
                "fallbackReason" to interpreter?.fallbackReason, "sttBackend" to "cpu", "ttsBackend" to "cpu"),
            "languageCapacity" to interpreter?.capacity?.snapshot(),
            "privacy" to mapOf("chatPersisted" to false, "recordingEnabled" to false, "temporaryAsrFiles" to true),
        )
    }
}

internal fun Route.languageCapacityRoutes(services: AccountServices, interpreter: MeetingInterpreter, membership: RoomMembershipCoordinator) {
    post("/api/v1/language-plan") {
        call.accountApi(services,mutation=true,requireActor=true) { actor ->
            val body=call.accountBody();val room=body.requiredString("roomId");requireValidIdentifier(room,"roomId")
            if(!checkNotNull(actor).canEnterRoom(room))throw SecurityException("Room not permitted")
            membership.previewLanguagePlan(room,Participant(body.requiredString("participantId"),actor.displayName,body.toPreferences(),
                accountId=actor.id,role=if(body.optionalBoolean("listener")==true)ParticipantRole.LISTENER else ParticipantRole.SPEAKER))
            call.respondText(encodeJson(mapOf("allowed" to true)),ContentType.Application.Json)
        }
    }
    get("/api/v1/language-capacity") {
        call.accountApi(services, requireActor=true, touchSession=false) {
            call.respondText(encodeJson(mapOf("inferenceStatus" to interpreter.status,"capacity" to interpreter.capacity?.snapshot())),ContentType.Application.Json)
        }
    }
    post("/api/v1/admin/language-capacity") {
        call.accountApi(services, mutation=true, requireActor=true, requireAdmin=true) {
            val body=call.accountBody()
            val selected=(body.optionalString("languages") ?: "").split(',').filter(String::isNotBlank).toSet()
            membership.withLanguagePlans { rooms ->
                checkNotNull(interpreter.capacity).configure(selected,body.optionalBoolean("allowDelay") ?: false,
                    body.optionalBoolean("acknowledgeDelay") ?: false,if(body.contains("meetingLanguageLimit"))body.requiredInt("meetingLanguageLimit")else 0,rooms)
            }
            call.respondText(encodeJson(mapOf("capacity" to interpreter.capacity?.snapshot())),ContentType.Application.Json)
        }
    }
}

internal fun Route.diagnosticRoutes(services: AccountServices, diagnostics: HostDiagnostics) {
    get("/api/v1/admin/diagnostics") {
        call.accountApi(services, requireActor = true, requireAdmin = true, touchSession = false) {
            call.respondText(encodeJson(diagnostics.snapshot()), ContentType.Application.Json)
        }
    }
}

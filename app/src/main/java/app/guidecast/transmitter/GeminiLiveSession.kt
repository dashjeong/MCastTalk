package app.guidecast.transmitter

import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.audio.pcmS16LeSignalStats
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.Closeable

internal data class GeminiLiveStatus(val state: String = "미연결", val connections: Long = 0,
    val inputAudioTokens: Long? = null, val outputAudioTokens: Long? = null,
    val inputTextTokens: Long? = null, val outputTextTokens: Long? = null,
    val transcriptionUsage: OpenAiAudioUsage? = null,
    val unknownSessions: Long = 0,
    val lossesByLanguage: Map<String, Map<LiveAudioLoss, LiveAudioLossTotals>> = emptyMap(),
    val stalledLanguages: Set<String> = emptySet(),
    val unconfirmedActiveInputBytesByLanguage: Map<String, Long> = emptyMap(),
    val connectionIndexByLanguage: Map<String, Int> = emptyMap(),
    val latestReportedTokensByLanguage: Map<String, Map<String, Long?>> = emptyMap())

/** Shared status has no speech, credentials or raw provider errors. */
internal class GeminiLiveMonitor {
    private val mutable = MutableStateFlow(GeminiLiveStatus())
    val state = mutable.asStateFlow()
    fun loss(target: String, reason: LiveAudioLoss, bytes: Int) = update { state ->
        val language = state.lossesByLanguage[target].orEmpty()
        val previous = language[reason] ?: LiveAudioLossTotals()
        state.copy(lossesByLanguage = state.lossesByLanguage + (target to (language +
            (reason to previous.copy(frames = previous.frames + 1, bytes = previous.bytes + bytes)))))
    }
    @Synchronized fun update(transform: (GeminiLiveStatus) -> GeminiLiveStatus) { mutable.value = transform(mutable.value) }
}

/** A single language connection feeds any number of local listeners. No queued audio replay. */
internal class GeminiLiveSession(
    scope: CoroutineScope,
    private val options: TranslationApiOptions,
    private val target: String,
    private val settings: TranslationApiSettings,
    private val monitor: GeminiLiveMonitor,
    private val allowed: () -> Boolean,
    private val onEvent: suspend (GeminiLiveEvent) -> Unit,
    private val onFailure: (String) -> Unit,
    transport: GeminiLiveTransport = GeminiLiveTransport(),
    private val onDiagnostic: (ServiceFlowAction) -> Unit = {},
    private val onEnded: (NativeAudioEndReason) -> Unit = {},
    private val timing: NativeLiveTiming? = null,
    private val references: NativeReferenceSnapshot = NativeReferenceSnapshot(),
    private val onStatus: (String) -> Unit = {},
    private val onSourceDelayStatus: (previous: String?, next: String?) -> Unit = { _, _ -> },
    private val sourceLanguageTag: String? = null,
) : LiveAudioSession {
    private val termination = NativeAudioTermination(onEnded)
    private val sourceDelayWarning = GeminiLiveSourceDelayWarningOwner(
        allowed = { !termination.isEnded && allowed() && settings.authorized(options) && options.allowLiveAudio },
        changed = onSourceDelayStatus)
    private val usageSessionId = java.util.UUID.randomUUID().toString()
    private var usageReports = 0L
    private val readiness = NativeLiveConnectionReadiness()
    private val diagnostics = GeminiLiveCounters()
    private var wireDiagnostics = GeminiWireDiagnostics()
    private var connectionIndex = 0
    private fun recordWire() = RuntimeDiagnosticLog.record("gemini_wire", wireDiagnostics.snapshot()
        .put("session_id", usageSessionId).put("settings_revision", options.revision)
        .put("target", diagnosticTarget).put("connection_index", connectionIndex).toString())
    private val diagnosticTarget = geminiLiveTarget(target)
    private fun recordState(state: String) {
        runCatching { onDiagnostic(when (state) {
            "connecting" -> ServiceFlowAction.LIVE_CONNECTING
            "ready" -> ServiceFlowAction.LIVE_READY
            "turn_complete", "interrupted" -> ServiceFlowAction.LIVE_TURN_COMPLETE
            else -> ServiceFlowAction.LIVE_CLOSED
        }) }
        RuntimeDiagnosticLog.record("gemini_live", "target=$diagnosticTarget state=$state ${diagnostics.summary()}")
        recordWire()
    }
    private val input = GeminiLiveInput { reason, bytes ->
        timing?.stages?.inputLoss()
        monitor.loss(target, reason, bytes)
        diagnostics.loss(reason, bytes)
    }
    private val job = scope.launch(start = CoroutineStart.LAZY) {
        var sourceStalled = false
        try {
            check(allowed() && settings.authorized(options) && options.allowLiveAudio)
            val key = settings.key(options) ?: error("Gemini Live 키를 확인하세요.")
            val context = geminiSessionContext(options, references)
            transport.run(key, options.model, target, input.frames,
                authorized = { allowed() && settings.authorized(options) && options.allowLiveAudio },
                onReady = {
                    check(!termination.isEnded && allowed() && settings.authorized(options) && options.allowLiveAudio)
                    check(readiness.markReady()) { "Live connection already closed" }
                    input.markReady(); recordState("ready"); monitor.update { it.copy(state = "$target 연결됨 · 음성 입력 준비") }
                    if (connectionIndex > 1) runCatching { onStatus("Gemini Live $target 연결 갱신 완료 · 통역 재개 · 누락 계측은 별도 확인하세요.") }
                },
                onEvent = { event ->
                    check(!termination.isEnded && allowed() && settings.authorized(options))
                    sourceDelayWarning.sourceReceived(event.source)
                    sourceDelayWarning.providerResponse(event)
                    event.usage?.let { usage ->
                        usageReports++
                        val receipt = org.json.JSONObject().put("provider", "GEMINI_LIVE")
                            .put("model", options.model.takeIf { it in setOf(GEMINI_LIVE_TRANSLATE, GEMINI_LIVE_AGENT) } ?: "CUSTOM")
                            .put("session_id", usageSessionId).put("settings_revision", options.revision)
                            .put("target", diagnosticTarget)
                            .put("connection_index", connectionIndex).put("report_index", usageReports)
                            .put("scope", "RAW_PROVIDER_EVENT_PER_CONNECTION_NOT_BILLING_TOTAL")
                        for (field in listOf("promptTokenCount", "responseTokenCount", "totalTokenCount", "cachedContentTokenCount")) {
                            val number = runCatching { if (!usage.has(field) || usage.isNull(field)) null else
                                usage.getLong(field).also { require(it >= 0) } }.getOrNull()
                            receipt.put(field, number ?: org.json.JSONObject.NULL)
                        }
                        RuntimeDiagnosticLog.durableRecord("native_audio_usage", receipt.toString())
                        fun tokens(field: String, modality: String) = reportedLiveModalityTokens(usage, field, modality)
                        monitor.update { it.copy(inputAudioTokens = tokens("promptTokensDetails", "AUDIO"),
                            outputAudioTokens = tokens("responseTokensDetails", "AUDIO"),
                            inputTextTokens = tokens("promptTokensDetails", "TEXT"), outputTextTokens = tokens("responseTokensDetails", "TEXT"),
                            latestReportedTokensByLanguage = it.latestReportedTokensByLanguage + (target to
                                listOf("promptTokenCount", "responseTokenCount", "totalTokenCount", "cachedContentTokenCount")
                                    .associateWith { field -> (receipt.opt(field) as? Number)?.toLong() })) }
                    }
                    diagnostics.event(event)
                    if (event.finished || event.interrupted) recordState(if (event.interrupted) "interrupted" else "turn_complete")
                    onEvent(event)
                }, domainPrompt = context.domain, tone = options.tone,
                onAudioSent = { bytes ->
                    diagnostics.sent(bytes)
                    if (wireDiagnostics.sentPacketCount() % 10L == 0L) recordWire()
                }, timing = timing, diagnostics = wireDiagnostics,
                onAudioSendUnconfirmed = { bytes ->
                    timing?.stages?.inputLoss()
                    monitor.loss(target, LiveAudioLoss.INPUT_SEND_UNCONFIRMED, bytes)
                    diagnostics.loss(LiveAudioLoss.INPUT_SEND_UNCONFIRMED, bytes)
                },
                onPreparedAudioDiscarded = { bytes ->
                    monitor.loss(target, LiveAudioLoss.INPUT_ABANDONED, bytes)
                    diagnostics.loss(LiveAudioLoss.INPUT_ABANDONED, bytes)
                },
                interpreterInstructions = context.instructions, references = context.references, liveVoice = options.liveVoice, sourceLanguageTag = sourceLanguageTag,
                onConnectionAttempt = { index, resumed, observed ->
                    check(!termination.isEnded && allowed() && settings.authorized(options) && options.allowLiveAudio)
                    connectionIndex = index; usageReports = 0; wireDiagnostics = observed
                    RuntimeDiagnosticLog.durableRecord("native_audio_connection", org.json.JSONObject()
                        .put("provider", "GEMINI_LIVE").put("session_id", usageSessionId).put("connection_index", index)
                        .put("resumed", resumed).put("target", diagnosticTarget)
                        .put("model", options.model.takeIf { it in setOf(GEMINI_LIVE_TRANSLATE, GEMINI_LIVE_AGENT) } ?: "CUSTOM")
                        .put("settings_revision", options.revision).put("stage", "SESSION_ATTEMPT")
                        .put("actual_usage", org.json.JSONObject.NULL).toString())
                    recordState("connecting")
                    monitor.update { it.copy(state = "$target 연결 중 · $index 번째 연결", connections = it.connections + 1,
                        connectionIndexByLanguage = it.connectionIndexByLanguage + (target to index),
                        latestReportedTokensByLanguage = it.latestReportedTokensByLanguage - target,
                        inputAudioTokens = null, outputAudioTokens = null, inputTextTokens = null, outputTextTokens = null) }
                },
                onConnectionClosed = { index, observed ->
                    RuntimeDiagnosticLog.durableRecord("gemini_wire", observed.snapshot()
                        .put("session_id", usageSessionId).put("settings_revision", options.revision)
                        .put("target", diagnosticTarget).put("connection_index", index).toString())
                    RuntimeDiagnosticLog.durableRecord("native_audio_connection_closed", org.json.JSONObject()
                        .put("provider", "GEMINI_LIVE").put("session_id", usageSessionId).put("connection_index", index)
                        .put("target", diagnosticTarget).put("settings_revision", options.revision).put("usage_report_count", usageReports)
                        .put("final_usage", org.json.JSONObject.NULL).put("scope", "FINAL_USAGE_UNKNOWN_NOT_ZERO_OR_BILLING_TOTAL").toString())
                    monitor.update { it.copy(unknownSessions = it.unknownSessions + 1) }
                },
                onRenewalNotice = { notice ->
                    val message = "Gemini Live $target 연결 갱신 중 · 방송 주소와 이력 유지 · 일시 지연이 있을 수 있습니다."
                    monitor.update { it.copy(state = message) }; runCatching { onStatus(message) }
                    RuntimeDiagnosticLog.durableRecord("native_audio_renewal", org.json.JSONObject()
                        .put("provider", "GEMINI_LIVE").put("session_id", usageSessionId).put("connection_index", notice.connectionIndex)
                        .put("target", diagnosticTarget).put("settings_revision", options.revision).put("time_left_ms", notice.timeLeftMillis)
                        .put("scope", "CONTROLLED_HANDOFF_NOT_LOSSLESS_GUARANTEE").toString())
                },
                onSourceDelayNotice = { notice ->
                    val message = when (notice.kind) {
                        GeminiLiveSourceDelayKind.ENERGY_SOURCE_UNCONFIRMED ->
                            "$target · 말소리가 자막으로 확인되지 않습니다. 주변 소음과 마이크를 확인하세요. 중계는 계속됩니다."
                        GeminiLiveSourceDelayKind.OUTPUT_PENDING ->
                            "$target · 통역 응답이 아직 완료되지 않았습니다. 연결과 음성 출력을 확인하세요. 중계는 계속됩니다."
                    }
                    if (sourceDelayWarning.show(notice, message)) RuntimeDiagnosticLog.record("native_audio_source_delay",
                        org.json.JSONObject().put("provider", "GEMINI_LIVE").put("session_id", usageSessionId)
                            .put("settings_revision", options.revision).put("connection_index", connectionIndex)
                            .put("target", diagnosticTarget).put("source_progress_age_ms", notice.progress.sourceProgressAgeMillis)
                            .put("successful_active_input_bytes_since_source", notice.progress.activeInputBytesSinceSource)
                            .put("provider_response_age_ms", notice.providerResponseAgeMillis ?: org.json.JSONObject.NULL)
                            .put("reason", notice.kind.name)
                            .put("pending_output_observation", notice.pendingOutputObservation ?: org.json.JSONObject.NULL)
                            .put("pending_output_progress_observation", notice.pendingOutputProgressObservation ?: org.json.JSONObject.NULL)
                            .put("pending_output_age_ms", notice.pendingOutputAgeMillis ?: org.json.JSONObject.NULL)
                            .put("disposition", "CONTINUE_NO_RETRY")
                            .put("scope", if (notice.kind == GeminiLiveSourceDelayKind.OUTPUT_PENDING)
                                "LANE_SOURCE_OBSERVATION_OUTPUT_UNCONFIRMED_NOT_UTTERANCE_ALIGNMENT"
                                else "PCM_ENERGY_IS_NOT_SPEECH_OR_PROCESSING_ACK").toString())
                })
        } catch (cancelled: CancellationException) {
            termination.failed(cancelled, settings.authorized(options), allowed())
            if (cancelled is TimeoutCancellationException) onFailure("Gemini Live 연결 준비 시간 초과 · 다시 시작하세요.")
            throw cancelled
        } catch (error: Exception) {
            if (error is GeminiLiveSourceStalledFailure) {
                sourceStalled = true
                monitor.update { it.copy(stalledLanguages = it.stalledLanguages + target,
                    unconfirmedActiveInputBytesByLanguage = it.unconfirmedActiveInputBytesByLanguage +
                        (target to error.progress.activeInputBytesSinceSource)) }
                RuntimeDiagnosticLog.durableRecord("native_audio_source_stalled", org.json.JSONObject()
                    .put("provider", "GEMINI_LIVE").put("session_id", usageSessionId).put("target", diagnosticTarget)
                    .put("source_progress_age_ms", error.progress.sourceProgressAgeMillis)
                    .put("successful_active_input_bytes_since_source", error.progress.activeInputBytesSinceSource)
                    .put("scope", "SOURCE_PROCESSING_UNCONFIRMED_NOT_CONFIRMED_PCM_LOSS").toString())
            }
            termination.failed(error, settings.authorized(options), allowed())
            RuntimeDiagnosticLog.failure("gemini_live_$diagnosticTarget", error)
            onFailure(when {
                sourceStalled -> "Gemini Live $diagnosticTarget 원문 응답 60초 지연 · 해당 언어를 중지했습니다. 발화 처리 미확인 · 자동 재전송 없음."
                error is NativeAudioResponseTimeout && error.stage == NativeAudioResponseTimeoutStage.REQUEST_SEND ->
                    "Gemini Live $diagnosticTarget 음성 송신 3초 지연 · 해당 언어를 중지했습니다. 전송 여부 미확인 · 자동 재전송 없음."
                error is NativeAudioResponseTimeout && error.stage == NativeAudioResponseTimeoutStage.EVENT_CALLBACK ->
                    "Gemini Live $diagnosticTarget 출력 처리 지연 · 해당 언어를 중지했습니다. 자동 재전송 없음."
                error is GeminiLiveRenewalFailure -> "Gemini Live $diagnosticTarget 안전한 연결 갱신을 완료하지 못했습니다 · 방송 주소는 유지됩니다. 마이크를 다시 켜세요. 미전송·확인 불가 음성 자동 재전송 없음."
                else -> "Gemini Live $diagnosticTarget 중지 · ${onlineConnectionFailureResult(error).message} 미전송 음성은 재전송하지 않습니다."
            })
        } finally {
            sourceDelayWarning.endInput()
            termination.finish()
            readiness.close()
            input.close()
            RuntimeDiagnosticLog.durableRecord("gemini_wire", wireDiagnostics.snapshot()
                .put("session_id", usageSessionId).put("settings_revision", options.revision)
                .put("target", diagnosticTarget).put("connection_index", connectionIndex).toString())
            timing?.let { RuntimeDiagnosticLog.durableRecord("native_audio_timing", it.snapshot(close = true)
                .put("provider", "GEMINI_LIVE").put("session_id", usageSessionId)
                .put("target", diagnosticTarget)
                .put("end_reason", termination.reason.name)
                .put("model", options.model.takeIf { model -> model in setOf(GEMINI_LIVE_TRANSLATE, GEMINI_LIVE_AGENT) } ?: "CUSTOM")
                .put("settings_revision", options.revision).toString()) }
            recordState("closed")
            monitor.update { it.copy(state = if (sourceStalled) "$target 원문 응답 지연으로 중지 · 발화 처리 미확인 · 최종 사용량 미확인"
                else "$target 중지 · 최종 사용량 미확인") }
        }
    }
    init { job.invokeOnCompletion { cause ->
        if (cause != null) termination.failed(cause)
        termination.finish()
    } }
    override fun start() { job.start() }
    suspend fun awaitReady() = readiness.awaitReady()
    override fun offer(frame: PcmAudioFrame) {
        timing?.input(frame.bytes.size, frame.bytes.pcmS16LeSignalStats().let { it.rms >= 0.002f || it.peak >= 0.01f })
        diagnostics.capture(frame.bytes.size)
        if (!input.offer(frame.bytes)) {
            termination.request(NativeAudioEndReason.OVERLOAD)
            onFailure("Gemini Live 전송 대기열 초과 · 음성 일부가 미전송되어 해당 연결을 중지했습니다. 다시 시작하세요.")
            close()
        }
    }
    override fun close() {
        termination.request(NativeAudioEndReason.STOPPED); termination.finish()
        readiness.close(); input.close(); job.cancel()
    }
}

/** A missing modality is unknown; a reported zero remains zero. Cached counts are never added. */
internal fun reportedLiveModalityTokens(usage: org.json.JSONObject, field: String, modality: String): Long? = runCatching {
    val rows = usage.getJSONArray(field)
    val matching = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.getString("modality") == modality }
    if (matching.isEmpty()) null else matching.fold(0L) { total, row ->
        val count = row.getLong("tokenCount"); require(count >= 0); Math.addExact(total, count)
    }
}.getOrNull()

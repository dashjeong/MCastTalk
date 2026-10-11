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
    private val scope: CoroutineScope,
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
    private val useExplicitActivity: Boolean = false,
) : LiveAudioSession {
    private val termination = NativeAudioTermination(onEnded)
    private val drainWaitBudget = NativeDrainWaitBudget()
    private val drainTraceOrdinal = NativeDrainTraceOrdinals.next()
    private val inputDrain = NativeInputDrainState(onTrace = { event ->
        RuntimeDiagnosticLog.record("native_input_drain", event.detail(drainTraceOrdinal))
    })
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
    private fun recordProviderObservations(observed: GeminiWireDiagnostics, index: Int) {
        geminiProviderObservationRecords(observed.providerObservationSnapshot(), usageSessionId, index,
            diagnosticTarget, options.revision, drainTraceOrdinal).forEach { record ->
            RuntimeDiagnosticLog.durableRecord("gemini_provider_observation", record.toString())
        }
    }
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
                    drainWaitBudget.observe(event.source, event.translation, event.audio.any { it.isNotEmpty() })
                    if (event.finished || event.interrupted) drainWaitBudget.turnEnded()
                    sourceDelayWarning.sourceReceived(event.source)
                    sourceDelayWarning.providerResponse(event)
                    if (event.outputMissing) {
                        RuntimeDiagnosticLog.durableRecord("native_activity_incomplete", "target=$diagnosticTarget connection_index=$connectionIndex reason=NO_RECOGNIZED_OUTPUT")
                        onStatus("$target · 일부 발화의 통역 응답이 없습니다. 다음 발화는 계속 처리합니다.")
                    }
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
                interpreterInstructions = context.instructions, references = context.references, liveVoice = options.liveVoice, sourceLanguageTag = sourceLanguageTag, inputDrain = inputDrain, drainTraceOrdinal = drainTraceOrdinal,
                explicitActivity = useExplicitActivity, inputEvents = input.events, inputPaused = { input.isPaused },
                onPendingInputBoundary = {
                    RuntimeDiagnosticLog.durableRecord("native_pending_boundary", org.json.JSONObject()
                        .put("session_id", usageSessionId).put("target", diagnosticTarget)
                        .put("connection_index", connectionIndex).put("source_idle_threshold_ms", 5_000)
                        .put("stage", "EOS_SENT_RECEIVER_RETAINED")
                        .put("scope", "PROCESSING_REQUEST_NOT_OUTPUT_OR_COMPLETION_ACK").toString())
                },
                onConnectionAttempt = { index, resumed, observed ->
                    check(!termination.isEnded && allowed() && settings.authorized(options) && options.allowLiveAudio)
                    drainWaitBudget.invalidate()
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
                    recordProviderObservations(observed, index)
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
            if (inputDrain.completed) termination.request(if (inputDrain.successful)
                NativeAudioEndReason.INPUT_COMPLETED else NativeAudioEndReason.INPUT_INCOMPLETE)
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
                error is GeminiManualActivityFailure -> when (error.reason) {
                    GeminiManualActivityFailure.Reason.OVERFLOW -> "Gemini Live $diagnosticTarget 응답 대기 중 음성이 20초를 넘었습니다. 통역을 중지했습니다. 미전송 음성은 자동으로 재전송하지 않습니다."
                    GeminiManualActivityFailure.Reason.INTERRUPTED -> "Gemini Live $diagnosticTarget 통역 응답이 제공자에서 중단되었습니다. 미완료 자막을 확인한 뒤 마이크를 다시 켜세요. 자동 재전송 없음."
                    GeminiManualActivityFailure.Reason.NO_OUTPUT -> "Gemini Live $diagnosticTarget 통역 음성·자막 없이 응답이 끝났습니다. 입력 내용을 확인한 뒤 마이크를 다시 켜세요. 자동 재전송 없음."
                }
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
        inputDrain.sessionEnded(termination.reason == NativeAudioEndReason.INPUT_COMPLETED)
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
    private var pauseJob: Job? = null
    @Volatile private var pauseResult: CompletableDeferred<Boolean>? = null
    private var pauseOrdinal = 0L
    private fun recordInputPause(stage: String, ordinal: Long, elapsedNanos: Long? = null) {
        RuntimeDiagnosticLog.durableRecord("native_input_pause", org.json.JSONObject()
            .put("session_id", usageSessionId).put("target", diagnosticTarget)
            .put("settings_revision", options.revision).put("connection_index", connectionIndex)
            .put("pause_ordinal", ordinal).put("stage", stage)
            .put("elapsed_ms", elapsedNanos?.div(1_000_000) ?: org.json.JSONObject.NULL)
            .put("sent_packets", wireDiagnostics.sentPacketCount())
            .put("scope", "LOCAL_ORDERED_INPUT_BOUNDARY_NOT_PROVIDER_COMPLETION").toString())
    }
    override val supportsInputPause: Boolean get() = true
    override val canResumeInput: Boolean get() = job.isActive && !termination.isEnded &&
        allowed() && settings.authorized(options) && options.allowLiveAudio && input.canResume
    override fun beginInputPause(): Boolean {
        if (termination.isEnded || !allowed() || !settings.authorized(options)) return false
        val barrier = input.beginPause() ?: return false
        val result = CompletableDeferred<Boolean>().also { pauseResult = it }
        val ordinal = ++pauseOrdinal
        val pauseBudgetGeneration = drainWaitBudget.generation
        val started = System.nanoTime()
        recordInputPause("REQUEST", ordinal)
        pauseJob = scope.launch {
            var sent = false
            try {
                sent = withTimeoutOrNull(5_000) { input.sendPause(barrier); barrier.sent.await() } == true
                if (!sent) {
                    recordInputPause("FAILED", ordinal, System.nanoTime() - started)
                    termination.request(NativeAudioEndReason.TIMEOUT)
                    onFailure("Gemini Live $target 마이크 종료 신호 전송 실패 · 연결을 중지했습니다. 남은 통역은 미완료일 수 있습니다.")
                    close()
                } else {
                    drainWaitBudget.pauseSent(pauseBudgetGeneration)
                    recordInputPause(if (useExplicitActivity) "PAUSE_FENCE_QUEUED_RECEIVER_RETAINED"
                        else "EOS_SENT_RECEIVER_RETAINED", ordinal, System.nanoTime() - started)
                    monitor.update { it.copy(state = "$target 마이크 꺼짐 · 남은 응답 수신 중") }
                }
            } finally { result.complete(sent) }
        }
        return true
    }
    override suspend fun awaitInputPause(): Boolean = pauseResult?.await() == true
    override fun resumeInput(): Boolean {
        if (!canResumeInput || !input.resume()) return false
        drainWaitBudget.invalidate()
        recordInputPause("RESUMED_SAME_SESSION", pauseOrdinal)
        monitor.update { it.copy(state = "$target 연결됨 · 음성 입력 준비") }
        return true
    }
    override val supportsInputDrain: Boolean get() = true
    override fun beginInputDrain(): Boolean {
        if (termination.isEnded || !inputDrain.request()) return false
        sourceDelayWarning.endInput()
        input.endInput()
        monitor.update { it.copy(state = "$target 마이크 꺼짐 · 남은 통역 처리 중") }
        return true
    }
    override suspend fun awaitInputDrain(timeoutMillis: Long): Boolean {
        require(timeoutMillis > 0)
        val remaining = drainWaitBudget.remainingMillis(timeoutMillis)
        // Even an expired response window allows the bounded local EOS send and receiver cleanup.
        // Its expiry remains an unconfirmed completion, never an acknowledgement of pending speech.
        val effectiveWait = maxOf(remaining, minOf(timeoutMillis, 3_000L))
        RuntimeDiagnosticLog.durableRecord("native_input_drain_budget", org.json.JSONObject()
            .put("session_id", usageSessionId).put("target", diagnosticTarget)
            .put("connection_index", connectionIndex).put("remaining_response_ms", remaining)
            .put("effective_wait_ms", effectiveWait)
            .put("scope", "REUSED_PAUSED_RESPONSE_WAIT_NOT_COMPLETION_ACK").toString())
        val result = drainWaitBudget.awaitCompletion(timeoutMillis) { inputDrain.awaitEnded() }
        if (result == null) {
            termination.request(NativeAudioEndReason.TIMEOUT)
            close()
            return false
        }
        return result
    }
    override fun abortInputDrain(reason: NativeAudioEndReason) {
        termination.request(reason)
        close()
    }
    override fun close() {
        termination.request(NativeAudioEndReason.STOPPED); termination.finish()
        readiness.close(); input.close(); job.cancel(); pauseJob?.cancel(); pauseResult?.complete(false)
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

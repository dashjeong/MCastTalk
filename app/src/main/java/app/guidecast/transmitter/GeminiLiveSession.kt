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
    val lossesByLanguage: Map<String, Map<LiveAudioLoss, LiveAudioLossTotals>> = emptyMap())

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
) : LiveAudioSession {
    private val termination = NativeAudioTermination(onEnded)
    private val usageSessionId = java.util.UUID.randomUUID().toString()
    private var usageReports = 0L
    private val readiness = NativeLiveConnectionReadiness()
    private val diagnostics = GeminiLiveCounters()
    private val wireDiagnostics = GeminiWireDiagnostics()
    private fun recordWire() = RuntimeDiagnosticLog.record("gemini_wire", wireDiagnostics.snapshot()
        .put("session_id", usageSessionId).put("settings_revision", options.revision)
        .put("target", diagnosticTarget).toString())
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
        var connected = false
        try {
            check(allowed() && settings.authorized(options) && options.allowLiveAudio)
            val key = settings.key(options) ?: error("Gemini Live 키를 확인하세요.")
            connected = true
            RuntimeDiagnosticLog.durableRecord("native_audio_connection", org.json.JSONObject()
                .put("provider", "GEMINI_LIVE").put("session_id", usageSessionId)
                .put("model", options.model.takeIf { it in setOf(GEMINI_LIVE_TRANSLATE, GEMINI_LIVE_AGENT) } ?: "CUSTOM")
                .put("settings_revision", options.revision).put("stage", "SESSION_ATTEMPT")
                .put("actual_usage", org.json.JSONObject.NULL).toString())
            recordState("connecting")
            monitor.update { it.copy(state = "$target 연결 중", connections = it.connections + 1) }
            val context = geminiSessionContext(options, references)
            transport.run(key, options.model, target, input.frames,
                authorized = { allowed() && settings.authorized(options) && options.allowLiveAudio },
                onReady = {
                    check(readiness.markReady()) { "Live connection already closed" }
                    input.markReady(); recordState("ready"); monitor.update { it.copy(state = "$target 연결됨 · 음성 입력 준비") }
                },
                onEvent = { event ->
                    check(!termination.isEnded && allowed() && settings.authorized(options))
                    event.usage?.let { usage ->
                        usageReports++
                        val receipt = org.json.JSONObject().put("provider", "GEMINI_LIVE")
                            .put("model", options.model.takeIf { it in setOf(GEMINI_LIVE_TRANSLATE, GEMINI_LIVE_AGENT) } ?: "CUSTOM")
                            .put("session_id", usageSessionId).put("settings_revision", options.revision)
                            .put("report_index", usageReports).put("scope", "RAW_PROVIDER_EVENT_NOT_BILLING_TOTAL")
                        for (field in listOf("promptTokenCount", "responseTokenCount", "totalTokenCount", "cachedContentTokenCount")) {
                            val number = runCatching { if (!usage.has(field) || usage.isNull(field)) null else
                                usage.getLong(field).also { require(it >= 0) } }.getOrNull()
                            receipt.put(field, number ?: org.json.JSONObject.NULL)
                        }
                        RuntimeDiagnosticLog.durableRecord("native_audio_usage", receipt.toString())
                        fun tokens(field: String, modality: String) = reportedLiveModalityTokens(usage, field, modality)
                        monitor.update { it.copy(inputAudioTokens = tokens("promptTokensDetails", "AUDIO"),
                            outputAudioTokens = tokens("responseTokensDetails", "AUDIO"),
                            inputTextTokens = tokens("promptTokensDetails", "TEXT"), outputTextTokens = tokens("responseTokensDetails", "TEXT")) }
                    }
                    diagnostics.event(event)
                    if (event.finished || event.interrupted) recordState(if (event.interrupted) "interrupted" else "turn_complete")
                    onEvent(event)
                }, domainPrompt = context.domain, tone = options.tone,
                onAudioSent = { bytes ->
                    diagnostics.sent(bytes)
                    if (wireDiagnostics.sentPacketCount() % 10L == 0L) recordWire()
                }, timing = timing, diagnostics = wireDiagnostics,
                interpreterInstructions = context.instructions, references = context.references)
        } catch (cancelled: CancellationException) {
            termination.failed(cancelled, settings.authorized(options), allowed())
            if (cancelled is TimeoutCancellationException) onFailure("Gemini Live 연결 준비 시간 초과 · 다시 시작하세요.")
            throw cancelled
        } catch (error: Exception) {
            termination.failed(error, settings.authorized(options), allowed())
            RuntimeDiagnosticLog.failure("gemini_live_$diagnosticTarget", error)
            onFailure("Gemini Live $diagnosticTarget 중지 · ${onlineConnectionFailureResult(error).message} 미전송 음성은 재전송하지 않습니다.")
        } finally {
            termination.finish()
            readiness.close()
            input.close()
            RuntimeDiagnosticLog.durableRecord("gemini_wire", wireDiagnostics.snapshot()
                .put("session_id", usageSessionId).put("settings_revision", options.revision)
                .put("target", diagnosticTarget).toString())
            timing?.let { RuntimeDiagnosticLog.durableRecord("native_audio_timing", it.snapshot(close = true)
                .put("provider", "GEMINI_LIVE").put("session_id", usageSessionId)
                .put("model", options.model.takeIf { model -> model in setOf(GEMINI_LIVE_TRANSLATE, GEMINI_LIVE_AGENT) } ?: "CUSTOM")
                .put("settings_revision", options.revision).toString()) }
            recordState("closed")
            monitor.update { it.copy(state = "$target 중지 · 최종 사용량 미확인", unknownSessions = it.unknownSessions + if (connected) 1 else 0) }
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

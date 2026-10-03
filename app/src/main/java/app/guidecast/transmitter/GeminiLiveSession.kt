package app.guidecast.transmitter

import app.guidecast.core.stream.PcmAudioFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.Closeable

internal data class GeminiLiveStatus(val state: String = "미연결", val connections: Long = 0,
    val inputAudioTokens: Long? = null, val outputAudioTokens: Long? = null,
    val inputTextTokens: Long? = null, val outputTextTokens: Long? = null,
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
) : Closeable {
    private val diagnostics = GeminiLiveCounters()
    private val diagnosticTarget = geminiLiveTarget(target)
    private fun recordState(state: String) {
        runCatching { onDiagnostic(when (state) {
            "connecting" -> ServiceFlowAction.LIVE_CONNECTING
            "ready" -> ServiceFlowAction.LIVE_READY
            "turn_complete", "interrupted" -> ServiceFlowAction.LIVE_TURN_COMPLETE
            else -> ServiceFlowAction.LIVE_CLOSED
        }) }
        RuntimeDiagnosticLog.record("gemini_live", "target=$diagnosticTarget state=$state ${diagnostics.summary()}")
    }
    private val input = GeminiLiveInput { reason, bytes ->
        monitor.loss(target, reason, bytes)
        diagnostics.loss(reason, bytes)
    }
    private val job = scope.launch(start = CoroutineStart.LAZY) {
        var connected = false
        try {
            check(allowed() && settings.authorized(options) && options.allowLiveAudio)
            val key = settings.key(options) ?: error("Gemini Live 키를 확인하세요.")
            connected = true
            recordState("connecting")
            monitor.update { it.copy(state = "$target 연결 중", connections = it.connections + 1) }
            transport.run(key, options.model, target, input.frames,
                authorized = { allowed() && settings.authorized(options) && options.allowLiveAudio },
                onReady = { input.markReady(); recordState("ready"); monitor.update { it.copy(state = "$target 음성 전송 중 · 최대 60초") } },
                onEvent = { event ->
                    check(allowed() && settings.authorized(options))
                    event.usage?.let { usage ->
                        fun tokens(field: String, modality: String): Long? = runCatching {
                            val rows = usage.getJSONArray(field)
                            (0 until rows.length()).map { rows.getJSONObject(it) }
                                .filter { it.getString("modality") == modality }.sumOf { it.getLong("tokenCount").also { n -> require(n >= 0) } }
                        }.getOrNull()
                        monitor.update { it.copy(inputAudioTokens = tokens("promptTokensDetails", "AUDIO"),
                            outputAudioTokens = tokens("responseTokensDetails", "AUDIO"),
                            inputTextTokens = tokens("promptTokensDetails", "TEXT"), outputTextTokens = tokens("responseTokensDetails", "TEXT")) }
                    }
                    diagnostics.event(event)
                    if (event.finished || event.interrupted) recordState(if (event.interrupted) "interrupted" else "turn_complete")
                    onEvent(event)
                }, domainPrompt = if (options.interpretationMode == OnlineInterpretationMode.PROFESSIONAL) options.domainPrompt else "", tone = options.tone)
        } catch (cancelled: CancellationException) {
            if (cancelled is TimeoutCancellationException) onFailure("Gemini Live 60초 시험 종료 · 다시 시작하세요.")
            throw cancelled
        } catch (error: Exception) {
            RuntimeDiagnosticLog.failure("gemini_live_$diagnosticTarget", error)
            onFailure("Gemini Live $diagnosticTarget 중지 · ${onlineConnectionFailureResult(error).message} 미전송 음성은 재전송하지 않습니다.")
        } finally {
            input.close()
            recordState("closed")
            monitor.update { it.copy(state = "$target 중지 · 최종 사용량 미확인", unknownSessions = it.unknownSessions + if (connected) 1 else 0) }
        }
    }
    fun start() { job.start() }
    fun offer(frame: PcmAudioFrame) {
        diagnostics.capture(frame.bytes.size)
        if (!input.offer(frame.bytes)) {
            onFailure("Gemini Live 전송 대기열 초과 · 음성 일부가 미전송되어 해당 연결을 중지했습니다. 다시 시작하세요.")
            close()
        }
    }
    override fun close() { input.close(); job.cancel() }
}

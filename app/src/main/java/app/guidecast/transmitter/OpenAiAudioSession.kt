package app.guidecast.transmitter

import app.guidecast.core.stream.PcmAudioFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal interface LiveAudioSession : java.io.Closeable {
    fun start()
    fun offer(frame: PcmAudioFrame)
}

/** Covers a frame already dequeued by a playback worker when its turn is cancelled. */
internal class NativeAudioRetiredTurns {
    private val retired = linkedSetOf<Long>()
    private var expiredThrough = Long.MIN_VALUE
    @Synchronized fun retire(sequence: Long) {
        retired.add(sequence)
        while (retired.size > 2048) {
            val oldest = retired.min()
            retired.remove(oldest); expiredThrough = maxOf(expiredThrough, oldest)
        }
    }
    @Synchronized fun allows(sequence: Long?) = sequence == null || (sequence > expiredThrough && sequence !in retired)
    /** Retirement cannot slip between the last admission check and a nonblocking write. */
    @Synchronized fun writeIfAllowed(sequence: Long?, write: () -> Int): Int = if (allows(sequence)) write() else 0
}

internal class NativeAudioRecentIdentities(private val capacity: Int = 2048) {
    private val entries = linkedSetOf<String>()
    @Synchronized fun add(id: String): Boolean {
        if (!entries.add(id)) return false
        while (entries.size > capacity) entries.remove(entries.first())
        return true
    }
    @Synchronized operator fun contains(id: String) = id in entries
}

/** Idempotent termination also works for a lazy session closed before its coroutine starts. */
internal class NativeAudioTermination(private val onEnded: (NativeAudioEndReason) -> Unit) {
    private val requested = java.util.concurrent.atomic.AtomicReference<NativeAudioEndReason?>(null)
    private val ended = java.util.concurrent.atomic.AtomicBoolean(false)
    val isEnded: Boolean get() = ended.get()
    fun request(reason: NativeAudioEndReason) { requested.compareAndSet(null, reason) }
    fun failed(failure: Throwable, authorized: Boolean = true, active: Boolean = true) = request(when {
        failure is TimeoutCancellationException -> NativeAudioEndReason.TIMEOUT
        failure is NativeAudioOverload -> NativeAudioEndReason.OVERLOAD
        !authorized -> NativeAudioEndReason.CONSENT_REVOKED
        !active -> NativeAudioEndReason.SESSION_ENDED
        failure is CancellationException -> NativeAudioEndReason.STOPPED
        else -> NativeAudioEndReason.FAILURE
    })
    fun finish() {
        if (ended.compareAndSet(false, true)) onEnded(requested.get() ?: NativeAudioEndReason.FAILURE)
    }
}

/** A separate bounded, paced publisher keeps capture and server interruption events responsive. */
internal class NativeAudioOutputQueue(
    private val publish: suspend (OpenAiAudioEvent, ByteArray) -> Unit,
    private val discarded: (Int) -> Unit = {},
    private val maxQueuedBytes: Int = 40 * 48_000,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private class Chunk(val event: OpenAiAudioEvent, val bytes: ByteArray) {
        val accounted = java.util.concurrent.atomic.AtomicBoolean(true)
    }
    private val queuedBytes = java.util.concurrent.atomic.AtomicLong()
    private val queuedChunks = java.util.concurrent.atomic.AtomicInteger()
    private fun release(chunk: Chunk) { if (chunk.accounted.compareAndSet(true, false)) {
        queuedBytes.addAndGet(-chunk.bytes.size.toLong()); queuedChunks.decrementAndGet()
    } }
    private val queue = Channel<Chunk>(512, onUndeliveredElement = { release(it); discarded(it.bytes.size) })
    private val retired = NativeAudioRecentIdentities()
    private val retiredSequences = NativeAudioRetiredTurns()
    private val publicationLock = Mutex()
    init { require(maxQueuedBytes > 0) }
    @Synchronized fun offer(event: OpenAiAudioEvent): Boolean {
        if (event.inputId in retired || !retiredSequences.allows(event.inputSequence)) {
            event.audio.forEach { discarded(it.size) }; return true
        }
        val bytes = event.audio.sumOf { it.size.toLong() }
        if (event.audio.any { it.isEmpty() || it.size % 2 != 0 || it.size > 4800 } ||
            bytes > maxQueuedBytes - queuedBytes.get() || event.audio.size > 512 - queuedChunks.get()) {
            event.audio.forEach { discarded(it.size) }; return false
        }
        queuedBytes.addAndGet(bytes); queuedChunks.addAndGet(event.audio.size)
        for ((index, bytes) in event.audio.withIndex()) if (!queue.trySend(Chunk(event, bytes)).isSuccess) {
            event.audio.drop(index).forEach { queuedBytes.addAndGet(-it.size.toLong()); queuedChunks.decrementAndGet(); discarded(it.size) }
            return false
        }
        return true
    }
    suspend fun interrupt(inputId: String, sequence: Long? = null): Boolean =
        publicationLock.withLock {
            sequence?.let(retiredSequences::retire)
            retired.add(inputId)
        }
    suspend fun run() {
        var deadline: Long? = null
        for (chunk in queue) {
            release(chunk)
            var started = 0L
            val delivered = publicationLock.withLock {
                if (chunk.event.inputId in retired || !retiredSequences.allows(chunk.event.inputSequence)) { discarded(chunk.bytes.size); false }
                else { started = nowNanos(); publish(chunk.event, chunk.bytes); true }
            }
            if (!delivered) continue
            // Publication is part of each PCM interval. Reset a missed deadline rather than
            // bursting a backlog; accepted chunks retain their order and are never skipped.
            val prior = deadline
            val anchor = if (prior == null || started - prior > 1_000_000L) started else prior
            val nextDeadline = anchor + chunk.bytes.size * 1_000_000_000L / 48_000
            deadline = nextDeadline
            val remaining = nextDeadline - nowNanos()
            if (remaining > 0) delay((remaining + 999_999L) / 1_000_000L)
        }
    }
    fun close() { queue.cancel() }
}

/** Production event dispatch, shared by socket/session integration fixtures. Metadata is not a stop command. */
internal class NativeAudioEventRouter(
    private val output: NativeAudioOutputQueue,
    private val onCancelled: (OpenAiAudioEvent) -> Unit,
    private val onTranscript: suspend (OpenAiAudioEvent) -> Unit,
    private val onUsage: (OpenAiAudioEvent) -> Unit,
    private val onTerminal: (String) -> Unit = {},
) {
    private val usageSeen = NativeAudioRecentIdentities()
    private val terminalSeen = NativeAudioRecentIdentities()
    suspend fun accept(event: OpenAiAudioEvent) {
        if (event.interrupted && output.interrupt(event.inputId, event.inputSequence)) onCancelled(event)
        onTranscript(event)
        if (!output.offer(event)) throw NativeAudioOverload()
        val usageBoundary = event.usage != null ||
            (event.usageKind == NativeAudioUsageKind.RESPONSE && event.finished) ||
            (event.usageKind == NativeAudioUsageKind.TRANSCRIPTION && (event.sourceFinal || event.sourceFailed || event.sourceExpired))
        if (usageBoundary && usageSeen.add("${event.usageKind}:${event.inputId}")) {
            onUsage(event)
        }
        if (event.status in setOf("completed", "cancelled", "failed", "incomplete") && terminalSeen.add(event.inputId)) {
            onTerminal(requireNotNull(event.status))
        }
    }
}

internal class OpenAiAudioSegments(private val target: String, private val source: String,
    private val sessionId: Long? = null) {
    private val identities = linkedMapOf<String, Pair<Long, Long>>()
    private var nextSequence = 0L
    @Synchronized fun accept(event: OpenAiAudioEvent, now: Long): TranslationTranscriptLine {
        val (sequence, started) = identities.getOrPut(event.inputId) {
            while (identities.size >= 2048) identities.remove(identities.keys.first())
            (2_000_000_000L + (event.inputSequence ?: nextSequence++)) to now
        }
        return TranslationTranscriptLine(sequence, event.source, started,
            event.finished && event.sourceFinal && !event.sourceFailed && !event.sourceExpired && !event.interrupted && event.status == "completed",
            translations = if (event.translation.isBlank()) emptyMap() else mapOf(target to event.translation),
            sourceLanguageTag = source, liveSegmentLanguage = target,
            liveSourceFinal = event.sourceFinal,
            liveSourceFailed = event.sourceFailed,
            liveSourceExpired = event.sourceExpired,
            nativeAudioSessionId = sessionId,
            liveOutputState = when (event.status) {
                "queued" -> LiveOutputState.QUEUED
                "completed" -> if (event.interrupted) LiveOutputState.CANCELLED else LiveOutputState.GENERATED
                "cancelled" -> LiveOutputState.CANCELLED
                "incomplete", "failed" -> LiveOutputState.INCOMPLETE
                else -> if (event.interrupted) LiveOutputState.CANCELLED else LiveOutputState.GENERATING
            })
    }
}

/** Local ASR/TTS are bypassed. One socket serves the selected language's listeners. */
internal class OpenAiAudioSession(
    scope: CoroutineScope,
    private val options: TranslationApiOptions,
    private val source: String,
    private val target: String,
    private val settings: TranslationApiSettings,
    private val monitor: GeminiLiveMonitor,
    private val allowed: () -> Boolean,
    private val onTranscript: suspend (OpenAiAudioEvent) -> Unit,
    private val onAudio: suspend (OpenAiAudioEvent, ByteArray) -> Unit,
    private val onInterrupted: () -> Unit,
    private val onTurnInterrupted: (Long) -> Unit,
    private val onEnded: (NativeAudioEndReason) -> Unit,
    private val onFailure: (String) -> Unit,
    private val onDiagnostic: (ServiceFlowAction) -> Unit = {},
    transport: OpenAiAudioTransport = OpenAiAudioTransport(),
    private val references: NativeReferenceSnapshot = NativeReferenceSnapshot(),
) : LiveAudioSession {
    private val sessionId = UUID.randomUUID().toString()
    private val termination = NativeAudioTermination(onEnded)
    private val ready = CompletableDeferred<Unit>()
    private var responses = 0L
    private var transcriptions = 0L
    private val requests = linkedMapOf<String, Long>()
    private var requestAttempts = 0L
    private var requestsSent = 0L
    private val input = GeminiLiveInput { reason, bytes -> monitor.loss(target, reason, bytes) }
    private val output = NativeAudioOutputQueue(publish = { event, bytes ->
        check(!termination.isEnded && allowed() && settings.authorized(options) && options.allowLiveAudio)
        onAudio(event, bytes)
    }, discarded = { monitor.loss(target, LiveAudioLoss.OUTPUT_BLOCKED, it) })
    private val router = NativeAudioEventRouter(output,
        onCancelled = { event ->
            onTurnInterrupted(2_000_000_000L + requireNotNull(event.inputSequence))
        }, onTranscript = { event ->
            if (event.sourceFailed || event.sourceExpired) {
                onDiagnostic(if (event.sourceExpired) ServiceFlowAction.LIVE_CAPTION_EXPIRED else ServiceFlowAction.LIVE_CAPTION_FAILED)
                monitor.update { it.copy(state = if (event.sourceExpired)
                    "원문 자막 대기 만료 · 통역 음성과 다음 발화는 계속 처리"
                    else "원문 자막 인식 실패 · 통역 음성과 다음 발화는 계속 처리") }
            }
            onTranscript(event)
        }, onUsage = { event ->
            val usage = event.usage
            if (event.usageKind == NativeAudioUsageKind.RESPONSE) {
                responses++
                monitor.update { it.copy(inputAudioTokens = usage?.inputAudio, inputTextTokens = usage?.inputText,
                    outputAudioTokens = usage?.outputAudio, outputTextTokens = usage?.outputText) }
            } else {
                transcriptions++
                monitor.update { it.copy(transcriptionUsage = usage) }
            }
            RuntimeDiagnosticLog.durableRecord("native_audio_usage", org.json.JSONObject().put("provider", "OPENAI_REALTIME")
                .put("model", options.model.takeIf { it in OPENAI_REALTIME_MODELS } ?: "CUSTOM")
                .put("session_id", sessionId).put("input_sequence", event.inputSequence ?: org.json.JSONObject.NULL)
                .put("response_index", responses).put("kind", event.usageKind.name)
                .put("measurement_status", if (event.sourceExpired && event.usageKind == NativeAudioUsageKind.TRANSCRIPTION)
                    "EXPIRED" else if (usage == null) "UNKNOWN" else "PROVIDER_REPORTED")
                .put("status", event.status ?: "UNKNOWN").put("actual_usage", usage?.countsOnly() ?: org.json.JSONObject.NULL)
                .put("scope", "PER_INPUT_PROVIDER_REPORT_NOT_ACCOUNT_BILLING").toString())
        }, onTerminal = { status ->
            onDiagnostic(when (status) {
                "completed" -> ServiceFlowAction.LIVE_GENERATED
                "cancelled" -> ServiceFlowAction.LIVE_CANCELLED
                else -> ServiceFlowAction.LIVE_INCOMPLETE
            })
            monitor.update { it.copy(state = "OpenAI $target · " + when (status) {
                "completed" -> "통역 생성 완료 · 청취 미확인"
                "cancelled" -> "해당 통역 취소됨 · 다음 발화는 계속 처리"
                else -> "해당 통역 미완료 · 다음 발화는 계속 처리"
            }) }
        })
    private val job = scope.launch(start = CoroutineStart.LAZY) {
        var connected = false
        try {
            check(allowed() && settings.authorized(options) && options.allowLiveAudio && options.usesNativeLiveAudio)
            val key = settings.key(options) ?: error("Audio key unavailable")
            monitor.update { it.copy(state = "OpenAI $target 연결 중", connections = it.connections + 1) }
            onDiagnostic(ServiceFlowAction.LIVE_CONNECTING)
            connected = true
            RuntimeDiagnosticLog.durableRecord("native_audio_connection", org.json.JSONObject().put("provider", "OPENAI_REALTIME")
                .put("model", options.model.takeIf { it in OPENAI_REALTIME_MODELS } ?: "CUSTOM")
                .put("session_id", sessionId).put("settings_revision", options.revision)
                .put("stage", "SESSION_ATTEMPT").put("actual_usage", org.json.JSONObject.NULL).toString())
            coroutineScope {
                val publisher = launch { output.run() }
                try {
                    transport.run(key, options.model, source.substringBefore('-').lowercase(), target, input.frames,
                        { allowed() && settings.authorized(options) && options.allowLiveAudio },
                        onReady = { input.markReady(); ready.complete(Unit); onDiagnostic(ServiceFlowAction.LIVE_READY)
                            monitor.update { it.copy(state = "OpenAI $target 음성 전송 중") } },
                        onEvent = { event ->
                            check(!termination.isEnded && allowed() && settings.authorized(options))
                            router.accept(event)
                        }, domain = if (options.interpretationMode == OnlineInterpretationMode.PROFESSIONAL) options.domainPrompt else "", tone = options.tone,
                        onRequest = { id, sequence, sent ->
                            requests[id] = sequence
                            while (requests.size > 128) requests.remove(requests.keys.first())
                            if (sent) requestsSent++ else requestAttempts++
                            RuntimeDiagnosticLog.durableRecord("native_audio_request", "session_id=$sessionId input_sequence=$sequence " +
                                "phase=${if (sent) "SENT" else "ATTEMPT"} target_count=1")
                        }, interpreterInstructions = options.interpreterInstructions,
                        references = if (options.allowDomainReferences) references.payload else "")
                } finally { output.close(); publisher.cancel() }
            }
        } catch (cancelled: CancellationException) {
            termination.failed(cancelled, settings.authorized(options), allowed())
            if (cancelled is TimeoutCancellationException) onFailure("OpenAI 음성 연결 준비 시간 초과 · 다시 시작하세요. 미전송 음성은 재전송하지 않습니다.")
            throw cancelled
        } catch (failure: Exception) {
            termination.failed(failure, settings.authorized(options), allowed())
            onInterrupted()
            onFailure(if (failure is NativeAudioIdentityUncertain) "오래된 발화 식별을 확인할 수 없어 중지했습니다. 다시 시작하세요. 이전 음성을 자동 재전송하지 않습니다."
                else if (failure is NativeAudioOverload) "OpenAI 통역 대기열 초과 · 미완료 통역이 있어 중지했습니다. 음성을 자동 재전송하지 않습니다."
                else "OpenAI 음성 통역 중지 · ${onlineConnectionFailureResult(failure).message} 미전송 음성은 재전송하지 않습니다.")
            RuntimeDiagnosticLog.record("native_audio", "session_id=$sessionId state=FAILED responses=$responses")
        } finally {
            termination.finish()
            if (!ready.isCompleted) ready.completeExceptionally(IllegalStateException("Audio preparation incomplete"))
            input.close(); output.close(); onInterrupted(); onDiagnostic(ServiceFlowAction.LIVE_CLOSED)
            RuntimeDiagnosticLog.record("native_audio_session", "session_id=$sessionId attempts=$requestAttempts " +
                "sent=$requestsSent responses=$responses transcriptions=$transcriptions final_usage=UNKNOWN", true)
            monitor.update { it.copy(state = "OpenAI $target 중지 · 최종 사용량 미확인",
                unknownSessions = it.unknownSessions + if (connected) 1 else 0) }
        }
    }
    init { job.invokeOnCompletion { cause ->
        if (cause != null) termination.failed(cause)
        termination.finish()
        if (!ready.isCompleted) ready.completeExceptionally(IllegalStateException("Audio preparation stopped"))
    } }
    suspend fun awaitReady() = withTimeout(8_000) { ready.await() }
    override fun start() { job.start() }
    override fun offer(frame: PcmAudioFrame) {
        if (!input.offer(frame.bytes)) {
            termination.request(NativeAudioEndReason.OVERLOAD)
            onFailure("OpenAI 음성 입력 대기열 초과 · 중지했습니다. 다시 시작하세요."); close()
        }
    }
    override fun close() {
        termination.request(NativeAudioEndReason.STOPPED); termination.finish()
        input.close(); output.close(); job.cancel(); onInterrupted()
    }
}

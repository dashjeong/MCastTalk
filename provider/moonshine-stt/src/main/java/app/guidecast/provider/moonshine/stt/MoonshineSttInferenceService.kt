package app.guidecast.provider.moonshine.stt

import ai.moonshine.voice.JNI
import ai.moonshine.voice.MicTranscriber
import ai.moonshine.voice.TranscriptEvent
import ai.moonshine.voice.TranscriptLine
import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns every Moonshine native STT call in the non-exported `:stt_inference` process.
 *
 * All load/create/add/stop/free/close operations are serialized on one native thread. Binder PCM
 * delivery is acknowledged only after Moonshine consumes the frame, keeping the one-way Binder
 * queue bounded to one small transaction per recognition session.
 */
class MoonshineSttInferenceService : Service() {
    private val nativeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GuideCast-Moonshine-STT").apply {
            // Recognition is the realtime producer for subtitles and every TTS channel. Running
            // it below normal priority caused starvation when a Note 9 synthesized speech.
            priority = Thread.NORM_PRIORITY
        }
    }
    private val nativeDispatcher = nativeExecutor.asCoroutineDispatcher()
    private val serviceJob = SupervisorJob()
    private val nativeScope = CoroutineScope(serviceJob + nativeDispatcher)
    private val shuttingDown = AtomicBoolean(false)
    private val runtimeCloseGate = MoonshineSttRuntimeCloseGate()
    private val nativeOwners = MoonshineSttNativeOwnerRegistry<MicTranscriber>()
    private val cancelledSessions = MoonshineSttCancelledSessions()
    private val startingSessions = ConcurrentHashMap.newKeySet<Long>()
    private lateinit var processGeneration: MoonshineSttServiceGenerationGate.Generation

    // These fields are accessed only from nativeDispatcher.
    private var cachePrepared = false
    private val sessions = mutableMapOf<Long, NativeRecognitionSession>()

    private val binder = object : IGuideCastMoonshineStt.Stub() {
        override fun prepare(
            operationId: Long,
            languageTag: String,
            callback: IGuideCastMoonshineSttCallback,
        ) {
            if (!isValidOperation(operationId, languageTag, callback)) {
                callback.safely { onFinished(operationId) }
                return
            }
            nativeScope.launch {
                try {
                    ensureLoaded(operationId, callback)
                } catch (error: Throwable) {
                    reportPreparationFailure(operationId, callback, error)
                } finally {
                    // READY/error is the caller result. This distinct terminal acknowledgement is
                    // the only proof that JNI load has actually returned and may release the
                    // process-wide cold-load ticket after caller cancellation.
                    callback.safely { onFinished(operationId) }
                }
            }
        }

        override fun startRecognition(
            sessionId: Long,
            languageTag: String,
            sampleRateHz: Int,
            channelCount: Int,
            callback: IGuideCastMoonshineSttCallback,
        ) {
            val validationError = when {
                shuttingDown.get() -> "Moonshine 음성인식 작업 공간이 종료 중입니다."
                sessionId <= 0L -> "잘못된 Moonshine 음성인식 세션입니다."
                !isKorean(languageTag) -> "현재 Moonshine STT 원문 언어는 한국어만 지원합니다."
                sampleRateHz != REQUIRED_SAMPLE_RATE_HZ || channelCount != REQUIRED_CHANNEL_COUNT ->
                    "Moonshine STT는 16 kHz 모노 PCM만 지원합니다."
                !startingSessions.add(sessionId) -> "이미 사용 중인 Moonshine 음성인식 세션입니다."
                else -> null
            }
            if (validationError != null) {
                callback.safely {
                    onError(
                        sessionId,
                        MoonshineSttIpcProtocol.ERROR_INVALID_ARGUMENT,
                        validationError,
                    )
                }
                return
            }

            nativeScope.launch {
                try {
                    startSession(sessionId, callback)
                } catch (error: Throwable) {
                    cleanupFailedStart(sessionId)
                    callback.safely {
                        onError(
                            sessionId,
                            MoonshineSttIpcProtocol.ERROR_RECOGNITION,
                            error.safeMessage("Moonshine 음성인식을 시작하지 못했습니다."),
                        )
                    }
                } finally {
                    startingSessions.remove(sessionId)
                    // A stop that overtook this one-way start has now been consumed. IDs are never
                    // reused, so retaining it beyond the observed start only wastes tombstone space.
                    cancelledSessions.remove(sessionId)
                }
            }
        }

        override fun pushPcm(sessionId: Long, frameId: Long, pcm16Le: ByteArray) {
            val invalid = sessionId <= 0L || frameId <= 0L || pcm16Le.isEmpty() ||
                pcm16Le.size % Short.SIZE_BYTES != 0 ||
                pcm16Le.size > MoonshineSttIpcProtocol.MAX_PCM_TRANSACTION_BYTES
            if (invalid || shuttingDown.get()) {
                reportSessionError(
                    sessionId,
                    MoonshineSttIpcProtocol.ERROR_INVALID_ARGUMENT,
                    if (shuttingDown.get()) {
                        "Moonshine 음성인식 작업 공간이 종료되었습니다."
                    } else {
                        "16비트 PCM 프레임 형식 또는 크기가 올바르지 않습니다."
                    },
                )
                return
            }

            // AIDL already copied the byte array across the process boundary. Queue only this
            // one bounded copy; the client waits for onPcmConsumed before sending another.
            nativeScope.launch {
                val session = sessions[sessionId]
                if (session == null || session.closed.get() || sessionId in cancelledSessions) {
                    reportSessionError(
                        sessionId,
                        MoonshineSttIpcProtocol.ERROR_WORKER_STOPPED,
                        "Moonshine 음성인식 세션이 이미 종료되었습니다.",
                    )
                    return@launch
                }
                try {
                    requireNotNull(nativeOwners.activeOrNull()).addAudioToStream(
                        session.streamHandle,
                        pcm16Le.toNormalizedFloatPcm(),
                        REQUIRED_SAMPLE_RATE_HZ,
                    )
                    session.callback.safely {
                        onPcmConsumed(sessionId, frameId)
                    }
                } catch (error: Throwable) {
                    session.callback.safely {
                        onError(
                            sessionId,
                            MoonshineSttIpcProtocol.ERROR_RECOGNITION,
                            error.safeMessage("Moonshine PCM 처리에 실패했습니다."),
                        )
                    }
                    requestSessionStop(sessionId)
                }
            }
        }

        override fun stopRecognition(sessionId: Long) {
            requestSessionStop(sessionId)
        }

        override fun shutdown() {
            requestShutdown()
        }
    }

    override fun onCreate() {
        super.onCreate()
        processGeneration = MoonshineSttProcessGenerationGate.beginGeneration()
    }

    override fun onBind(intent: Intent?): IBinder? = if (shuttingDown.get()) null else binder

    override fun onDestroy() {
        requestShutdown()
        super.onDestroy()
    }

    private fun isValidOperation(
        operationId: Long,
        languageTag: String,
        callback: IGuideCastMoonshineSttCallback,
    ): Boolean {
        val message = when {
            shuttingDown.get() -> "Moonshine 음성인식 작업 공간이 종료 중입니다."
            operationId <= 0L -> "잘못된 Moonshine 준비 요청입니다."
            !isKorean(languageTag) -> "현재 Moonshine STT 원문 언어는 한국어만 지원합니다."
            else -> return true
        }
        callback.safely {
            onError(operationId, MoonshineSttIpcProtocol.ERROR_INVALID_ARGUMENT, message)
        }
        return false
    }

    private suspend fun ensureLoaded(
        operationId: Long,
        callback: IGuideCastMoonshineSttCallback,
    ): MicTranscriber {
        // A replacement Service can be constructed while a cancelled JNI call from the old
        // instance is still returning. Do not touch this generation's native runtime first.
        processGeneration.awaitPredecessorClosed()
        check(!shuttingDown.get()) { "Moonshine 음성인식 작업 공간이 종료 중입니다." }
        nativeOwners.activeOrNull()?.let { existing ->
            check(existing.isLoaded) {
                "Moonshine STT native 상태를 확인할 수 없어 새 모델 적재를 중단했습니다."
            }
            callback.sendStatus(
                operationId = operationId,
                readiness = MoonshineSttIpcProtocol.READINESS_READY,
                message = "한국어 Moonshine 스트리밍 음성인식 준비됨",
                progress = 1f,
            )
            return existing
        }
        nativeOwners.requireCanInitialize()

        if (!cachePrepared) {
            callback.sendStatus(
                operationId = operationId,
                readiness = MoonshineSttIpcProtocol.READINESS_DOWNLOADING,
                message = "검증된 한국어 Moonshine 모델 캐시를 확인하는 중입니다.",
            )
            // Download and full SHA-256 verification already ran in the client process before it
            // acquired native admission. Recheck the private marker and exact sizes only; do not
            // spend the scarce ticket hashing model bytes a second time.
            MoonshineSttModelCacheMigration(applicationContext).requirePreparedForNativeLoad()
            cachePrepared = true
        }

        callback.sendStatus(
            operationId = operationId,
            readiness = MoonshineSttIpcProtocol.READINESS_DOWNLOADING,
            message = "한국어 Moonshine 오프라인 음성 모델을 확인하는 중입니다.",
        )
        val loading = MicTranscriber(applicationContext)
        nativeOwners.trackInitialization(loading)
        try {
            loading
                .language(MOONSHINE_KOREAN_LANGUAGE)
                .modelArch(JNI.MOONSHINE_MODEL_ARCH_TINY)
                .options(MoonshineRealtimeSttOptions.values(usesNote9CompatibilityProfile()))
                .callbacksOnMainThread(false)
                .onProgress { fraction, file ->
                    if (!shuttingDown.get()) {
                        callback.sendStatus(
                            operationId = operationId,
                            readiness = MoonshineSttIpcProtocol.READINESS_DOWNLOADING,
                            message = "한국어 Moonshine 음성 모델 준비 중 · " +
                                "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%",
                            progress = fraction.coerceIn(0f, 1f),
                            currentFile = file,
                        )
                    }
                }
            loading.load()
            check(loading.isLoaded) { "Moonshine 네이티브 음성 모델이 적재되지 않았습니다." }
            check(!shuttingDown.get()) { "Moonshine 음성인식 준비가 취소되었습니다." }
            nativeOwners.activate(loading)
            callback.sendStatus(
                operationId = operationId,
                readiness = MoonshineSttIpcProtocol.READINESS_READY,
                message = "한국어 Moonshine 스트리밍 음성인식 준비됨",
                progress = 1f,
            )
            return loading
        } catch (error: Throwable) {
            nativeOwners.closeFailedInitialization(
                candidate = loading,
                initializationFailure = error,
                closeCandidate = MicTranscriber::close,
            )
        }
    }

    private fun usesNote9CompatibilityProfile(): Boolean {
        val activityManager = getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q ||
            memoryInfo.totalMem < NOTE9_COMPATIBILITY_MEMORY_BYTES ||
            activityManager.isLowRamDevice
    }

    private suspend fun startSession(
        sessionId: Long,
        callback: IGuideCastMoonshineSttCallback,
    ) {
        val callbackBinder = callback.asBinder()
        val callbackDied = IBinder.DeathRecipient { requestSessionStop(sessionId) }
        callbackBinder.linkToDeath(callbackDied, 0)
        if (sessionId in cancelledSessions || shuttingDown.get()) {
            runCatching { callbackBinder.unlinkToDeath(callbackDied, 0) }
            callback.safely { onSessionStopped(sessionId) }
            return
        }

        val activeTranscriber = try {
            // startRecognition is deliberately hot-only. If Android reclaimed this process or an
            // idle cleanup unloaded the runtime, the main process must acquire the shared native
            // admission ticket and call prepare() before retrying. Loading here would overlap an
            // unrelated Gemma/ML Kit/TTS cold load without the coordinator knowing about it.
            requirePreparedMoonshineSttRuntime(
                activeRuntime = nativeOwners.activeOrNull(),
                isLoaded = MicTranscriber::isLoaded,
            )
        } catch (notPrepared: MoonshineSttNativePreparationRequiredException) {
            runCatching { callbackBinder.unlinkToDeath(callbackDied, 0) }
            callback.sendStatus(
                operationId = sessionId,
                readiness = MoonshineSttIpcProtocol.READINESS_NATIVE_RESTART_REQUIRED,
                message = "한국어 Moonshine 모델 파일 준비됨 · 음성인식 작업자 재시작 필요",
                progress = 1f,
            )
            callback.safely {
                onError(
                    sessionId,
                    MoonshineSttIpcProtocol.ERROR_NATIVE_PREPARATION_REQUIRED,
                    notPrepared.message.orEmpty(),
                )
            }
            return
        } catch (error: Throwable) {
            runCatching { callbackBinder.unlinkToDeath(callbackDied, 0) }
            throw error
        }
        if (sessionId in cancelledSessions || shuttingDown.get()) {
            runCatching { callbackBinder.unlinkToDeath(callbackDied, 0) }
            callback.safely { onSessionStopped(sessionId) }
            return
        }

        val streamHandle = try {
            activeTranscriber.createStream()
        } catch (error: Throwable) {
            runCatching { callbackBinder.unlinkToDeath(callbackDied, 0) }
            throw error
        }
        val closed = AtomicBoolean(false)
        val listener = Consumer<TranscriptEvent> { event ->
            if (closed.get()) return@Consumer
            when (event) {
                is TranscriptEvent.LineCompleted -> {
                    if (event.streamHandle == streamHandle) {
                        publishLine(sessionId, callback, event.line, isFinal = true)
                    }
                }
                is TranscriptEvent.LineTextChanged -> {
                    if (event.streamHandle == streamHandle) {
                        publishLine(sessionId, callback, event.line, isFinal = false)
                    }
                }
                is TranscriptEvent.LineUpdated -> {
                    if (event.streamHandle == streamHandle) {
                        // LineCompleted remains the sole final event, avoiding duplicate
                        // translations when Moonshine flips the updated-line completion flag.
                        publishLine(sessionId, callback, event.line, isFinal = false)
                    }
                }
                is TranscriptEvent.Error -> {
                    if (event.streamHandle == streamHandle) {
                        callback.safely {
                            onError(
                                sessionId,
                                MoonshineSttIpcProtocol.ERROR_RECOGNITION,
                                event.cause.safeMessage("Moonshine 음성인식 처리에 실패했습니다."),
                            )
                        }
                        requestSessionStop(sessionId)
                    }
                }
            }
        }
        val session = NativeRecognitionSession(
            id = sessionId,
            streamHandle = streamHandle,
            callback = callback,
            callbackBinder = callbackBinder,
            callbackDied = callbackDied,
            listener = listener,
            closed = closed,
        )

        try {
            activeTranscriber.addListener(listener)
            activeTranscriber.startStream(streamHandle)
            sessions[sessionId] = session
            callback.safely { onSessionReady(sessionId) }
        } catch (error: Throwable) {
            session.closed.set(true)
            runCatching { activeTranscriber.removeListener(listener) }
            runCatching { activeTranscriber.stopStream(streamHandle) }
            runCatching { activeTranscriber.freeStream(streamHandle) }
            runCatching { callbackBinder.unlinkToDeath(callbackDied, 0) }
            throw error
        }
    }

    private fun publishLine(
        sessionId: Long,
        callback: IGuideCastMoonshineSttCallback,
        line: TranscriptLine,
        isFinal: Boolean,
    ) {
        val text = line.text?.trim()?.takeIf(String::isNotEmpty) ?: return
        callback.safely {
            onTranscript(
                sessionId,
                line.id,
                text.take(MAX_UTTERANCE_CHARACTERS),
                isFinal,
                SystemClock.elapsedRealtimeNanos(),
            )
        }
    }

    private fun requestSessionStop(sessionId: Long) {
        cancelledSessions.mark(sessionId)
        nativeScope.launch {
            stopSession(sessionId, notifyClient = true)
        }
    }

    private fun stopSession(sessionId: Long, notifyClient: Boolean) {
        val session = sessions.remove(sessionId) ?: return
        try {
            if (!session.closed.compareAndSet(false, true)) return
            val activeTranscriber = nativeOwners.activeOrNull()
            if (activeTranscriber != null) {
                runCatching { activeTranscriber.removeListener(session.listener) }
                runCatching { activeTranscriber.stopStream(session.streamHandle) }
                runCatching { activeTranscriber.freeStream(session.streamHandle) }
            }
            runCatching { session.callbackBinder.unlinkToDeath(session.callbackDied, 0) }
            if (notifyClient) session.callback.safely { onSessionStopped(session.id) }
        } finally {
            cancelledSessions.remove(sessionId)
        }
    }

    private fun cleanupFailedStart(sessionId: Long) {
        stopSession(sessionId, notifyClient = false)
        cancelledSessions.remove(sessionId)
    }

    private fun reportPreparationFailure(
        operationId: Long,
        callback: IGuideCastMoonshineSttCallback,
        error: Throwable,
    ) {
        val message = error.safeMessage("한국어 Moonshine 음성 모델 준비에 실패했습니다.")
        callback.sendStatus(
            operationId = operationId,
            readiness = MoonshineSttIpcProtocol.READINESS_FAILED,
            message = message,
        )
        callback.safely {
            onError(operationId, MoonshineSttIpcProtocol.ERROR_MODEL_PREPARATION, message)
        }
    }

    private fun reportSessionError(sessionId: Long, code: Int, message: String) {
        nativeScope.launch {
            sessions[sessionId]?.callback?.safely {
                onError(sessionId, code, message)
            }
        }
    }

    private fun requestShutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return
        cancelledSessions.markAll(startingSessions)
        nativeScope.launch {
            try {
                sessions.keys.toList().forEach { sessionId ->
                    stopSession(sessionId, notifyClient = true)
                }
                val closeFailure = closeMoonshineSttRuntimeWithRetry(
                    maxAttempts = MAX_RUNTIME_CLOSE_ATTEMPTS,
                ) {
                    runtimeCloseGate.close {
                        processGeneration.closeRuntimeThenMarkClosed {
                            nativeOwners.closeAll(MicTranscriber::close)
                        }
                    }
                }
                if (closeFailure != null) {
                    // Do not present an uncertain close as success. This process generation stays
                    // unresolved so a replacement Service cannot load beside stale native state.
                    Log.e(LOG_TAG, "Moonshine STT native runtime close was not confirmed", closeFailure)
                }
            } finally {
                stopSelf()
                serviceJob.cancel()
                nativeDispatcher.close()
            }
        }
    }

    private fun IGuideCastMoonshineSttCallback.sendStatus(
        operationId: Long,
        readiness: Int,
        message: String,
        progress: Float = 0f,
        currentFile: String? = null,
    ) = safely {
        onPreparationStatus(
            operationId,
            readiness,
            message.take(MAX_STATUS_CHARACTERS),
            progress.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f,
            currentFile.orEmpty().take(MAX_STATUS_CHARACTERS),
        )
    }

    private inline fun IGuideCastMoonshineSttCallback.safely(
        event: IGuideCastMoonshineSttCallback.() -> Unit,
    ) {
        try {
            event()
        } catch (error: RemoteException) {
            Log.w(LOG_TAG, "STT client callback is no longer reachable", error)
        }
    }

    private data class NativeRecognitionSession(
        val id: Long,
        val streamHandle: Int,
        val callback: IGuideCastMoonshineSttCallback,
        val callbackBinder: IBinder,
        val callbackDied: IBinder.DeathRecipient,
        val listener: Consumer<TranscriptEvent>,
        val closed: AtomicBoolean,
    )

    companion object {
        private const val LOG_TAG = "GuideCastSttWorker"
        private const val MOONSHINE_KOREAN_LANGUAGE = "ko"
        private const val REQUIRED_SAMPLE_RATE_HZ = 16_000
        private const val REQUIRED_CHANNEL_COUNT = 1
        private const val MAX_UTTERANCE_CHARACTERS = 2_000
        private const val MAX_STATUS_CHARACTERS = 500
        private const val MAX_RUNTIME_CLOSE_ATTEMPTS = 2
        private const val NOTE9_COMPATIBILITY_MEMORY_BYTES = 7L * 1024 * 1024 * 1024

        private fun isKorean(languageTag: String): Boolean =
            languageTag.substringBefore('-').equals("ko", ignoreCase = true)
    }
}

private fun Throwable.safeMessage(fallback: String): String =
    (message?.takeIf(String::isNotBlank) ?: fallback).take(500)

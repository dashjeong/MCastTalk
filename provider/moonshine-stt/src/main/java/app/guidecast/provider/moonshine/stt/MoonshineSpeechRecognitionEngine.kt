package app.guidecast.provider.moonshine.stt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.translation.RecognizedUtterance
import app.guidecast.core.translation.SpeechRecognitionConfig
import app.guidecast.core.translation.SpeechRecognitionEngine
import app.guidecast.core.translation.NativeColdLoadTicket
import app.guidecast.core.translation.currentNativeColdLoadTicket
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

data class MoonshineSpeechRecognitionCapability(
    val available: Boolean,
    val reason: String? = null,
)

enum class MoonshineSpeechLanguageReadiness {
    DOWNLOAD_REQUIRED,
    DOWNLOADING,
    /** Verified assets exist, but no isolated native worker is currently warm. */
    NATIVE_RESTART_REQUIRED,
    READY,
    FAILED,
    UNSUPPORTED,
}

data class MoonshineSpeechLanguageStatus(
    val readiness: MoonshineSpeechLanguageReadiness,
    val message: String,
    val progress: Float = 0f,
    val currentFile: String? = null,
) {
    val isReady: Boolean get() = readiness == MoonshineSpeechLanguageReadiness.READY
}

enum class MoonshineSttNativeReleaseResult {
    RELEASED,
    BUSY,
    SUPERSEDED,
}

/**
 * Korean streaming STT from the Moonshine Voice stack used by Gemma Translator.
 *
 * The public API remains local and Flow-based, while every MicTranscriber load and stream call is
 * delegated to the non-exported `:stt_inference` process. A native abort therefore disconnects
 * this Binder and fails only the recognition Flow; microphone capture, web streaming, and the
 * operator UI remain in the main process.
 */
class MoonshineSpeechRecognitionEngine(
    context: Context,
) : SpeechRecognitionEngine, Closeable {
    private val applicationContext = context.applicationContext
    private val modelMutex = Mutex()
    private val assetPreparationMutex = Mutex()
    private val connectionMutex = Mutex()
    private val connectionStateLock = Any()
    private val closed = AtomicBoolean(false)
    private val nativeResourceUseEpoch = AtomicLong(0)
    private val nativeActivitySequence = AtomicLong(0)
    private val nativeActivityChanges = MutableStateFlow(0L)
    private val pendingPreparations = ConcurrentHashMap<Long, MoonshineSttPreparationRequest>()
    private val activeSessions = ConcurrentHashMap<Long, RemoteRecognitionSession>()

    @Volatile private var remoteService: IGuideCastMoonshineStt? = null
    @Volatile private var remoteBinder: IBinder? = null
    @Volatile private var remoteDeathRecipient: IBinder.DeathRecipient? = null
    @Volatile private var serviceConnection: ServiceConnection? = null

    private val mutableStatus = MutableStateFlow(
        MoonshineSpeechLanguageStatus(
            readiness = MoonshineSpeechLanguageReadiness.DOWNLOAD_REQUIRED,
            message = "한국어 Moonshine 오프라인 음성 모델을 준비하세요.",
        ),
    )
    val status: StateFlow<MoonshineSpeechLanguageStatus> = mutableStatus.asStateFlow()

    fun capability(): MoonshineSpeechRecognitionCapability = when {
        Build.SUPPORTED_ABIS.none { it == "arm64-v8a" } ->
            MoonshineSpeechRecognitionCapability(
                available = false,
                reason = "이 빌드는 ARM64 Android 기기에서 Moonshine 음성인식을 지원합니다.",
            )

        else -> MoonshineSpeechRecognitionCapability(available = true)
    }

    suspend fun languageStatus(languageTag: String): MoonshineSpeechLanguageStatus {
        requireKorean(languageTag)
        return activeWorkerAwareStatus()
    }

    suspend fun prepareLanguage(languageTag: String): MoonshineSpeechLanguageStatus {
        prepareLanguageAssets(languageTag)
        return prepareNativeLanguage(languageTag)
    }

    /** Downloads and fully verifies model bytes without binding or loading the native worker. */
    suspend fun prepareLanguageAssets(languageTag: String) {
        requireKorean(languageTag)
        checkOpen()
        check(capability().available) {
            capability().reason ?: "Moonshine 음성인식을 사용할 수 없습니다."
        }
        assetPreparationMutex.withLock {
            withContext(Dispatchers.IO) {
                MoonshineSttModelCacheMigration(applicationContext).prepare { progress, file ->
                    if (!closed.get()) {
                        updateStatus(
                            MoonshineSttIpcProtocol.READINESS_DOWNLOADING,
                            "한국어 Moonshine 모델 검증 및 준비 중 · " +
                                "${(progress * 100).toInt()}%",
                            progress,
                            file.orEmpty(),
                        )
                    }
                }
            }
        }
    }

    /** Maps the already verified model in the isolated worker under caller-owned admission. */
    suspend fun prepareNativeLanguage(languageTag: String): MoonshineSpeechLanguageStatus {
        requireKorean(languageTag)
        checkOpen()
        check(capability().available) {
            capability().reason ?: "Moonshine 음성인식을 사용할 수 없습니다."
        }
        // A cleanup claim captured before this call is now stale, even while this preparation is
        // waiting for modelMutex. It must not unload the worker prepared for the newer session.
        nativeResourceUseEpoch.incrementAndGet()
        signalNativeActivityChange()
        return modelMutex.withLock {
            if (status.value.isReady && remoteService?.asBinder()?.isBinderAlive == true) {
                return@withLock status.value
            }

            val worker = remote()
            val operationId = nextIpcId()
            val completion = CompletableDeferred<Unit>()
            val pending = MoonshineSttPreparationRequest(completion, currentNativeColdLoadTicket())
            pendingPreparations[operationId] = pending
            signalNativeActivityChange()
            val callbackLock = Any()
            var callbackActive = true

            fun acceptCallback(action: () -> Unit) {
                synchronized(callbackLock) {
                    if (callbackActive && !closed.get()) action()
                }
            }

            fun deactivateCallback() {
                synchronized(callbackLock) { callbackActive = false }
            }

            val callback = object : IGuideCastMoonshineSttCallback.Stub() {
                override fun onPreparationStatus(
                    reportedOperationId: Long,
                    readiness: Int,
                    message: String,
                    progress: Float,
                    currentFile: String,
                ) {
                    if (reportedOperationId != operationId) return
                    acceptCallback {
                        updateStatus(readiness, message, progress, currentFile)
                        if (readiness == MoonshineSttIpcProtocol.READINESS_READY) {
                            completion.complete(Unit)
                        }
                    }
                }

                override fun onError(reportedOperationId: Long, errorCode: Int, message: String) {
                    if (reportedOperationId != operationId) return
                    acceptCallback {
                        val error = MoonshineSttWorkerException(message, errorCode = errorCode)
                        updateFailedStatus(message)
                        completion.completeExceptionally(error)
                    }
                }

                override fun onSessionReady(sessionId: Long) = Unit
                override fun onTranscript(
                    sessionId: Long,
                    lineId: Long,
                    text: String,
                    isFinal: Boolean,
                    capturedAtElapsedRealtimeNanos: Long,
                ) = Unit
                override fun onPcmConsumed(sessionId: Long, frameId: Long) = Unit
                override fun onSessionStopped(sessionId: Long) = Unit
                override fun onFinished(reportedOperationId: Long) {
                    if (reportedOperationId == operationId) {
                        finishPendingPreparation(operationId, pending)
                    }
                }
            }

            try {
                awaitMoonshineSttCallbackWithin(
                    timeoutMillis = PREPARATION_CALLBACK_TIMEOUT_MILLIS,
                    timeoutFailure = {
                        MoonshineSttWorkerException(
                            "Moonshine STT 모델 준비 응답 시간이 초과되었습니다. " +
                                "Galaxy 온디바이스 음성인식으로 전환합니다.",
                            errorCode = MoonshineSttIpcProtocol.ERROR_MODEL_PREPARATION,
                        )
                    },
                    onTimeout = ::deactivateCallback,
                ) {
                    try {
                        currentCoroutineContext().ensureActive()
                        pending.transferToNative()
                        worker.prepare(operationId, languageTag, callback)
                    } catch (error: Throwable) {
                        finishPendingPreparation(operationId, pending)
                        throw error
                    }
                    completion.await()
                }
                status.value
            } catch (cancelled: CancellationException) {
                deactivateCallback()
                throw cancelled
            } catch (error: Throwable) {
                // Close callback ownership before publishing the terminal failure. A late Binder
                // progress/ready event from the timed-out native load must not overwrite fallback
                // state belonging to this or a newer Galaxy recognition attempt.
                deactivateCallback()
                val workerError = error.asWorkerFailure("한국어 Moonshine 음성 모델을 준비하지 못했습니다.")
                updateFailedStatus(workerError.message.orEmpty())
                throw workerError
            } finally {
                deactivateCallback()
                // Caller completion/cancellation is not native completion. Keep this ownership
                // record until onFinished or Binder death so idle cleanup cannot kill a JNI load
                // and lose the transferred process-wide ticket.
                if (pending.abortBeforeSubmission()) {
                    finishPendingPreparation(operationId, pending)
                }
            }
        }
    }

    fun hasActivePreparedWorker(languageTag: String): Boolean {
        requireKorean(languageTag)
        return activeWorkerAwareStatus().isReady
    }

    override fun recognize(
        frames: Flow<PcmAudioFrame>,
        config: SpeechRecognitionConfig,
    ): Flow<RecognizedUtterance> = callbackFlow {
        requireKorean(config.sourceLanguageTag)
        require(config.sampleRateHz == REQUIRED_SAMPLE_RATE_HZ &&
            config.channelCount == REQUIRED_CHANNEL_COUNT) {
            "Moonshine STT requires 16 kHz mono PCM"
        }
        checkOpen()
        check(capability().available) {
            capability().reason ?: "Moonshine 음성인식을 사용할 수 없습니다."
        }

        val sessionId = nextIpcId()
        val remoteSession = RemoteRecognitionSession(
            id = sessionId,
            sourceLanguageTag = config.sourceLanguageTag,
            producer = this,
            onStatus = ::updateStatus,
        )
        // Claim the recognition session while holding the same connection lock used by idle
        // release. If release wins first, this call observes an empty connection and rebinds. If
        // recognition wins first, release sees the active session and leaves its worker intact.
        val worker = try {
            remoteForRecognition(remoteSession)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            close(error.asWorkerFailure("Moonshine 음성인식 작업 공간에 연결하지 못했습니다."))
            return@callbackFlow
        }
        val cleanedUp = AtomicBoolean(false)
        var feeder: Job? = null

        fun cleanup() {
            if (!cleanedUp.compareAndSet(false, true)) return
            remoteSession.markClientClosed()
            feeder?.cancel()
            activeSessions.remove(sessionId, remoteSession)
            signalNativeActivityChange()
            runCatching { worker.stopRecognition(sessionId) }
        }

        try {
            awaitMoonshineSttCallbackWithin(
                timeoutMillis = SESSION_READY_TIMEOUT_MILLIS,
                timeoutFailure = {
                    MoonshineSttWorkerException(
                        "Moonshine STT 세션 준비 응답 시간이 초과되었습니다. " +
                            "Galaxy 온디바이스 음성인식으로 전환합니다.",
                    )
                },
                // stopRecognition records a bounded tombstone even when the one-way start is
                // still queued in the worker, so the timed-out attempt cannot resurrect later.
                onTimeout = ::cleanup,
            ) {
                worker.startRecognition(
                    sessionId,
                    config.sourceLanguageTag,
                    config.sampleRateHz,
                    config.channelCount,
                    remoteSession,
                )
                remoteSession.awaitReady()
            }
            feeder = launch(Dispatchers.IO) {
                frames.collect { frame ->
                    frame.bytes.asBinderSafePcmChunks().forEach { chunk ->
                        remoteSession.sendPcm(worker, chunk)
                    }
                }
            }.also { job ->
                job.invokeOnCompletion { error ->
                    if (error != null && error !is CancellationException) {
                        remoteSession.fail(
                            error.asWorkerFailure("Moonshine 음성 입력 전달이 중단되었습니다."),
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            cleanup()
            throw cancelled
        } catch (error: Throwable) {
            cleanup()
            close(error.asWorkerFailure("Moonshine 음성인식을 시작하지 못했습니다."))
            return@callbackFlow
        }

        awaitClose(::cleanup)
    }

    private suspend fun remote(): IGuideCastMoonshineStt = connectionMutex.withLock {
        remoteWhileConnectionLocked()
    }

    private suspend fun remoteForRecognition(
        session: RemoteRecognitionSession,
    ): IGuideCastMoonshineStt = connectionMutex.withLock {
        checkOpen()
        nativeResourceUseEpoch.incrementAndGet()
        signalNativeActivityChange()
        check(activeSessions.putIfAbsent(session.id, session) == null) {
            "Moonshine 음성인식 세션 식별자가 중복되었습니다."
        }
        signalNativeActivityChange()
        try {
            remoteWhileConnectionLocked()
        } catch (error: Throwable) {
            if (activeSessions.remove(session.id, session)) signalNativeActivityChange()
            throw error
        }
    }

    /** Must be invoked with [connectionMutex] held. */
    private suspend fun remoteWhileConnectionLocked(): IGuideCastMoonshineStt {
        checkOpen()
        remoteService?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        return try {
            withTimeout(BIND_TIMEOUT_MILLIS) { bindRemote() }
        } catch (timeout: TimeoutCancellationException) {
            throw MoonshineSttWorkerException(
                "Moonshine STT 작업 공간 연결이 지연되어 중단했습니다. " +
                    "원음 방송과 앱은 계속 동작합니다.",
                cause = timeout,
            )
        }
    }

    /**
     * Releases only idle native state while keeping this engine reusable.
     *
     * This is deliberately non-blocking with respect to model preparation and recognition start:
     * callers may retry after their session cancellation has propagated. A busy result means that
     * active or starting work still owns the worker; it is never interrupted to save memory.
     * A successful release shuts down and unbinds the isolated process. The next prepare/recognize
     * call reconnects through [remoteWhileConnectionLocked].
     */
    fun nativeResourceUseEpoch(): Long = nativeResourceUseEpoch.get()

    /** Suspends without polling until an in-flight prepare/session ends or newer use supersedes it. */
    suspend fun awaitNativeIdleOrUseChanged(
        expectedUseEpoch: Long,
    ): MoonshineSttNativeReleaseResult {
        require(expectedUseEpoch >= 0L)
        while (true) {
            val observed = nativeActivitySequence.get()
            val decision = moonshineSttNativeReleaseDecision(
                expectedUseEpoch = expectedUseEpoch,
                currentUseEpoch = nativeResourceUseEpoch(),
                pendingPreparationCount = pendingPreparations.size,
                activeRecognitionCount = activeSessions.size,
            )
            if (decision != MoonshineSttNativeReleaseResult.BUSY) return decision
            nativeActivityChanges.first { it != observed }
        }
    }

    suspend fun releaseNativeResources(): Boolean =
        releaseNativeResourcesIfUnchanged(nativeResourceUseEpoch()).let { result ->
            result == MoonshineSttNativeReleaseResult.RELEASED
        }

    suspend fun releaseNativeResourcesIfUnchanged(
        expectedUseEpoch: Long,
    ): MoonshineSttNativeReleaseResult {
        require(expectedUseEpoch >= 0L) { "Moonshine STT use epoch cannot be negative" }
        if (closed.get()) return MoonshineSttNativeReleaseResult.RELEASED
        if (nativeResourceUseEpoch() != expectedUseEpoch) {
            return MoonshineSttNativeReleaseResult.SUPERSEDED
        }
        if (!modelMutex.tryLock()) return MoonshineSttNativeReleaseResult.BUSY
        try {
            if (!connectionMutex.tryLock()) return MoonshineSttNativeReleaseResult.BUSY
            try {
                checkOpen()
                val decision = moonshineSttNativeReleaseDecision(
                    expectedUseEpoch = expectedUseEpoch,
                    currentUseEpoch = nativeResourceUseEpoch(),
                    pendingPreparationCount = pendingPreparations.size,
                    activeRecognitionCount = activeSessions.size,
                )
                if (decision != MoonshineSttNativeReleaseResult.RELEASED) {
                    return decision
                }
                disconnectConnection(requestShutdown = true)
                markNativeRestartRequiredAfterRelease()
                return MoonshineSttNativeReleaseResult.RELEASED
            } finally {
                connectionMutex.unlock()
            }
        } finally {
            modelMutex.unlock()
        }
    }

    private suspend fun bindRemote(): IGuideCastMoonshineStt =
        suspendCancellableCoroutine { continuation ->
            val bindCompleted = AtomicBoolean(false)
            val bindingAttempt = MoonshineSttBindingAttemptState()
            fun resumeWorker(worker: IGuideCastMoonshineStt): Boolean {
                if (!bindCompleted.compareAndSet(false, true) || !continuation.isActive) {
                    return false
                }
                continuation.resume(worker)
                return true
            }
            fun failBinding(error: Throwable) {
                if (bindCompleted.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resumeWithException(error)
                }
            }

            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (binder == null) {
                        failBinding(
                            MoonshineSttWorkerException("Moonshine STT 작업 공간이 빈 연결을 반환했습니다."),
                        )
                        disconnectConnection(this, requestShutdown = false)
                        return
                    }
                    val worker = IGuideCastMoonshineStt.Stub.asInterface(binder)
                    val deathRecipient = IBinder.DeathRecipient {
                        handleWorkerLoss(
                            expectedConnection = this,
                            expectedBinder = binder,
                            error = MoonshineSttWorkerException(
                                "Moonshine STT 작업 프로세스가 예기치 않게 종료되었습니다. " +
                                    "원음 방송과 앱은 계속 동작합니다.",
                            ),
                        )
                    }
                    try {
                        binder.linkToDeath(deathRecipient, 0)
                    } catch (error: RemoteException) {
                        failBinding(
                            error.asWorkerFailure("Moonshine STT 작업 프로세스가 연결 중 종료되었습니다."),
                        )
                        disconnectConnection(this, requestShutdown = false)
                        return
                    }

                    val accepted = synchronized(connectionStateLock) {
                        if (!closed.get() && serviceConnection === this) {
                            remoteService = worker
                            remoteBinder = binder
                            remoteDeathRecipient = deathRecipient
                            true
                        } else {
                            false
                        }
                    }
                    if (!accepted) {
                        runCatching { binder.unlinkToDeath(deathRecipient, 0) }
                        runCatching { applicationContext.unbindService(this) }
                        failBinding(
                            MoonshineSttWorkerException("Moonshine STT 연결 요청이 취소되었습니다."),
                        )
                        return
                    }

                    if (!resumeWorker(worker)) {
                        disconnectConnection(this, requestShutdown = false)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    val error = MoonshineSttWorkerException(
                        "Moonshine STT 작업 프로세스 연결이 끊겼습니다. " +
                            "원음 방송과 앱은 계속 동작합니다.",
                    )
                    handleWorkerLoss(this, expectedBinder = null, error = error)
                    failBinding(error)
                }

                override fun onBindingDied(name: ComponentName?) {
                    val error = MoonshineSttWorkerException(
                        "Moonshine STT 작업 프로세스가 시스템에 의해 종료되었습니다. " +
                            "원음 방송과 앱은 계속 동작합니다.",
                    )
                    handleWorkerLoss(this, expectedBinder = null, error = error)
                    failBinding(error)
                }

                override fun onNullBinding(name: ComponentName?) {
                    val error = MoonshineSttWorkerException(
                        "Moonshine STT 작업 공간을 시작하지 못했습니다.",
                    )
                    handleWorkerLoss(this, expectedBinder = null, error = error)
                    failBinding(error)
                }
            }

            synchronized(connectionStateLock) {
                serviceConnection = connection
            }
            continuation.invokeOnCancellation {
                bindingAttempt.cancel()
                bindCompleted.compareAndSet(false, true)
                val shouldUnbind = synchronized(connectionStateLock) {
                    if (serviceConnection === connection && remoteService == null) {
                        serviceConnection = null
                        true
                    } else {
                        false
                    }
                }
                if (shouldUnbind) runCatching { applicationContext.unbindService(connection) }
            }

            if (!bindingAttempt.mayStartBinding(continuation.isActive)) {
                bindCompleted.compareAndSet(false, true)
                disconnectConnection(connection, requestShutdown = false)
                return@suspendCancellableCoroutine
            }

            val bound = runCatching {
                applicationContext.bindService(
                    Intent(applicationContext, MoonshineSttInferenceService::class.java),
                    connection,
                    // An actively recording worker must not be classified as a cached process.
                    // Android 14+ may freeze a WAIVE_PRIORITY binding and then reject/kill its
                    // synchronous Binder calls, which interrupts live interpretation.
                    Context.BIND_AUTO_CREATE,
                )
            }.getOrElse { error ->
                failBinding(error)
                false
            }
            if (!bound) {
                synchronized(connectionStateLock) {
                    if (serviceConnection === connection) serviceConnection = null
                }
                failBinding(
                    MoonshineSttWorkerException("Moonshine STT 작업 공간을 시작하지 못했습니다."),
                )
            } else if (bindingAttempt.wasCancelled) {
                // Cancellation can clear state before bindService() returns ownership. Release the
                // exact obsolete connection again after Android has accepted the binding.
                disconnectConnection(connection, requestShutdown = false)
            }
        }

    private fun handleWorkerLoss(
        expectedConnection: ServiceConnection,
        expectedBinder: IBinder?,
        error: MoonshineSttWorkerException,
    ) {
        val disconnected = synchronized(connectionStateLock) {
            if (serviceConnection !== expectedConnection ||
                (expectedBinder != null && remoteBinder !== expectedBinder)) {
                null
            } else {
                val state = DisconnectedState(
                    connection = serviceConnection,
                    binder = remoteBinder,
                    deathRecipient = remoteDeathRecipient,
                )
                serviceConnection = null
                remoteService = null
                remoteBinder = null
                remoteDeathRecipient = null
                state
            }
        } ?: return

        if (disconnected.binder != null && disconnected.deathRecipient != null) {
            runCatching { disconnected.binder.unlinkToDeath(disconnected.deathRecipient, 0) }
        }
        disconnected.connection?.let { connection ->
            runCatching { applicationContext.unbindService(connection) }
        }
        if (!closed.get()) {
            updateFailedStatus(error.message.orEmpty())
            failOutstandingWork(error)
            Log.e(LOG_TAG, "Isolated STT worker was lost; main process remains alive", error)
        }
    }

    private fun disconnectConnection(
        expectedConnection: ServiceConnection? = null,
        requestShutdown: Boolean,
    ) {
        val state = synchronized(connectionStateLock) {
            if (expectedConnection != null && serviceConnection !== expectedConnection) {
                null
            } else {
                DisconnectedState(
                    connection = serviceConnection,
                    binder = remoteBinder,
                    deathRecipient = remoteDeathRecipient,
                ).also {
                    serviceConnection = null
                    remoteService = null
                    remoteBinder = null
                    remoteDeathRecipient = null
                }
            }
        }
        if (state == null) {
            // The owner may already have been cleared before bindService() returned. Unbind the
            // exact stale connection without disturbing a newer worker that now owns shared state.
            expectedConnection?.let { runCatching { applicationContext.unbindService(it) } }
            return
        }
        if (requestShutdown) {
            runCatching { IGuideCastMoonshineStt.Stub.asInterface(state.binder)?.shutdown() }
        }
        if (state.binder != null && state.deathRecipient != null) {
            runCatching { state.binder.unlinkToDeath(state.deathRecipient, 0) }
        }
        state.connection?.let { connection ->
            runCatching { applicationContext.unbindService(connection) }
        }
    }

    private fun failOutstandingWork(error: MoonshineSttWorkerException) {
        pendingPreparations.entries.toList().forEach { (operationId, pending) ->
            if (pendingPreparations.remove(operationId, pending)) {
                pending.finishNative()
                pending.completion.completeExceptionally(error)
                signalNativeActivityChange()
            }
        }
        activeSessions.values.toList().forEach { it.fail(error) }
    }

    private fun finishPendingPreparation(
        operationId: Long,
        pending: MoonshineSttPreparationRequest,
    ) {
        // ACK/death may race. Either path may finish the idempotent ticket, but only the map owner
        // publishes the transition to idle.
        pending.finishNative()
        if (pendingPreparations.remove(operationId, pending)) signalNativeActivityChange()
    }

    private fun signalNativeActivityChange() {
        val next = nativeActivitySequence.updateAndGet { value ->
            if (value == Long.MAX_VALUE) 0L else value + 1L
        }
        nativeActivityChanges.value = next
    }

    private fun updateStatus(
        readiness: Int,
        message: String,
        progress: Float,
        currentFile: String,
    ) {
        mutableStatus.value = MoonshineSpeechLanguageStatus(
            readiness = readiness.toReadiness(),
            message = message.take(MAX_STATUS_CHARACTERS),
            progress = progress.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f,
            currentFile = currentFile.trim().takeIf(String::isNotEmpty),
        )
    }

    private fun updateFailedStatus(message: String) {
        mutableStatus.value = MoonshineSpeechLanguageStatus(
            readiness = MoonshineSpeechLanguageReadiness.FAILED,
            message = message.take(MAX_STATUS_CHARACTERS).ifBlank {
                "Moonshine STT 작업 프로세스가 종료되었습니다."
            },
        )
    }

    /** A READY label means a live Binder generation, not merely verified model files on disk. */
    private fun activeWorkerAwareStatus(): MoonshineSpeechLanguageStatus {
        val current = status.value
        if (!current.isReady) return current
        val binderAlive = remoteService?.asBinder()?.let { binder ->
            runCatching { binder.isBinderAlive }.getOrDefault(false)
        } == true
        val replacement = moonshineSttStatusForWorkerLiveness(current, binderAlive)
        if (replacement === current) return current
        mutableStatus.compareAndSet(current, replacement)
        return status.value
    }

    private fun markNativeRestartRequiredAfterRelease() {
        while (true) {
            val current = status.value
            if (!current.isReady) return
            if (mutableStatus.compareAndSet(current, nativeRestartRequiredStatus())) return
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val error = MoonshineSttWorkerException("Moonshine 음성인식 엔진이 종료되었습니다.")
        failOutstandingWork(error)
        disconnectConnection(requestShutdown = true)
    }

    private fun checkOpen() {
        check(!closed.get()) { "Moonshine 음성인식 엔진이 이미 종료되었습니다." }
    }

    private fun requireKorean(languageTag: String) {
        require(languageTag.substringBefore('-').equals("ko", ignoreCase = true)) {
            "현재 Moonshine STT 원문 언어는 한국어만 지원합니다."
        }
    }

    private inner class RemoteRecognitionSession(
        val id: Long,
        private val sourceLanguageTag: String,
        private val producer: ProducerScope<RecognizedUtterance>,
        private val onStatus: (Int, String, Float, String) -> Unit,
    ) : IGuideCastMoonshineSttCallback.Stub() {
        private val ready = CompletableDeferred<Unit>()
        private val terminated = AtomicBoolean(false)
        private val nextSequence = AtomicLong(0)
        private val nextFrameId = AtomicLong(1)
        private val sequencesByLine = ConcurrentHashMap<Long, Long>()
        private val capturedAtByLine = ConcurrentHashMap<Long, Long>()
        private val lastTextByLine = ConcurrentHashMap<Long, String>()
        private val pendingPcm = AtomicReference<PendingPcm?>(null)

        suspend fun awaitReady() = ready.await()

        suspend fun sendPcm(worker: IGuideCastMoonshineStt, bytes: ByteArray) {
            if (terminated.get()) {
                throw MoonshineSttWorkerException("Moonshine 음성인식 세션이 종료되었습니다.")
            }
            val frameId = nextFrameId.getAndIncrement()
            val acknowledgement = CompletableDeferred<Unit>()
            val pending = PendingPcm(frameId, acknowledgement)
            check(pendingPcm.compareAndSet(null, pending)) {
                "Only one Moonshine PCM transaction may be in flight"
            }
            try {
                worker.pushPcm(id, frameId, bytes)
                withTimeout(PCM_ACK_TIMEOUT_MILLIS) { acknowledgement.await() }
            } catch (timeout: TimeoutCancellationException) {
                throw MoonshineSttWorkerException(
                    "Moonshine STT 작업 프로세스가 PCM 처리에 응답하지 않습니다. " +
                        "원음 방송과 앱은 계속 동작합니다.",
                    cause = timeout,
                )
            } catch (cancelled: CancellationException) {
                // Flow.first()/take() cancels the upstream feeder as soon as the requested final
                // transcript arrives. That is successful structured cancellation, not a worker
                // or Binder failure, and must retain its cancellation identity through cleanup.
                throw cancelled
            } catch (error: Throwable) {
                throw error.asWorkerFailure("Moonshine STT 작업 프로세스에 PCM을 전달하지 못했습니다.")
            } finally {
                pendingPcm.compareAndSet(pending, null)
            }
        }

        override fun onPreparationStatus(
            operationId: Long,
            readiness: Int,
            message: String,
            progress: Float,
            currentFile: String,
        ) {
            if (operationId == id && !terminated.get()) {
                onStatus(readiness, message, progress, currentFile)
            }
        }

        override fun onSessionReady(sessionId: Long) {
            if (sessionId == id && !terminated.get()) ready.complete(Unit)
        }

        override fun onTranscript(
            sessionId: Long,
            lineId: Long,
            text: String,
            isFinal: Boolean,
            capturedAtElapsedRealtimeNanos: Long,
        ) {
            if (sessionId != id || terminated.get()) return
            val normalized = text.trim().takeIf(String::isNotEmpty) ?: return
            if (!isFinal && lastTextByLine.put(lineId, normalized) == normalized) return
            val sequence = sequencesByLine.getOrPut(lineId) { nextSequence.getAndIncrement() }
            val capturedAt = capturedAtByLine.getOrPut(lineId) {
                capturedAtElapsedRealtimeNanos
            }
            producer.trySend(
                RecognizedUtterance(
                    sequence = sequence,
                    text = normalized.take(MAX_UTTERANCE_CHARACTERS),
                    sourceLanguageTag = sourceLanguageTag,
                    isFinal = isFinal,
                    capturedAtElapsedRealtimeNanos = capturedAt,
                    recognizedAtElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                ),
            )
            if (isFinal) {
                sequencesByLine.remove(lineId)
                capturedAtByLine.remove(lineId)
                lastTextByLine.remove(lineId)
            }
        }

        override fun onPcmConsumed(sessionId: Long, frameId: Long) {
            if (sessionId != id || terminated.get()) return
            val pending = pendingPcm.get() ?: return
            if (pending.frameId == frameId && pendingPcm.compareAndSet(pending, null)) {
                pending.completion.complete(Unit)
            }
        }

        override fun onSessionStopped(sessionId: Long) {
            if (sessionId == id && terminated.compareAndSet(false, true)) {
                val error = MoonshineSttWorkerException("Moonshine 음성인식 세션이 종료되었습니다.")
                ready.completeExceptionally(error)
                pendingPcm.getAndSet(null)?.completion?.completeExceptionally(error)
                clearLineState()
                producer.close()
            }
        }

        override fun onFinished(operationId: Long) = Unit

        override fun onError(operationId: Long, errorCode: Int, message: String) {
            if (operationId == id) {
                fail(MoonshineSttWorkerException(message, errorCode = errorCode))
            }
        }

        fun fail(error: Throwable) {
            if (!terminated.compareAndSet(false, true)) return
            ready.completeExceptionally(error)
            pendingPcm.getAndSet(null)?.completion?.completeExceptionally(error)
            clearLineState()
            producer.close(error)
        }

        fun markClientClosed() {
            if (!terminated.compareAndSet(false, true)) return
            val cancellation = CancellationException("Moonshine 음성인식 수신이 취소되었습니다.")
            ready.completeExceptionally(cancellation)
            pendingPcm.getAndSet(null)?.completion?.completeExceptionally(cancellation)
            clearLineState()
        }

        private fun clearLineState() {
            sequencesByLine.clear()
            capturedAtByLine.clear()
            lastTextByLine.clear()
        }
    }

    private data class PendingPcm(
        val frameId: Long,
        val completion: CompletableDeferred<Unit>,
    )

    private data class DisconnectedState(
        val connection: ServiceConnection?,
        val binder: IBinder?,
        val deathRecipient: IBinder.DeathRecipient?,
    )

    companion object {
        private const val LOG_TAG = "GuideCastStt"
        private const val REQUIRED_SAMPLE_RATE_HZ = 16_000
        private const val REQUIRED_CHANNEL_COUNT = 1
        private const val MAX_UTTERANCE_CHARACTERS = 2_000
        private const val MAX_STATUS_CHARACTERS = 500
        private const val BIND_TIMEOUT_MILLIS = 10_000L
        // Model download has its own progress callbacks and may legitimately take several minutes.
        // It still needs an absolute ceiling so a live Binder with a wedged native loader cannot
        // keep the web broadcast permanently open with every interpretation channel silent.
        private const val PREPARATION_CALLBACK_TIMEOUT_MILLIS = 10L * 60L * 1_000L
        // A prepared worker should create/start a stream promptly. This is a recovery ceiling, not
        // the product latency target; expiry closes only this attempt and lets Galaxy select the
        // already-prepared Android on-device recognizer.
        private const val SESSION_READY_TIMEOUT_MILLIS = 30_000L
        private const val PCM_ACK_TIMEOUT_MILLIS = 30_000L
        private val NEXT_IPC_ID = AtomicLong(1)

        private fun nextIpcId(): Long {
            val value = NEXT_IPC_ID.getAndIncrement()
            check(value > 0L) { "Moonshine STT IPC id space was exhausted" }
            return value
        }
    }
}

/** Owns a caller ticket until the worker's explicit terminal callback or Binder death. */
internal class MoonshineSttPreparationRequest(
    val completion: CompletableDeferred<Unit>,
    private val nativeTicket: NativeColdLoadTicket?,
) {
    private val transferred = AtomicBoolean(false)
    private val nativeFinished = AtomicBoolean(false)

    fun transferToNative() {
        check(transferred.compareAndSet(false, true)) {
            "Moonshine STT native ticket was transferred more than once"
        }
        nativeTicket?.transferToNative()
    }

    fun finishNative() {
        if (nativeFinished.compareAndSet(false, true)) nativeTicket?.completeNative()
    }

    /** Removes a request cancelled after registration but before its one-way Binder submission. */
    fun abortBeforeSubmission(): Boolean {
        if (transferred.get()) return false
        finishNative()
        return true
    }
}

internal class MoonshineSttWorkerException(
    message: String,
    val errorCode: Int = MoonshineSttIpcProtocol.ERROR_WORKER_STOPPED,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Bounds a one-way Binder request whose remote process remains alive but never invokes its
 * completion callback. [withTimeoutOrNull] distinguishes this deadline from cancellation of the
 * owning broadcast; only this deadline is converted into a recoverable provider failure.
 */
internal suspend fun awaitMoonshineSttCallbackWithin(
    timeoutMillis: Long,
    timeoutFailure: () -> MoonshineSttWorkerException,
    onTimeout: () -> Unit = {},
    awaitCallback: suspend () -> Unit,
) {
    require(timeoutMillis > 0L) { "Moonshine STT callback timeout must be positive" }
    val completed = withTimeoutOrNull(timeoutMillis) {
        awaitCallback()
        true
    } ?: false
    if (!completed) {
        onTimeout()
        throw timeoutFailure()
    }
}

private fun Int.toReadiness(): MoonshineSpeechLanguageReadiness = when (this) {
    MoonshineSttIpcProtocol.READINESS_DOWNLOAD_REQUIRED ->
        MoonshineSpeechLanguageReadiness.DOWNLOAD_REQUIRED
    MoonshineSttIpcProtocol.READINESS_DOWNLOADING -> MoonshineSpeechLanguageReadiness.DOWNLOADING
    MoonshineSttIpcProtocol.READINESS_NATIVE_RESTART_REQUIRED ->
        MoonshineSpeechLanguageReadiness.NATIVE_RESTART_REQUIRED
    MoonshineSttIpcProtocol.READINESS_READY -> MoonshineSpeechLanguageReadiness.READY
    MoonshineSttIpcProtocol.READINESS_FAILED -> MoonshineSpeechLanguageReadiness.FAILED
    MoonshineSttIpcProtocol.READINESS_UNSUPPORTED -> MoonshineSpeechLanguageReadiness.UNSUPPORTED
    else -> MoonshineSpeechLanguageReadiness.FAILED
}

internal fun nativeRestartRequiredStatus() = MoonshineSpeechLanguageStatus(
    readiness = MoonshineSpeechLanguageReadiness.NATIVE_RESTART_REQUIRED,
    message = "한국어 Moonshine 모델 파일 준비됨 · 음성인식 작업자 재시작 필요",
    progress = 1f,
)

private fun Throwable.asWorkerFailure(fallback: String): MoonshineSttWorkerException {
    if (this is MoonshineSttWorkerException) return this
    var current: Throwable? = this
    repeat(MAX_CAUSE_DEPTH) {
        if (current is RemoteException) {
            return MoonshineSttWorkerException(
                "$fallback 원음 방송과 앱은 계속 동작합니다.",
                cause = this,
            )
        }
        val next = current?.cause
        if (next == null || next === current) return@repeat
        current = next
    }
    return MoonshineSttWorkerException(
        message = message?.takeIf(String::isNotBlank) ?: fallback,
        cause = this,
    )
}

private const val MAX_CAUSE_DEPTH = 8

internal fun ByteArray.toNormalizedFloatPcm(): FloatArray {
    require(size % Short.SIZE_BYTES == 0) {
        "Moonshine STT PCM contains an incomplete 16-bit sample (byte count: $size)"
    }
    val sampleCount = size / Short.SIZE_BYTES
    val floats = FloatArray(sampleCount)
    var byteIdx = 0
    var floatIdx = 0
    while (floatIdx < sampleCount) {
        val sample = ((this[byteIdx + 1].toInt() shl 8) or (this[byteIdx].toInt() and 0xFF)).toShort()
        floats[floatIdx] = sample.toFloat() / 32_768f
        byteIdx += 2
        floatIdx++
    }
    return floats
}

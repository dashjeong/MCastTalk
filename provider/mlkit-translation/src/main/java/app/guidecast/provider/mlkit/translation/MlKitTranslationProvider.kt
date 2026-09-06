package app.guidecast.provider.mlkit.translation

import android.content.Context
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import app.guidecast.core.translation.LanguageModelManager
import app.guidecast.core.translation.LanguageModelStatus
import app.guidecast.core.translation.ModelReadiness
import app.guidecast.core.translation.TextTranslationEngine
import app.guidecast.core.translation.TranslationEngineProvider
import app.guidecast.core.stream.MAX_SIMULTANEOUS_TRANSLATED_CHANNELS
import app.guidecast.core.translation.currentNativeColdLoadTicket
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout

private const val MAX_MLKIT_WORKER_CONNECTION_ATTEMPTS = 2

class MlKitTranslationProvider(
    context: Context,
    sourceLanguageTag: String,
    private val requireWifiForModels: Boolean = true,
) : TranslationEngineProvider, Closeable {
    private val applicationContext = context.applicationContext
    private val sourceSessions = MlKitSourceSessionCoordinator(sourceLanguageTag.toMlKitLanguage())
    private val sourceSwitchMutex = Mutex()
    internal val sourceLanguage: String
        get() = sourceSessions.capture().languageTag
    // Catalog and file downloads/deletion stay in the app process. Translator construction,
    // warm-up and live inference run in private workers because TranslateJni is not reentrant.
    private val modelOperations = NativeOperationCoordinator()
    private val modelDownloads = ModelArtifactDownloadCoordinator()
    private val slots = MlKitWorkerSlotAllocator(MAX_LIVE_TARGETS)
    private val preparedWorkers = PreparedWorkerGenerations<String, IBinder>()
    private val initializedWorkers = PreparedWorkerGenerations<String, IBinder>()
    private val nextRequestId = java.util.concurrent.atomic.AtomicLong(1L)
    private val workers = Array(MAX_LIVE_TARGETS) { slot ->
        MlKitWorkerConnection(
            applicationContext = applicationContext,
            serviceClass = MlKitInferenceServices.forSlot(slot),
        )
    }

    val modelManager: LanguageModelManager = MlKitLanguageModelManager(this)

    /** Whether this target already owns a live worker, without allocating a new process slot. */
    fun hasActiveWorker(targetLanguageTag: String): Boolean {
        val lease = slots.existingLeaseFor(targetLanguageTag) ?: return false
        return workers[lease.slot].hasActiveConnection()
    }

    /** True only after this exact live worker generation completed a real translation Task. */
    fun hasActivePreparedWorker(targetLanguageTag: String): Boolean {
        val lease = slots.existingLeaseFor(targetLanguageTag) ?: return false
        return preparedWorkers.isCurrent(targetLanguageTag, workers[lease.slot].activeBinder())
    }

    /** True only for the exact live worker generation initialized by [prepareTarget]. */
    fun hasActiveInitializedWorker(targetLanguageTag: String): Boolean {
        val lease = slots.existingLeaseFor(targetLanguageTag) ?: return false
        return initializedWorkers.isCurrent(targetLanguageTag, workers[lease.slot].activeBinder())
    }

    override fun engineFor(targetLanguageTag: String): TextTranslationEngine {
        val normalizedTarget = targetLanguageTag.toMlKitLanguage()
        val sourceSession = sourceSessions.capture()
        require(normalizedTarget != sourceSession.languageTag) {
            "ML Kit source and target languages must be different"
        }
        val sessionGeneration = slots.captureSessionGeneration()
        return TextTranslationEngine { text, sourceTag, _ ->
            require(sourceTag.toMlKitLanguage() == sourceSession.languageTag) {
                "ML Kit translator was prepared for a different source language"
            }
            check(sourceSessions.isCurrent(sourceSession)) {
                "ML Kit translator belongs to a replaced source-language session"
            }
            require(text.isNotBlank() && text.length <= MAX_SOURCE_CHARACTERS) {
                "ML Kit source text must contain 1..$MAX_SOURCE_CHARACTERS characters"
            }
            val lease = slots.awaitLeaseFor(targetLanguageTag, sessionGeneration)
            translateWithWorker(
                lease = lease,
                text = text,
                sourceSession = sourceSession,
                targetLanguage = normalizedTarget,
                logicalTargetLanguage = targetLanguageTag,
            )
        }
    }

    private suspend fun translateWithWorker(
        lease: MlKitWorkerSlotLease,
        text: String,
        sourceSession: MlKitSourceSession,
        targetLanguage: String,
        logicalTargetLanguage: String,
    ): String {
        val translated = withTargetWorker(
            lease = lease,
            targetLanguage = targetLanguage,
            activityLabel = "번역",
            sourceSession = sourceSession,
        ) { worker ->
            awaitWorkerTranslation(
                worker = worker,
                text = text,
                sourceLanguageTag = sourceSession.languageTag,
                targetLanguageTag = targetLanguage,
            ).also {
                // A successful result can only be published after the Google Task terminated.
                // Binding or model download alone must never bypass aggregate cold-load admission.
                preparedWorkers.markCurrent(logicalTargetLanguage, worker.asBinder())
            }
        }
        check(translated.isNotBlank()) {
            "ML Kit worker returned an empty translation"
        }
        return translated
    }

    internal suspend fun prepareTarget(
        targetLanguageTag: String,
        preparationGeneration: Long? = null,
    ) {
        val target = targetLanguageTag.toMlKitLanguage()
        val sourceSession = sourceSessions.capture()
        require(target != sourceSession.languageTag) {
            "ML Kit source and target languages must be different"
        }
        val sessionGeneration = slots.captureSessionGeneration()
        val lease = slots.awaitLeaseFor(
            languageTag = targetLanguageTag,
            expectedSessionGeneration = sessionGeneration,
            expectedPreparationGeneration = preparationGeneration,
        )
        val result = withTargetWorker(
            lease = lease,
            targetLanguage = target,
            activityLabel = "모델 준비",
            sourceSession = sourceSession,
        ) { worker ->
            awaitWorkerRequest(worker, target, "model preparation") { requestId, callback ->
                worker.prepareAsync(
                    requestId,
                    sourceSession.languageTag,
                    target,
                    requireWifiForModels,
                    callback,
                )
            }
        }
        check(result == MODEL_READY_RESULT) { "ML Kit worker returned an invalid prepare result" }
        check(
            preparationGeneration == null ||
                slots.isActiveForPreparation(lease, preparationGeneration),
        ) { "ML Kit model preparation was superseded" }
        val liveBinder = workers[lease.slot].activeBinder()
        check(liveBinder != null) { "ML Kit worker disconnected after model preparation" }
        initializedWorkers.markCurrent(targetLanguageTag, liveBinder)
        preparedWorkers.markCurrent(targetLanguageTag, liveBinder)
    }

    private suspend fun <T> withTargetWorker(
        lease: MlKitWorkerSlotLease,
        targetLanguage: String,
        activityLabel: String,
        retirement: MlKitWorkerSlotRetirement? = null,
        sourceSession: MlKitSourceSession? = null,
        operation: suspend (IGuideCastMlKitInference) -> T,
    ): T {
        var firstTransportFailure: Throwable? = null
        val hasNativeColdLoadTicket = currentNativeColdLoadTicket() != null
        val connectionAttempts = mlKitWorkerConnectionAttempts(hasNativeColdLoadTicket)
        repeat(connectionAttempts) { attempt ->
            check(retirement?.let(slots::isRetiring) ?: slots.isActive(lease)) {
                "ML Kit translation session was replaced"
            }
            check(sourceSession?.let(sourceSessions::isCurrent) != false) {
                "ML Kit source-language session was replaced"
            }
            val connection = workers[lease.slot]
            val worker = connection.worker()
            try {
                val result = operation(worker)
                check(retirement?.let(slots::isRetiring) ?: slots.isActive(lease)) {
                    "ML Kit translation session was replaced"
                }
                check(sourceSession?.let(sourceSessions::isCurrent) != false) {
                    "ML Kit source-language session was replaced"
                }
                return result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                preparedWorkers.invalidate(targetLanguage, worker.asBinder())
                initializedWorkers.invalidate(targetLanguage, worker.asBinder())
                val transportFailure = error.isMlKitWorkerTransportFailure()
                if (transportFailure) {
                    connection.invalidate(worker)
                }
                if (!transportFailure || attempt + 1 >= connectionAttempts) {
                    throw IllegalStateException(
                        "$targetLanguage $activityLabel 작업 공간이 응답하지 않습니다. " +
                            "해당 언어 채널만 다음 문장에서 다시 시도합니다.",
                        error,
                    )
                }
                firstTransportFailure = error
            }
        }
        throw IllegalStateException(
            "$targetLanguage $activityLabel 작업 공간에 다시 연결하지 못했습니다.",
            firstTransportFailure,
        )
    }

    private suspend fun awaitWorkerTranslation(
        worker: IGuideCastMlKitInference,
        text: String,
        sourceLanguageTag: String,
        targetLanguageTag: String,
    ): String = awaitWorkerRequest(worker, targetLanguageTag, "translation") {
            requestId, callback ->
        worker.translateAsync(
            requestId,
            text,
            sourceLanguageTag,
            targetLanguageTag,
            callback,
        )
    }

    private suspend fun awaitWorkerRequest(
        worker: IGuideCastMlKitInference,
        targetLanguageTag: String,
        activityLabel: String,
        start: (Long, IGuideCastMlKitInferenceCallback) -> Unit,
    ): String {
        val nativeTicket = currentNativeColdLoadTicket()
        return suspendCancellableCoroutine { continuation ->
            val requestId = nextRequestId.getAndIncrement()
            check(requestId > 0L) { "ML Kit request identifier overflow" }
            val completed = AtomicBoolean(false)
            val linked = AtomicBoolean(false)
            val lifecycleLock = Any()
            val nativeSubmissionPlanned = AtomicBoolean(false)
            val nativeFinished = AtomicBoolean(false)
            val binder = worker.asBinder()
            lateinit var deathRecipient: IBinder.DeathRecipient

            fun unlink() {
                if (linked.compareAndSet(true, false)) {
                    runCatching { binder.unlinkToDeath(deathRecipient, 0) }
                }
            }

            fun unlinkIfFullyTerminal() {
                val mayUnlink = synchronized(lifecycleLock) {
                    mlKitRequestMayUnlinkDeathRecipient(
                        clientFinished = completed.get(),
                        nativeSubmissionPlanned = nativeSubmissionPlanned.get(),
                        nativeFinished = nativeFinished.get(),
                    )
                }
                if (mayUnlink) unlink()
            }

            fun finishNative() {
                if (nativeFinished.compareAndSet(false, true)) {
                    nativeTicket?.completeNative()
                    // A native terminal acknowledgement may race ahead of the one-way result.
                    // Keep death observation until both halves of the request are terminal.
                    unlinkIfFullyTerminal()
                }
            }

            fun fail(error: Throwable) {
                if (completed.compareAndSet(false, true)) {
                    unlinkIfFullyTerminal()
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }

            deathRecipient = IBinder.DeathRecipient {
                finishNative()
                fail(RemoteException("ML Kit $targetLanguageTag worker died during $activityLabel"))
            }
            val callback = object : IGuideCastMlKitInferenceCallback.Stub() {
                override fun onSuccess(callbackRequestId: Long, translatedText: String?) {
                    if (callbackRequestId != requestId) return
                    if (completed.compareAndSet(false, true)) {
                        unlinkIfFullyTerminal()
                        if (continuation.isActive) continuation.resume(translatedText.orEmpty())
                    }
                }

                override fun onError(callbackRequestId: Long, message: String?) {
                    if (callbackRequestId == requestId) {
                        fail(
                            IllegalStateException(
                                message.orEmpty().ifBlank {
                                    "ML Kit $activityLabel worker failed"
                                },
                            ),
                        )
                    }
                }

                override fun onFinished(callbackRequestId: Long) {
                    if (callbackRequestId == requestId) finishNative()
                }
            }
            continuation.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) {
                    unlinkIfFullyTerminal()
                    runCatching { worker.cancel(requestId) }
                }
            }
            try {
                binder.linkToDeath(deathRecipient, 0)
                linked.set(true)
                val maySubmit = synchronized(lifecycleLock) {
                    if (completed.get() || !continuation.isActive) {
                        false
                    } else {
                        nativeSubmissionPlanned.set(true)
                        true
                    }
                }
                if (!maySubmit) {
                    unlink()
                    return@suspendCancellableCoroutine
                }
                try {
                    nativeTicket?.transferToNative()
                    start(requestId, callback)
                } catch (error: Throwable) {
                    finishNative()
                    throw error
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    internal suspend fun <T> serializedModelOperation(operation: suspend () -> T): T =
        modelOperations.run(operation)

    /** Downloads one ML Kit artifact without constructing a process-local Translator. */
    internal suspend fun downloadModelFile(languageTag: String) {
        val normalized = languageTag.toMlKitLanguage()
        // English is built into ML Kit; TranslateRemoteModel forbids downloading/deleting it.
        if (normalized == "en") return
        val conditions = DownloadConditions.Builder().apply {
            if (requireWifiForModels) requireWifi()
        }.build()
        val manager = RemoteModelManager.getInstance()
        val model = TranslateRemoteModel.Builder(normalized).build()
        modelDownloads.download(normalized, isArtifactReady = {
            awaitReadOnlyModelCatalog(manager.isModelDownloaded(model))
        }) {
            ensureModelArtifactDownloaded(
                isDownloaded = { awaitReadOnlyModelCatalog(manager.isModelDownloaded(model)) },
                download = { manager.download(model, conditions).awaitCompletion() },
            )
        }
    }

    /**
     * Initializes each verified native translator before a live utterance arrives. ML Kit's
     * first translate call creates native state and can otherwise add several seconds to the
     * first sentence even though the model is already downloaded.
     */
    suspend fun warm(languageTags: Collection<String>) {
        val targets = languageTags.distinct().sorted()
        require(targets.size <= MAX_LIVE_TARGETS) {
            "Warm no more than $MAX_LIVE_TARGETS target languages"
        }
        val sourceSession = sourceSessions.capture()
        val failures = supervisorScope {
            targets.map { languageTag ->
                async {
                    try {
                        val completed = withTimeoutOrNull(WORKER_WARMUP_TIMEOUT_MILLIS) {
                            engineFor(languageTag).translate(
                                requireNotNull(WARMUP_SOURCE_TEXT[sourceSession.languageTag]),
                                sourceSession.languageTag,
                                languageTag,
                            )
                            true
                        } ?: false
                        check(completed) { "$languageTag ML Kit 사전 실행 시간이 초과되었습니다." }
                        null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        languageTag to error
                    }
                }
            }.mapNotNull { it.await() }
        }
        check(failures.isEmpty()) {
            failures.joinToString(
                prefix = "ML Kit worker warm-up failed: ",
                separator = "; ",
            ) { (language, error) ->
                "$language=${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    /**
     * Keeps model downloads and file verification outside native-load admission, then delegates
     * only the worker-local Translator creation/model task to the app's shared coordinator.
     */
    suspend fun prepareModels(
        languageTags: Set<String>,
        requestedSourceLanguageTag: String,
        nativeOwnerGeneration: Long? = null,
        isNativeOwnerCurrent: () -> Boolean,
        reconcileNativeTargets: Boolean,
        initializeWithNativeAdmission: suspend (
            languageTag: String,
            initialize: suspend () -> Unit,
        ) -> Unit,
    ) {
        (modelManager as MlKitLanguageModelManager).prepare(
            languageTags = languageTags,
            requestedSourceLanguageTag = requestedSourceLanguageTag,
            nativeOwnerGeneration = nativeOwnerGeneration,
            isNativeOwnerCurrent = isNativeOwnerCurrent,
            reconcileNativeTargets = reconcileNativeTargets,
            initializeWithNativeAdmission = initializeWithNativeAdmission,
        )
    }

    suspend fun refreshModels(
        languageTags: Set<String>,
        nativeOwnerGeneration: Long,
        isNativeOwnerCurrent: () -> Boolean,
    ) {
        (modelManager as MlKitLanguageModelManager).refresh(
            languageTags = languageTags,
            nativeOwnerGeneration = nativeOwnerGeneration,
            isNativeOwnerCurrent = isNativeOwnerCurrent,
        )
    }

    /** Short generation hand-off used to invalidate stale settings waiters on broadcast takeover. */
    fun activatePreparationGeneration(generation: Long) {
        slots.activatePreparationGeneration(generation)
        (modelManager as MlKitLanguageModelManager).activatePreparationGeneration(generation)
    }

    suspend fun retireTargetUnlessRetained(
        languageTag: String,
        retainedLanguageTags: Set<String>,
    ) {
        if (languageTag !in retainedLanguageTags) {
            releaseTarget(languageTag)
        }
    }

    internal fun isBackendModelInUse(backendModel: String): Boolean =
        slots.isBackendInUse(backendModel)

    internal suspend fun releaseTarget(targetLanguage: String) {
        val retirement = slots.beginRetirement(targetLanguage) ?: return
        drainAndRetireTarget(targetLanguage, retirement)
    }

    internal suspend fun releaseTargetsExcept(
        languageTags: Set<String>,
        preparationGeneration: Long? = null,
    ): Map<String, Throwable> {
        val retained = languageTags
        val retirements = if (preparationGeneration == null) {
            slots.beginRetirementsExcept(retained)
        } else {
            slots.beginRetirementsExcept(retained, preparationGeneration)
                ?: throw CancellationException("ML Kit target reconciliation was superseded")
        }
        // Each slot is a different Android process. Drain them concurrently and never let a hung
        // obsolete language prevent healthy retirements from freeing their own slots.
        return try {
            runIsolatedRetirements(
                retirements = retirements,
                operation = { retirement ->
                    drainAndRetireTarget(retirement.lease.languageTag, retirement)
                },
                onFailure = { retirement, error ->
                    Log.w(
                        LOG_TAG,
                        "Could not retire isolated ML Kit channel " + retirement.lease.languageTag,
                        error,
                    )
                },
            ).mapKeys { (retirement, _) -> retirement.lease.languageTag }
        } finally {
            // Cancellation can arrive after the allocator marks all slots but before a child body
            // starts. Recover every untouched token here so no channel remains permanently RETIRING.
            withContext(NonCancellable) {
                retirements.filter(slots::isRetiring).forEach { retirement ->
                    runCatching {
                        workers[retirement.lease.slot].disconnect(stopService = false)
                    }.onFailure { error ->
                        Log.w(LOG_TAG, "ML Kit retirement cancellation disconnect failed", error)
                    }
                    if (!slots.cancelRetirement(retirement)) {
                        Log.e(
                            LOG_TAG,
                            "ML Kit retirement cancellation ownership was superseded: " +
                                retirement.lease.languageTag,
                        )
                    }
                }
            }
        }
    }

    /** Reconciles process-slot ownership even when every requested model is already downloaded. */
    suspend fun reconcileTargets(
        languageTags: Set<String>,
        preparationGeneration: Long? = null,
    ): Map<String, String> {
        require(languageTags.size <= MAX_LIVE_TARGETS) {
            "Reconcile no more than $MAX_LIVE_TARGETS target languages"
        }
        return releaseTargetsExcept(languageTags, preparationGeneration).mapValues { (_, error) ->
            error.message?.take(300) ?: error.javaClass.simpleName
        }
    }

    /**
     * Changes the source only after every old source→target worker has been drained and detached.
     * New leases are blocked for the complete transaction, so a worker can never reuse a Translator
     * constructed for the previous source language.
     */
    suspend fun selectSourceLanguage(sourceLanguageTag: String) =
        selectSourceLanguage(sourceLanguageTag) { true }

    suspend fun selectSourceLanguage(
        sourceLanguageTag: String,
        isOwnerCurrent: () -> Boolean,
    ) {
        val normalized = sourceLanguageTag.toMlKitLanguage()
        require(normalized in WARMUP_SOURCE_TEXT) {
            "GuideCast does not support ML Kit source language: $sourceLanguageTag"
        }
        sourceSwitchMutex.withLock {
            if (!isOwnerCurrent()) {
                throw CancellationException("ML Kit source-language change was superseded")
            }
            if (sourceSessions.capture().languageTag == normalized) return
            val retirements = slots.beginReset()
            var committed = false
            withContext(NonCancellable) {
                try {
                    runIsolatedRetirements(
                        retirements = retirements,
                        operation = { retirement ->
                            drainRetiringWorker(retirement.lease.languageTag, retirement)
                        },
                        onFailure = { retirement, error ->
                            Log.w(
                                LOG_TAG,
                                "Source switch forced disconnect after drain failure for " +
                                    retirement.lease.languageTag,
                                error,
                            )
                        },
                    )
                } finally {
                    // shutdown() closes the process-local Translator even when its drain callback
                    // failed. Slots remain globally closed until the new source generation commits.
                    workers.forEach { worker -> worker.disconnect(stopService = true) }
                    preparedWorkers.clear()
                    initializedWorkers.clear()
                    if (isOwnerCurrent()) {
                        sourceSessions.switchTo(normalized)
                        (modelManager as MlKitLanguageModelManager).onSourceChanged()
                        committed = true
                    }
                    slots.completeReset(reopen = true)
                }
            }
            if (!committed) {
                throw CancellationException("ML Kit source-language change was superseded")
            }
        }
    }

    internal fun captureSourceSession(): MlKitSourceSession = sourceSessions.capture()

    internal fun isSourceSessionCurrent(session: MlKitSourceSession): Boolean =
        sourceSessions.isCurrent(session)

    /**
     * Holds the target unavailable from drain through the real terminal state of model deletion.
     *
     * The reservation also covers a target that has no current process slot. Otherwise a live
     * prepare could open that target between the drain check and RemoteModelManager deletion.
     */
    internal suspend fun <T> removeTargetModel(
        targetLanguage: String,
        deleteModel: suspend (normalizedTarget: String, sharedBackendInUse: Boolean) -> T,
    ): T {
        val normalizedTarget = targetLanguage.toMlKitLanguage()
        return modelDownloads.withDownloadFinishedForRemoval(normalizedTarget) {
            runReservedModelRemoval(
                reserve = { slots.awaitTargetRemoval(targetLanguage) },
                drain = { removal ->
                    removal.retirement?.let { retirement ->
                        drainRetiringWorker(targetLanguage, retirement)
                    }
                },
                delete = { removal ->
                    // The artifact boundary excludes downloads. The native mutation lane and
                    // target reservation still survive until the real deletion Task completes.
                    serializedModelOperation {
                        deleteModel(normalizedTarget, removal.sharedBackendInUse)
                    }
                },
                complete = { removal ->
                    check(slots.completeTargetRemoval(removal)) {
                        "$targetLanguage 모델 삭제 예약이 완료 전에 교체되었습니다."
                    }
                },
                recover = { removal -> recoverTargetRemoval(targetLanguage, removal) },
            )
        }
    }

    private suspend fun drainAndRetireTarget(
        normalizedTarget: String,
        retirement: MlKitWorkerSlotRetirement,
    ) {
        var completed = false
        try {
            preparedWorkers.invalidate(normalizedTarget)
            initializedWorkers.invalidate(normalizedTarget)
            drainRetiringWorker(normalizedTarget, retirement)
            withContext(NonCancellable) {
                check(slots.completeRetirement(retirement)) {
                    "$normalizedTarget 번역 작업 슬롯이 삭제 준비 중 교체되었습니다."
                }
                // Commit the local completion flag in the same non-cancellable section as the
                // slot state. Otherwise prompt cancellation on context restoration could run the
                // recovery branch against an already-free slot and mask the real cancellation.
                completed = true
            }
        } finally {
            if (!completed) {
                // The one-way drain may finish after the caller deadline. Detach the old Binder
                // so a retry binds a fresh Service generation; model files remain untouched.
                withContext(NonCancellable) {
                    try {
                        workers[retirement.lease.slot].disconnect(stopService = false)
                    } finally {
                        check(slots.cancelRetirement(retirement)) {
                            "$normalizedTarget 번역 작업 슬롯 복구 소유권이 교체되었습니다."
                        }
                    }
                }
            }
        }
    }

    private suspend fun drainRetiringWorker(
        normalizedTarget: String,
        retirement: MlKitWorkerSlotRetirement,
    ) {
        val drained = withTimeoutOrNull(WORKER_DRAIN_TIMEOUT_MILLIS) {
            val result = withTargetWorker(
                lease = retirement.lease,
                targetLanguage = normalizedTarget,
                activityLabel = "모델 삭제 전 정리",
                retirement = retirement,
            ) { worker ->
                awaitWorkerRequest(worker, normalizedTarget, "model removal drain") {
                    requestId, callback ->
                    worker.drainAndCloseAsync(requestId, callback)
                }
            }
            result == WORKER_DRAINED_RESULT
        } ?: false
        check(drained) {
            "$normalizedTarget 번역 작업이 종료되지 않아 모델을 삭제하지 않았습니다."
        }
        withContext(NonCancellable) {
            workers[retirement.lease.slot].disconnect(stopService = false)
        }
    }

    private suspend fun recoverTargetRemoval(
        normalizedTarget: String,
        removal: MlKitWorkerTargetRemoval,
    ) {
        removal.retirement?.let { retirement ->
            runCatching {
                workers[retirement.lease.slot].disconnect(stopService = false)
            }.onFailure { error ->
                Log.w(LOG_TAG, "$normalizedTarget model-removal recovery disconnect failed", error)
            }
        }
        check(slots.cancelTargetRemoval(removal)) {
            "$normalizedTarget 모델 삭제 예약 복구 소유권이 교체되었습니다."
        }
    }

    /** Releases every bound language worker without allowing a cancelled cleanup to stop midway. */
    suspend fun releaseNativeResources() = withContext(NonCancellable) {
        // Invalidate engine objects before disconnecting, but retain language-to-slot ownership.
        // shutdown/unbind is one-way: the worker may still be finishing a non-cooperative Google
        // Task in onDestroy. Keeping the assignment means an immediate model deletion must bind a
        // successor generation and receive a real drain acknowledgement before deleting files.
        slots.invalidateLeasesPreservingAssignments()
        preparedWorkers.clear()
        initializedWorkers.clear()
        workers.forEach { worker -> worker.disconnect(stopService = true) }
    }

    override fun close() {
        slots.beginReset()
        try {
            preparedWorkers.clear()
            initializedWorkers.clear()
            workers.forEach { worker -> worker.close() }
        } finally {
            slots.completeReset(reopen = false)
        }
        modelOperations.close()
        modelDownloads.close()
    }

    private companion object {
        val WARMUP_SOURCE_TEXT = mapOf(
            "ar" to "مرحبا",
            "en" to "Hello",
            "es" to "Hola",
            "ja" to "こんにちは",
            "ko" to "안녕하세요",
            "zh" to "你好",
        )
        const val MAX_SOURCE_CHARACTERS = 2_000
        const val MAX_LIVE_TARGETS = MAX_SIMULTANEOUS_TRANSLATED_CHANNELS
        const val MODEL_READY_RESULT = "guidecast-model-ready"
        const val WORKER_DRAINED_RESULT = "guidecast-worker-drained"
        const val WORKER_WARMUP_TIMEOUT_MILLIS = 30_000L
        const val WORKER_DRAIN_TIMEOUT_MILLIS = 60_000L
        const val LOG_TAG = "GuideCastMlKit"
    }
}

/** A completed/dead first worker closes its transferred ticket; never reuse it for a cold retry. */
internal fun mlKitWorkerConnectionAttempts(hasNativeColdLoadTicket: Boolean): Int =
    if (hasNativeColdLoadTicket) 1 else MAX_MLKIT_WORKER_CONNECTION_ATTEMPTS

internal fun mlKitRequestMayUnlinkDeathRecipient(
    clientFinished: Boolean,
    nativeSubmissionPlanned: Boolean,
    nativeFinished: Boolean,
): Boolean = clientFinished && (!nativeSubmissionPlanned || nativeFinished)

/**
 * Records readiness against an exact Binder/process generation rather than a mere connection.
 * Identity comparison prevents a replacement worker from inheriting its predecessor's warm state.
 */
internal class PreparedWorkerGenerations<K : Any, G : Any> {
    private val generations = ConcurrentHashMap<K, G>()

    fun markCurrent(key: K, generation: G) {
        generations[key] = generation
    }

    fun isCurrent(key: K, liveGeneration: G?): Boolean {
        val prepared = generations[key] ?: return false
        val current = liveGeneration === prepared
        if (!current) generations.remove(key, prepared)
        return current
    }

    fun invalidate(key: K, expectedGeneration: G? = null) {
        if (expectedGeneration == null) {
            generations.remove(key)
        } else {
            generations.remove(key, expectedGeneration)
        }
    }

    fun clear() = generations.clear()
}

internal data class MlKitSourceSession(
    val languageTag: String,
    val generation: Long,
)

/** Small lock-protected epoch gate shared by engine factories and source switching. */
internal class MlKitSourceSessionCoordinator(initialLanguageTag: String) {
    private val lock = Any()
    private var current = MlKitSourceSession(initialLanguageTag, generation = 1L)

    fun capture(): MlKitSourceSession = synchronized(lock) { current }

    fun isCurrent(session: MlKitSourceSession): Boolean = synchronized(lock) { current == session }

    fun switchTo(languageTag: String): MlKitSourceSession = synchronized(lock) {
        if (current.languageTag == languageTag) return@synchronized current
        val generation = if (current.generation == Long.MAX_VALUE) 1L else current.generation + 1L
        MlKitSourceSession(languageTag, generation).also { current = it }
    }
}

/** Runs independent worker retirement while preserving structured caller cancellation. */
internal suspend fun <T> runIsolatedRetirements(
    retirements: Collection<T>,
    operation: suspend (T) -> Unit,
    onFailure: (T, Throwable) -> Unit = { _, _ -> },
): Map<T, Throwable> = supervisorScope {
    retirements.map { retirement ->
        async(start = CoroutineStart.UNDISPATCHED) {
            try {
                operation(retirement)
                null
            } catch (cancelled: CancellationException) {
                // Do not turn session/settings cancellation into an ordinary channel failure and
                // accidentally continue model preparation after the owner has gone away.
                throw cancelled
            } catch (error: Throwable) {
                onFailure(retirement, error)
                retirement to error
            }
        }
}.awaitAll().filterNotNull().toMap()
}

/**
 * Executes one target-model deletion as a transaction whose reservation outlives caller
 * cancellation once deletion has started.
 *
 * The caller can still cancel during reservation or drain. Immediately before entering delete we
 * honor that cancellation. After delete begins, its real completion and reservation commit/rollback
 * run in [NonCancellable] so a still-running Google Task can never overlap a new target lease.
 */
internal suspend fun <R, T> runReservedModelRemoval(
    reserve: suspend () -> R,
    drain: suspend (R) -> Unit,
    delete: suspend (R) -> T,
    complete: suspend (R) -> Unit,
    recover: suspend (R) -> Unit,
): T {
    val reservation = reserve()
    var terminal = false
    var primaryFailure: Throwable? = null
    try {
        drain(reservation)
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            val result = delete(reservation)
            complete(reservation)
            terminal = true
            result
        }
    } catch (error: Throwable) {
        primaryFailure = error
        throw error
    } finally {
        if (!terminal) {
            var recoveryFailure: Throwable? = null
            try {
                withContext(NonCancellable) { recover(reservation) }
            } catch (error: Throwable) {
                recoveryFailure = error
            } finally {
                terminal = true
            }
            recoveryFailure?.let { error ->
                val original = primaryFailure
                if (original != null) {
                    original.addSuppressed(error)
                } else {
                    throw error
                }
            }
        }
    }
}

/**
 * Owns ML Kit's non-reentrant JNI gate independently from a caller's realtime deadline.
 *
 * Cancelling a coroutine waiting for a Google Task only cancels its continuation; it does not
 * stop the underlying native translation. Running the operation in this provider-owned scope
 * keeps the exclusive native lane occupied until the Task really completes, so a timed-out
 * language cannot let a second language enter TranslateJni and abort the whole app process.
 */
internal class NativeOperationCoordinator(
    private val maxInFlightOperations: Int = DEFAULT_MAX_IN_FLIGHT_OPERATIONS,
) : Closeable {
    init {
        require(maxInFlightOperations > 0) {
            "maxInFlightOperations must be positive"
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val admission = Semaphore(maxInFlightOperations)
    private val queue = Channel<NativeOperationRequest<*>>(capacity = maxInFlightOperations)
    private val admittedRequests = ConcurrentHashMap.newKeySet<NativeOperationRequest<*>>()
    private val inFlightCount = AtomicInteger(0)
    private val closed = AtomicBoolean(false)

    internal val inFlightOperationCount: Int
        get() = inFlightCount.get()

    private val worker = scope.launch {
        for (request in queue) {
            try {
                request.executeIfActive()
            } finally {
                admittedRequests.remove(request)
                inFlightCount.decrementAndGet()
                admission.release()
            }
        }
    }.also { job ->
        job.invokeOnCompletion { scope.cancel() }
    }

    suspend fun <T> run(operation: suspend () -> T): T = run(
        onNativeFinished = {},
        operation = operation,
    )

    suspend fun <T> run(
        onNativeFinished: () -> Unit,
        operation: suspend () -> T,
    ): T {
        check(!closed.get()) { CLOSED_MESSAGE }
        admission.acquire()
        try {
            currentCoroutineContext().ensureActive()
            check(!closed.get()) { CLOSED_MESSAGE }
        } catch (error: Throwable) {
            admission.release()
            throw error
        }

        val request = NativeOperationRequest(operation, onNativeFinished)
        admittedRequests += request
        inFlightCount.incrementAndGet()
        val sendResult = queue.trySend(request)
        if (sendResult.isFailure) {
            admittedRequests.remove(request)
            inFlightCount.decrementAndGet()
            admission.release()
            val error = sendResult.exceptionOrNull()
                ?: IllegalStateException("ML Kit native operation queue rejected a request")
            request.cancelBeforeStart(error)
            throw error
        }

        return try {
            request.result.await()
        } catch (error: CancellationException) {
            // If the worker has not entered native code, discard this stale request. Once it has
            // entered, the CAS fails and the provider-owned worker deliberately finishes the
            // underlying Google Task before admitting the next JNI operation.
            request.cancelBeforeStart(error)
            throw error
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        queue.close()
        val closeError = IllegalStateException(CLOSED_MESSAGE)
        admittedRequests.forEach { request -> request.cancelBeforeStart(closeError) }
    }

    /** Waits until a Google Task already entered by the worker reaches its real terminal state. */
    suspend fun closeAndJoin() {
        close()
        worker.join()
    }

    private companion object {
        const val DEFAULT_MAX_IN_FLIGHT_OPERATIONS = 8
        const val CLOSED_MESSAGE = "ML Kit native operation coordinator is closed"
    }
}

private class NativeOperationRequest<T>(
    private val operation: suspend () -> T,
    private val onNativeFinished: () -> Unit,
) {
    val result = CompletableDeferred<T>()
    private val state = AtomicReference(NativeOperationRequestState.WAITING)
    private val terminalReported = AtomicBoolean(false)

    suspend fun executeIfActive() {
        if (!state.compareAndSet(
                NativeOperationRequestState.WAITING,
                NativeOperationRequestState.RUNNING,
            )
        ) {
            return
        }

        try {
            result.complete(operation())
        } catch (error: Throwable) {
            result.completeExceptionally(error)
        } finally {
            state.set(NativeOperationRequestState.COMPLETED)
            reportNativeFinished()
        }
    }

    fun cancelBeforeStart(cause: Throwable) {
        if (state.compareAndSet(
                NativeOperationRequestState.WAITING,
                NativeOperationRequestState.CANCELLED,
            )
        ) {
            result.completeExceptionally(cause)
            reportNativeFinished()
        }
    }

    private fun reportNativeFinished() {
        if (terminalReported.compareAndSet(false, true)) runCatching(onNativeFinished)
    }
}

private enum class NativeOperationRequestState {
    WAITING,
    RUNNING,
    CANCELLED,
    COMPLETED,
}

private class MlKitLanguageModelManager(
    private val provider: MlKitTranslationProvider,
) : LanguageModelManager {
    private val mutableStatuses = MutableStateFlow<List<LanguageModelStatus>>(emptyList())
    private val preparationStatusLock = Any()
    private val catalogLock = Any()
    private var catalogTask: Task<Set<TranslateRemoteModel>>? = null
    private var activePreparationGeneration: Long? = null
    override val statuses: StateFlow<List<LanguageModelStatus>> = mutableStatuses.asStateFlow()

    fun activatePreparationGeneration(generation: Long) = synchronized(preparationStatusLock) {
        if (activePreparationGeneration != generation) {
            mutableStatuses.value = interruptedModelPreparationStatuses(
                mutableStatuses.value,
                mutableStatuses.value.map { it.languageTag }.toSet(),
            )
        }
        activePreparationGeneration = generation
    }

    fun onSourceChanged() {
        // A target model alone is not READY for a new source. Drop the old pair snapshot until the
        // catalog has been checked against the newly selected source model.
        mutableStatuses.value = emptyList()
    }

    override suspend fun refresh(languageTags: Set<String>) = refresh(
        languageTags = languageTags,
        nativeOwnerGeneration = null,
        isNativeOwnerCurrent = { true },
    )

    suspend fun refresh(
        languageTags: Set<String>,
        nativeOwnerGeneration: Long?,
        isNativeOwnerCurrent: () -> Boolean,
    ) {
        // The settings screen may inspect the whole supported catalog. Only live preparation and
        // warm-up are bounded to the channels selected for one broadcast.
        require(languageTags.size <= MAX_INSPECTED_LANGUAGE_MODELS) {
            "Inspect no more than $MAX_INSPECTED_LANGUAGE_MODELS target languages"
        }
        val sourceSession = provider.captureSourceSession()
        // Catalog inspection does not enter TranslateJni. Never place a possibly unresponsive
        // read behind/in the native mutation lane: cancelling that lane cannot finish its Task.
        // Reuse an outstanding read on retry, rather than accumulating unresponsive requests.
        val task = synchronized(catalogLock) {
            catalogTask?.takeUnless { it.isComplete }
                ?: RemoteModelManager.getInstance()
                    .getDownloadedModels(TranslateRemoteModel::class.java)
                    .also { catalogTask = it }
        }
        val downloadedLanguages = awaitReadOnlyModelCatalog(task)
            .map(TranslateRemoteModel::getLanguage).toSet()
        if (!isNativeOwnerCurrent()) return
        check(provider.isSourceSessionCurrent(sourceSession)) {
            "ML Kit source language changed while model readiness was being checked"
        }
        languageTags.forEach { languageTag ->
            val targetLanguage = languageTag.toMlKitLanguage()
            require(targetLanguage != sourceSession.languageTag) {
                "ML Kit source and target languages must be different"
            }
            val readiness = if (isMlKitTranslationPairReady(
                    sourceLanguage = sourceSession.languageTag,
                    targetLanguage = targetLanguage,
                    downloadedLanguages = downloadedLanguages,
                )
            ) {
                ModelReadiness.READY
            } else {
                ModelReadiness.NOT_INSTALLED
            }
            updateIfCurrent(
                languageTag,
                readiness,
                nativeOwnerGeneration,
            )
        }
    }

    override suspend fun prepare(languageTags: Set<String>) = prepare(
        languageTags = languageTags,
        requestedSourceLanguageTag = provider.sourceLanguage,
        nativeOwnerGeneration = null,
        isNativeOwnerCurrent = { true },
        reconcileNativeTargets = true,
        initializeWithNativeAdmission = { _, initialize -> initialize() },
    )

    suspend fun prepare(
        languageTags: Set<String>,
        requestedSourceLanguageTag: String,
        nativeOwnerGeneration: Long?,
        isNativeOwnerCurrent: () -> Boolean,
        reconcileNativeTargets: Boolean,
        initializeWithNativeAdmission: suspend (
            languageTag: String,
            initialize: suspend () -> Unit,
        ) -> Unit,
    ) {
        try {
            prepareOwned(languageTags, requestedSourceLanguageTag, nativeOwnerGeneration,
                isNativeOwnerCurrent, reconcileNativeTargets, initializeWithNativeAdmission)
        } catch (cancelled: CancellationException) {
            synchronized(preparationStatusLock) {
                // Never acquire the application's owner lock inside the provider status lock:
                // owner activation takes them in the opposite order. The generation is sufficient.
                if (nativeOwnerGeneration == null || nativeOwnerGeneration == activePreparationGeneration) {
                    mutableStatuses.value = interruptedModelPreparationStatuses(
                        mutableStatuses.value, languageTags,
                    )
                }
            }
            throw cancelled
        }
    }

    private suspend fun prepareOwned(
        languageTags: Set<String>,
        requestedSourceLanguageTag: String,
        nativeOwnerGeneration: Long?,
        isNativeOwnerCurrent: () -> Boolean,
        reconcileNativeTargets: Boolean,
        initializeWithNativeAdmission: suspend (String, suspend () -> Unit) -> Unit,
    ) {
        require(languageTags.size <= MAX_SIMULTANEOUS_TRANSLATED_CHANNELS) {
            "Prepare no more than $MAX_SIMULTANEOUS_TRANSLATED_CHANNELS target languages"
        }
        val requestedSource = requestedSourceLanguageTag.toMlKitLanguage()
        val sourceSession = provider.captureSourceSession()
        require(languageTags.none { it.toMlKitLanguage() == requestedSource }) {
            "ML Kit source and target languages must be different"
        }
        // A previous settings selection may have occupied all stable slots. Retire only
        // languages no longer selected before assigning the next batch; selected live channels
        // retain their process identity and cannot be contaminated by a stale callback.
        val retirementFailures = if (reconcileNativeTargets && isNativeOwnerCurrent()) {
            provider.releaseTargetsExcept(languageTags, nativeOwnerGeneration)
        } else {
            emptyMap()
        }
        retirementFailures.forEach { (languageTag, error) ->
            if (!isNativeOwnerCurrent()) return@forEach
            updateIfCurrent(
                languageTag,
                ModelReadiness.FAILED,
                nativeOwnerGeneration,
                "이전 언어 작업 공간 정리 실패: " +
                    (error.message ?: error.javaClass.simpleName),
            )
        }

        // The source artifact is shared by every selected target. Download/verify it exactly once
        // before any worker/JNI admission, instead of making private processes race the same
        // RemoteModelManager task while holding native-load permits.
        if (isNativeOwnerCurrent()) {
            languageTags.forEach { languageTag ->
                updateIfCurrent(
                    languageTag,
                    ModelReadiness.DOWNLOADING,
                    nativeOwnerGeneration,
                )
            }
        }
        try {
            provider.downloadModelFile(requestedSource)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (!isNativeOwnerCurrent()) return
            if (isNativeOwnerCurrent()) {
                languageTags.forEach { languageTag ->
                    updateIfCurrent(
                        languageTag,
                        ModelReadiness.FAILED,
                        nativeOwnerGeneration,
                        error.message ?: "ML Kit source model download failed",
                    )
                }
            }
            throw IllegalStateException("ML Kit 원문 모델 준비에 실패했습니다.", error)
        }
        if (isNativeOwnerCurrent()) check(
            provider.isSourceSessionCurrent(sourceSession) &&
                sourceSession.languageTag == requestedSource,
        ) { "ML Kit source language changed during model preparation" }
        val failures = supervisorScope {
            languageTags.sorted().map { languageTag ->
                async {
                    if (isNativeOwnerCurrent()) {
                        updateIfCurrent(
                            languageTag,
                            ModelReadiness.DOWNLOADING,
                            nativeOwnerGeneration,
                        )
                    }
                    try {
                        // Network and file validation never consume a process-wide native-load
                        // ticket. Only the worker-local Translator construction/model task below
                        // enters the caller-supplied admission boundary.
                        provider.downloadModelFile(languageTag)
                        if (!isNativeOwnerCurrent()) return@async null
                        if (isNativeOwnerCurrent()) check(
                            provider.isSourceSessionCurrent(sourceSession) &&
                                sourceSession.languageTag == requestedSource,
                        ) { "ML Kit source language changed during model preparation" }
                        updateIfCurrent(
                            languageTag,
                            ModelReadiness.VERIFYING,
                            nativeOwnerGeneration,
                        )
                        val completed = if (isNativeOwnerCurrent()) {
                            withTimeoutOrNull(MODEL_PREPARATION_TIMEOUT_MILLIS) {
                                initializeWithNativeAdmission(languageTag) {
                                    // Settings or an earlier broadcast may have initialized the exact
                                    // Binder generation while this language waited for its permit.
                                    if (!provider.hasActiveInitializedWorker(languageTag)) {
                                        provider.prepareTarget(
                                            languageTag,
                                            nativeOwnerGeneration,
                                        )
                                    }
                                }
                                true
                            }
                        } else {
                            // A broadcast takeover leaves the verified offline files available but
                            // never lets this stale settings request reclaim a live worker.
                            true
                        } ?: false
                        check(completed) {
                            "$languageTag ML Kit 모델 준비 시간이 초과되었습니다."
                        }
                        if (isNativeOwnerCurrent()) check(
                            provider.isSourceSessionCurrent(sourceSession) &&
                                sourceSession.languageTag == requestedSource,
                        ) { "ML Kit source language changed during model preparation" }
                        updateIfCurrent(
                            languageTag,
                            ModelReadiness.READY,
                            nativeOwnerGeneration,
                        )
                        null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        if (isNativeOwnerCurrent() &&
                            provider.isSourceSessionCurrent(sourceSession)
                        ) {
                            updateIfCurrent(
                                languageTag,
                                ModelReadiness.FAILED,
                                nativeOwnerGeneration,
                                error.message ?: "ML Kit model download failed",
                            )
                        }
                        languageTag to error
                    }
                }
            }.mapNotNull { it.await() }
        }
        check(failures.isEmpty()) {
            "모델 준비 실패: ${failures.joinToString { it.first }}. " +
                "인터넷 연결과 저장 공간을 확인하세요."
        }
        if (isNativeOwnerCurrent()) check(
            provider.isSourceSessionCurrent(sourceSession) &&
                sourceSession.languageTag == requestedSource,
        ) { "ML Kit source language changed during model preparation" }
    }

    override suspend fun remove(languageTag: String) {
        val target = languageTag.toMlKitLanguage()
        require(target != provider.sourceLanguage) {
            "현재 원문 언어 모델은 출력 언어로 삭제할 수 없습니다."
        }
        val siblingInstalled = mutableStatuses.value.any {
            it.languageTag != languageTag &&
                it.languageTag.toMlKitLanguage() == target &&
                it.readiness != ModelReadiness.NOT_INSTALLED
        }
        provider.removeTargetModel(languageTag) { normalizedTarget, sharedBackendInUse ->
            if (normalizedTarget != "en" && !siblingInstalled && !sharedBackendInUse) {
                val model = TranslateRemoteModel.Builder(normalizedTarget).build()
                RemoteModelManager.getInstance().deleteDownloadedModel(model).awaitCompletion()
            }
        }
        if (target == "en") {
            // Only the worker can be retired for built-in English. Re-check the source artifact
            // so a Korean→English pair is never labelled READY when Korean is missing.
            refresh(setOf(languageTag))
        } else {
            update(languageTag, ModelReadiness.NOT_INSTALLED)
        }
    }

    private fun update(
        languageTag: String,
        readiness: ModelReadiness,
        error: String? = null,
    ) {
        val next = LanguageModelStatus(
            languageTag = languageTag,
            readiness = readiness,
            errorMessage = error,
        )
        mutableStatuses.update { current ->
            (current.filterNot { it.languageTag == languageTag } + next)
                .sortedBy(LanguageModelStatus::languageTag)
        }
    }

    /** Rejects a stale settings status at the same lock boundary as owner-generation activation. */
    private fun updateIfCurrent(
        languageTag: String,
        readiness: ModelReadiness,
        preparationGeneration: Long?,
        error: String? = null,
    ): Boolean = synchronized(preparationStatusLock) {
        if (preparationGeneration != null &&
            activePreparationGeneration != preparationGeneration
        ) {
            return@synchronized false
        }
        update(languageTag, readiness, error)
        true
    }
}

private const val MAX_INSPECTED_LANGUAGE_MODELS = 64
private const val MODEL_PREPARATION_TIMEOUT_MILLIS = 10L * 60 * 1_000

internal fun String.toMlKitLanguage(): String =
    TranslateLanguage.fromLanguageTag(this)
        ?: TranslateLanguage.fromLanguageTag(substringBefore('-'))
        ?: error("ML Kit does not support language: $this")

internal fun isMlKitArtifactReady(language: String, downloadedLanguages: Set<String>): Boolean {
    val normalized = language.toMlKitLanguage()
    return normalized == "en" || normalized in downloadedLanguages
}

internal fun isMlKitTranslationPairReady(
    sourceLanguage: String,
    targetLanguage: String,
    downloadedLanguages: Set<String>,
): Boolean = isMlKitArtifactReady(sourceLanguage, downloadedLanguages) &&
    isMlKitArtifactReady(targetLanguage, downloadedLanguages)

internal suspend fun Task<Void>.awaitCompletion(): Unit = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(Unit) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}

internal suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}

/** A metadata read may time out without releasing any native inference/mutation admission. */
internal suspend fun <T> awaitReadOnlyModelCatalog(
    task: Task<T>,
    timeoutMillis: Long = 8_000L,
): T = withTimeout(timeoutMillis) {
    suspendCancellableCoroutine { continuation ->
        task.addOnCompleteListener(java.util.concurrent.Executor { it.run() }) { result ->
            if (continuation.isActive) {
                when {
                    result.isCanceled -> continuation.cancel()
                    result.isSuccessful -> continuation.resume(result.result)
                    else -> continuation.resumeWithException(
                        result.exception ?: IllegalStateException("번역 모델 목록 확인 실패"),
                    )
                }
            }
        }
    }
}

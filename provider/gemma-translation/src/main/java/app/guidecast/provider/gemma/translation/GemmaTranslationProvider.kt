package app.guidecast.provider.gemma.translation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import app.guidecast.core.translation.ContextualTextTranslationEngine
import app.guidecast.core.translation.currentNativeColdLoadTicket
import app.guidecast.core.translation.TranslationEngineProvider
import app.guidecast.core.translation.TranslationGlossaryContext
import app.guidecast.core.translation.TranslationReviewContext
import kotlinx.coroutines.currentCoroutineContext
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Client for the exact Gemma 4 E2B LiteRT-LM model selected by
 * google-gemma/gemma-translator.
 *
 * LiteRT-LM runs in :gemma_inference instead of the broadcast process. ML Kit Translate and
 * LiteRT both keep substantial process-global native state; process isolation prevents switching
 * translation backends from stalling the stream server or terminating microphone capture.
 */
class GemmaTranslationProvider(context: Context) : TranslationEngineProvider, Closeable {
    private val applicationContext = context.applicationContext
    val modelManager = GemmaModelManager(applicationContext)
    private val connectionMutex = Mutex()
    private val generations = GemmaGenerationCoordinator()
    private val bindingState =
        GemmaServiceBindingState<ServiceConnection, IGuideCastGemmaInference>()
    private val preparedWorkerState = GemmaPreparedWorkerState()
    private val nativeCrashCircuitBreaker = GemmaNativeCrashCircuitBreaker()
    private val applyingModel = AtomicBoolean(false)

    /** Whether the current provider still owns a live Gemma worker Binder. */
    fun hasActiveWorker(): Boolean = bindingState.snapshot().service?.asBinder()?.let { binder ->
        runCatching { binder.isBinderAlive }.getOrDefault(false)
    } == true

    /** True only after a successful inference on the Binder connection that is still live. */
    fun hasActivePreparedWorker(): Boolean =
        preparedWorkerState.isReusable(hasActiveWorker())

    /** True when automatic use of the selected model awaits an explicit successful self-test. */
    fun isAutomaticRetryBlocked(): Boolean =
        nativeCrashCircuitBreaker.isBlocked(modelManager.selectedVariant.id)

    override fun engineFor(targetLanguageTag: String): ContextualTextTranslationEngine {
        val configuredTarget = targetLanguageTag.normalizedGemmaLanguage()
        require(configuredTarget in SUPPORTED_LANGUAGES) {
            "Gemma Translator target is not supported"
        }
        return object : ContextualTextTranslationEngine {
            override suspend fun translateWithContext(
                text: String,
                contextBefore: String?,
                sourceLanguageTag: String,
                targetLanguageTag: String,
            ): String {
                check(!applyingModel.get()) { "모델 실행 점검·적용 중입니다. 완료 후 통역을 시작하세요." }
                require(targetLanguageTag.normalizedGemmaLanguage() == configuredTarget)
                return translate(
                    text = text,
                    contextBefore = contextBefore,
                    sourceLanguageTag = sourceLanguageTag,
                    targetLanguageTag = configuredTarget,
                    timeoutMillis = REALTIME_TRANSLATION_TIMEOUT_MILLIS,
                )
            }
        }
    }

    suspend fun selfTest(): String {
        resetEngineSafely()
        val testedModelId = modelManager.selectedVariant.id
        val translated = translate(
            text = "안녕하세요",
            contextBefore = null,
            sourceLanguageTag = "ko",
            targetLanguageTag = "en",
            timeoutMillis = SELF_TEST_TIMEOUT_MILLIS,
            explicitVerification = true,
        )
        val normalized = translated.lowercase()
        check("hello" in normalized || normalized == "hi" || normalized.startsWith("hi ")) {
            "Gemma Translator 자체 점검 결과가 예상 번역이 아닙니다: $translated"
        }
        nativeCrashCircuitBreaker.rearmAfterSuccessfulExplicitVerification(testedModelId)
        return translated
    }

    /**
     * Binds the isolated worker and exercises one tiny private inference before audience audio is
     * accepted. The result is deliberately discarded: it never enters transcript, TTS or stream
     * state. Keeping this provider bound leaves both the LiteRT engine and first-inference kernels
     * ready while the live path retains a separate bounded recovery deadline.
     */
    suspend fun warmup(
        targetLanguageTag: String,
        sourceLanguageTag: String = "ko",
        timeoutMillis: Long = PREPARATION_WARMUP_TIMEOUT_MILLIS,
    ) {
        check(!applyingModel.get()) { "모델 적용 중입니다. 완료 후 다시 준비하세요." }
        require(timeoutMillis in 5_000L..120_000L)
        val source = sourceLanguageTag.normalizedGemmaLanguage()
        val target = targetLanguageTag.normalizedGemmaLanguage()
        require(supportsTranslation(source, target)) {
            "Gemma Translator warmup language pair is not supported"
        }
        translate(
            text = requireNotNull(WARMUP_SOURCE_TEXT[source]),
            contextBefore = null,
            sourceLanguageTag = source,
            targetLanguageTag = target,
            timeoutMillis = timeoutMillis,
        )
    }

    private suspend fun translate(
        text: String,
        contextBefore: String?,
        sourceLanguageTag: String,
        targetLanguageTag: String,
        timeoutMillis: Long,
        explicitVerification: Boolean = false,
    ): String {
        require(text.isNotBlank() && text.length <= MAX_SOURCE_CHARACTERS)
        val source = sourceLanguageTag.normalizedGemmaLanguage()
        val target = targetLanguageTag.normalizedGemmaLanguage()
        require(source in SUPPORTED_LANGUAGES) {
            "Gemma Translator source is not supported: $sourceLanguageTag"
        }
        require(target in SUPPORTED_LANGUAGES) {
            "Gemma Translator target is not supported: $targetLanguageTag"
        }
        require(source != target) { "Gemma Translator source and target must be different" }
        return modelManager.withSelectedModel {
            val selectedModelId = modelManager.selectedVariant.id
            if (!explicitVerification) {
                nativeCrashCircuitBreaker.requireAutomaticAttemptAllowed(selectedModelId)
            }
            generations.runGeneration { requestId ->
            try {
                // A queued language may have passed the outer check before another request died.
                // Recheck after acquiring the generation so no queued caller can respawn it.
                if (!explicitVerification) {
                    nativeCrashCircuitBreaker.requireAutomaticAttemptAllowed(selectedModelId)
                }
                val translated = withTimeoutOrNull(timeoutMillis) {
                    // Binding is part of the realtime deadline. A service process that never connects
                    // must not leave self-test or the first broadcast sentence suspended indefinitely.
                    val service = connectionMutex.withLock { remote() }
                    awaitTranslation(
                        requestId = requestId,
                        service = service,
                        text = text,
                        contextBefore = contextBefore.orEmpty().take(MAX_CONTEXT_CHARACTERS),
                        sourceLanguageTag = source,
                        targetLanguageTag = if (targetLanguageTag.equals("zh-TW", ignoreCase = true)) "zh-TW" else target,
                        selectedModelId = selectedModelId,
                    )
                } ?: throw GemmaRealtimeTimeoutException(
                    "Gemma 실시간 번역이 ${timeoutMillis}ms 안에 끝나지 않아 경량 오프라인 번역으로 전환합니다.",
                )
                check(translated.isNotBlank()) {
                    "Gemma Translator가 번역문을 생성하지 않았습니다."
                }
                translated.also { preparedWorkerState.markSuccessfulInference() }
            } catch (cancelled: CancellationException) {
                preparedWorkerState.invalidate()
                throw cancelled
            } catch (error: Throwable) {
                preparedWorkerState.invalidate()
                throw error
            }
        } }
    }

    private suspend fun awaitTranslation(
        requestId: Long,
        service: IGuideCastGemmaInference,
        text: String,
        contextBefore: String,
        sourceLanguageTag: String,
        targetLanguageTag: String,
        selectedModelId: String,
    ): String {
        val nativeTicket = currentNativeColdLoadTicket()
        val glossaryHints = currentCoroutineContext()[TranslationGlossaryContext]?.hints.orEmpty()
        val review = currentCoroutineContext()[TranslationReviewContext]
        val reviewDraft = review?.let {
            require(it.originalText == text &&
                it.sourceLanguageTag.equals(sourceLanguageTag, ignoreCase = true) &&
                it.targetLanguageTag.normalizedGemmaLanguage() == targetLanguageTag.normalizedGemmaLanguage()) {
                "Translation review context does not match its request"
            }
            require(it.draftTranslation.length <= 1_200) { "Translation review draft is too long" }
            it.draftTranslation
        }.orEmpty()
        return suspendCancellableCoroutine { continuation ->
            val completion = GemmaRequestCompletionState()
            val binder = service.asBinder()
            val lifecycleLock = Any()
            val nativeSubmissionPlanned = AtomicBoolean(false)
            val nativeFinished = AtomicBoolean(false)
            lateinit var deathRecipient: IBinder.DeathRecipient

            fun unlinkDeathRecipient() {
                if (completion.takeDeathRecipientLink()) {
                    runCatching { binder.unlinkToDeath(deathRecipient, 0) }
                }
            }

            fun unlinkIfFullyTerminal() {
                val mayUnlink = synchronized(lifecycleLock) {
                    completion.mayReleaseDeathRecipient(
                        nativeSubmissionPlanned = nativeSubmissionPlanned.get(),
                        nativeFinished = nativeFinished.get(),
                    )
                }
                if (mayUnlink) unlinkDeathRecipient()
            }

            fun finishNative() {
                if (nativeFinished.compareAndSet(false, true)) {
                    nativeTicket?.completeNative()
                    // Keep observing process death until the one-way result and native terminal have
                    // both arrived. Callback dispatch ordering must not become a liveness assumption.
                    unlinkIfFullyTerminal()
                }
            }

            fun fail(error: Throwable) {
                if (completion.tryComplete()) {
                    unlinkIfFullyTerminal()
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }

            deathRecipient = IBinder.DeathRecipient {
                // Process death is a definitive native-memory boundary even if no onFinished Binder
                // transaction could be delivered.
                if (shouldLatchGemmaNativeWorkerDeath(
                        nativeSubmissionPlanned = nativeSubmissionPlanned.get(),
                        nativeFinished = nativeFinished.get(),
                    )
                ) {
                    nativeCrashCircuitBreaker.recordDefiniteWorkerDeath(selectedModelId)
                }
                finishNative()
                fail(GemmaNativeWorkerDiedException())
            }
            val callback = object : IGuideCastGemmaInferenceCallback.Stub() {
                override fun onSuccess(callbackRequestId: Long, translatedText: String?) {
                    if (callbackRequestId != requestId) return
                    if (completion.tryComplete()) {
                        unlinkIfFullyTerminal()
                        if (continuation.isActive) {
                            continuation.resume(translatedText.orEmpty())
                        }
                    }
                }

                override fun onError(callbackRequestId: Long, message: String?) {
                    if (callbackRequestId != requestId) return
                    fail(IllegalStateException(message?.take(500) ?: "Gemma 실시간 번역 오류"))
                }

                override fun onFinished(callbackRequestId: Long) {
                    if (callbackRequestId == requestId) finishNative()
                }
            }

            continuation.invokeOnCancellation {
                if (completion.tryComplete()) {
                    unlinkIfFullyTerminal()
                    runCatching { service.cancel(requestId) }
                }
            }
            try {
                binder.linkToDeath(deathRecipient, 0)
                val maySubmit = synchronized(lifecycleLock) {
                    val completedWhileLinking = completion.markDeathRecipientLinked()
                    if (completedWhileLinking || !continuation.isActive) {
                        false
                    } else {
                        nativeSubmissionPlanned.set(true)
                        true
                    }
                }
                if (!maySubmit) {
                    // Cancellation can win between registration and linkToDeath.
                    // Remove a late link and never send the request.
                    unlinkDeathRecipient()
                    return@suspendCancellableCoroutine
                }
                try {
                    nativeTicket?.transferToNative()
                    service.translateAsync(
                        requestId,
                        selectedModelId,
                        text,
                        contextBefore,
                        sourceLanguageTag,
                        targetLanguageTag,
                        glossaryHints,
                        reviewDraft,
                        callback,
                    )
                } catch (error: Throwable) {
                    // A local Binder submission failure cannot have a later worker acknowledgement.
                    if (error is android.os.DeadObjectException) {
                        nativeCrashCircuitBreaker.recordDefiniteWorkerDeath(selectedModelId)
                    }
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

    /** Serializes lifecycle cleanup with the single native LiteRT generation lane. */
    suspend fun resetEngineSafely() {
        generations.cleanupAll { cancelThroughRequestId ->
            resetAndDisconnect(cancelThroughRequestId)
        }
    }

    suspend fun selectModel(variant: GemmaModelVariant) {
        variant.compatibilityIssue(android.os.Build.VERSION.SDK_INT)?.let { error(it) }
        generations.cleanupAll { cancelThroughRequestId ->
            if (modelManager.selectedVariant != variant) {
                val previous = modelManager.selectedVariant
                try {
                    modelManager.selectVariant(variant)
                } finally {
                    // A cancelled UI refresh after the durable selection commit must still
                    // invalidate the old prepared worker, without cancelling the new model.
                    if (modelManager.selectedVariant != previous) {
                        withContext(NonCancellable) { resetAndDisconnect(cancelThroughRequestId) }
                    }
                }
            }
        }
    }

    /** Only run after download verification and when the operator has stopped translation use. */
    suspend fun applyVerifiedModel(variant: GemmaModelVariant): String {
        check(applyingModel.compareAndSet(false, true)) { "다른 모델 적용이 진행 중입니다." }
        val previous = modelManager.selectedVariant
        try {
            modelManager.beginApplication()
            selectModel(variant)
            modelManager.markEngineTesting()
            val result = selfTest()
            modelManager.markRuntimeReady()
            modelManager.finishApplication()
            return result
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try {
                    resetEngineSafely()
                    selectModel(previous)
                    modelManager.finishApplication()
                } catch (restoreFailure: Throwable) {
                    // Keep the durable recovery journal for the next process start.
                    failure.addSuppressed(restoreFailure)
                }
            }
            throw failure
        } finally {
            applyingModel.set(false)
        }
    }

    /**
     * Cleans only the request boundary captured when Gemma actually failed.
     *
     * Failure handling is intentionally asynchronous in the broadcast service so ML Kit fallback
     * is not delayed by native cleanup. A recovery request may therefore start before this method
     * gets CPU time. In that case the old cleanup is obsolete and must not reset or disconnect the
     * connection now owned by the recovery request.
     */
    suspend fun resetAfterLatestFailureSafely(): Boolean =
        generations.cleanupLatestFailure { failedRequestId ->
            resetAndDisconnect(failedRequestId)
        }

    private suspend fun resetAndDisconnect(cancelThroughRequestId: Long) {
        connectionMutex.withLock {
            preparedWorkerState.invalidate()
            // This one-way reset can queue behind a native call that ignored cancellation. The
            // process-local service-generation gate prevents a replacement service from touching
            // its runtime until this predecessor really closes.
            runCatching {
                bindingState.snapshot().service?.resetEngine(cancelThroughRequestId)
            }
            disconnect()
        }
    }

    private suspend fun remote(): IGuideCastGemmaInference {
        val cached = bindingState.snapshot()
        cached.service?.let { service ->
            if (runCatching { service.asBinder().isBinderAlive }.getOrDefault(false)) return service
            disconnectConnection(cached.connection)
        }
        return bindRemote()
    }

    private suspend fun bindRemote(): IGuideCastGemmaInference =
        suspendCancellableCoroutine { continuation ->
            preparedWorkerState.invalidate()
            val bindCompleted = AtomicBoolean(false)
            val bindingCancelled = AtomicBoolean(false)
            fun failBinding(error: Throwable) {
                if (bindCompleted.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resumeWithException(error)
                }
            }

            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val connected = IGuideCastGemmaInference.Stub.asInterface(binder)
                    if (connected == null) {
                        failBinding(IllegalStateException("Gemma 추론 서비스에 연결하지 못했습니다."))
                        disconnectConnection(this)
                        return
                    }

                    val accepted = bindingState.publishIfOwned(
                        candidate = this,
                        connectedService = connected,
                        accepting = !bindCompleted.get() && continuation.isActive,
                    )
                    if (!accepted) {
                        // A callback from a cancelled/older session may unbind only itself. It
                        // must never publish over a newer Gemma connection.
                        disconnectConnection(this)
                        failBinding(IllegalStateException("Gemma 연결 요청이 취소되었습니다."))
                        return
                    }

                    if (bindCompleted.compareAndSet(false, true) && continuation.isActive) {
                        continuation.resume(connected)
                    } else {
                        disconnectConnection(this)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    disconnectConnection(this)
                    failBinding(IllegalStateException("Gemma 추론 서비스 연결이 끊겼습니다."))
                }

                override fun onBindingDied(name: ComponentName?) {
                    disconnectConnection(this)
                    failBinding(IllegalStateException("Gemma 추론 작업 공간이 종료되었습니다."))
                }

                override fun onNullBinding(name: ComponentName?) {
                    disconnectConnection(this)
                    failBinding(IllegalStateException("Gemma 추론 서비스가 빈 연결을 반환했습니다."))
                }
            }

            check(bindingState.install(connection)) { "A Gemma service connection is already active" }
            continuation.invokeOnCancellation {
                bindingCancelled.set(true)
                bindCompleted.compareAndSet(false, true)
                disconnectConnection(connection)
            }

            if (!continuation.isActive) {
                bindCompleted.compareAndSet(false, true)
                disconnectConnection(connection)
                return@suspendCancellableCoroutine
            }

            val bound = runCatching {
                applicationContext.bindService(
                    Intent(applicationContext, GemmaInferenceService::class.java),
                    connection,
                    // Keep the inference process eligible to run while this client is actively
                    // bound. BIND_WAIVE_PRIORITY makes Android 14+ treat the worker as cached;
                    // the cached-app freezer can kill a synchronous Binder inference in flight.
                    Context.BIND_AUTO_CREATE,
                )
            }.getOrElse { error ->
                disconnectConnection(connection)
                failBinding(error)
                false
            }
            if (!bound) {
                disconnectConnection(connection)
                failBinding(IllegalStateException("Gemma 추론 서비스를 시작하지 못했습니다."))
            } else if (bindingCancelled.get()) {
                // Cancellation can land after the pre-bind isActive check but before
                // bindService() returns. Unbind once more now that Android owns the binding.
                disconnectConnection(connection)
            }
        }

    private fun disconnect() = disconnectConnection(expectedConnection = null)

    private fun disconnectConnection(expectedConnection: ServiceConnection?): Boolean {
        val released = bindingState.release(expectedConnection)
        if (!released.owned) {
            // A late callback belongs to an obsolete binding. Unbind that exact connection but
            // preserve whichever newer session currently owns the shared Gemma provider.
            expectedConnection?.let { runCatching { applicationContext.unbindService(it) } }
            return false
        }
        preparedWorkerState.invalidate()
        released.connection?.let { runCatching { applicationContext.unbindService(it) } }
        return true
    }

    override fun close() {
        disconnect()
    }

    companion object {
        private const val MAX_SOURCE_CHARACTERS = 600
        private const val MAX_CONTEXT_CHARACTERS = 300
        private const val REALTIME_TRANSLATION_TIMEOUT_MILLIS = 10_000L
        private const val PREPARATION_WARMUP_TIMEOUT_MILLIS = 30_000L
        private const val SELF_TEST_TIMEOUT_MILLIS = 10 * 60 * 1_000L
        private val WARMUP_SOURCE_TEXT = mapOf(
            "ar" to "مرحبا",
            "en" to "Hello",
            "es" to "Hola",
            "ja" to "こんにちは",
            "zh" to "你好",
            "ko" to "안녕",
        )
        // Exact language set exposed by google-gemma/gemma-translator commit 47f9b3b. Dutch is
        // intentionally an ML Kit output extension and must never be represented as Gemma.
        private val SUPPORTED_LANGUAGES = WARMUP_SOURCE_TEXT.keys

        fun supportsTargetLanguage(languageTag: String): Boolean =
            languageTag.normalizedGemmaLanguage() in SUPPORTED_LANGUAGES

        fun supportsSourceLanguage(languageTag: String): Boolean =
            languageTag.normalizedGemmaLanguage() in SUPPORTED_LANGUAGES

        fun supportsTranslation(sourceLanguageTag: String, targetLanguageTag: String): Boolean {
            val source = sourceLanguageTag.normalizedGemmaLanguage()
            val target = targetLanguageTag.normalizedGemmaLanguage()
            return source in SUPPORTED_LANGUAGES && target in SUPPORTED_LANGUAGES && source != target
        }
    }
}

private fun String.normalizedGemmaLanguage(): String = substringBefore('-').lowercase(Locale.ROOT)

/**
 * Serializes the provider's single LiteRT generation lane and binds cleanup to the request that
 * failed. This state is Android-free so the timeout/recovery ordering can be regression-tested on
 * the JVM without timing-dependent Binder mocks.
 */
internal class GemmaGenerationCoordinator {
    private val mutex = Mutex()
    private var nextRequestId = 0L
    private var latestFailedRequestId = NO_REQUEST_ID

    suspend fun <T> runGeneration(block: suspend (requestId: Long) -> T): T {
        mutex.lock()
        val requestId = nextRequestId++
        try {
            return block(requestId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            latestFailedRequestId = maxOf(latestFailedRequestId, requestId)
            throw error
        } finally {
            mutex.unlock()
        }
    }

    suspend fun cleanupLatestFailure(cleanup: suspend (failedRequestId: Long) -> Unit): Boolean {
        mutex.lock()
        try {
            val failedRequestId = latestFailedRequestId
            if (failedRequestId == NO_REQUEST_ID) return false

            val latestStartedRequestId = nextRequestId - 1L
            if (latestStartedRequestId > failedRequestId) {
                // A later recovery already ran while this asynchronous cleanup was waiting. Never
                // disconnect its connection or reset the runtime it just proved usable.
                if (latestFailedRequestId == failedRequestId) {
                    latestFailedRequestId = NO_REQUEST_ID
                }
                return false
            }

            cleanup(failedRequestId)
            if (latestFailedRequestId == failedRequestId) {
                latestFailedRequestId = NO_REQUEST_ID
            }
            return true
        } finally {
            mutex.unlock()
        }
    }

    suspend fun cleanupAll(cleanup: suspend (cancelThroughRequestId: Long) -> Unit) {
        mutex.lock()
        try {
            cleanup(nextRequestId - 1L)
            latestFailedRequestId = NO_REQUEST_ID
        } finally {
            mutex.unlock()
        }
    }

    private companion object {
        const val NO_REQUEST_ID = -1L
    }
}

private class GemmaRealtimeTimeoutException(message: String) : IllegalStateException(message)

/** Android-free proof that a live Binder belongs to a worker which completed real inference. */
internal class GemmaPreparedWorkerState {
    private val successfulInference = AtomicBoolean(false)

    fun markSuccessfulInference() {
        successfulInference.set(true)
    }

    fun invalidate() {
        successfulInference.set(false)
    }

    fun isReusable(binderAlive: Boolean): Boolean = binderAlive && successfulInference.get()
}

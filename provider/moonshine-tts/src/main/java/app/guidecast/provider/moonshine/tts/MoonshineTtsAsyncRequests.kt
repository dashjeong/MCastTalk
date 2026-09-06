package app.guidecast.provider.moonshine.tts

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore as CoroutineSemaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface MoonshineTtsClientResult {
    data object Prepared : MoonshineTtsClientResult
    data class Synthesized(val path: String) : MoonshineTtsClientResult
}

internal data class MoonshineTtsLanguageSelectionResult(
    val drained: Boolean,
    val pendingLanguageTags: Set<String>,
)

/** Records cancellation across the non-atomic Android bindService boundary. */
internal class MoonshineTtsBindingAttemptState {
    private val cancelled = AtomicBoolean(false)

    val wasCancelled: Boolean
        get() = cancelled.get()

    fun cancel() {
        cancelled.set(true)
    }

    fun mayStartBinding(continuationActive: Boolean): Boolean =
        continuationActive && !cancelled.get()
}

/** Tracks complete client operations, including worker binding, without making release wait forever. */
internal class MoonshineTtsClientOperationRegistry {
    private val stateLock = Any()
    private var accepting = true
    private var closed = false
    /**
     * Null means settings/test code may prepare any catalog voice. A live translation session
     * installs its selected set before retiring old bindings so a late operation from the replaced
     * session cannot bind an unselected extra voice again.
     */
    private var selectedLanguageTags: Set<String>? = null
    private val operations = mutableMapOf<Long, ClientOperation>()

    fun register(operationId: Long, languageTag: String, job: Job): Boolean =
        synchronized(stateLock) {
            if (operationId <= 0L || languageTag.isBlank() || closed || !accepting) {
                return@synchronized false
            }
            if (selectedLanguageTags?.contains(languageTag) == false) return@synchronized false
            check(operationId !in operations) { "Duplicate Moonshine TTS client operation" }
            operations[operationId] = ClientOperation(languageTag, job)
            true
        }

    /** Compatibility overload for registry-only tests and whole-provider release. */
    fun register(operationId: Long, job: Job): Boolean = register(
        operationId = operationId,
        languageTag = UNRESTRICTED_TEST_LANGUAGE,
        job = job,
    )

    fun finish(operationId: Long, job: Job) {
        synchronized(stateLock) {
            if (operations[operationId]?.job === job) operations.remove(operationId)
        }
    }

    /**
     * Atomically publishes the new session's language boundary and cancels only removed-language
     * client work. Selected siblings remain accepted while retirement is in progress.
     */
    suspend fun selectLanguagesAndCancelUnselected(
        languageTags: Set<String>,
        timeoutMillis: Long,
    ): MoonshineTtsLanguageSelectionResult {
        require(languageTags.size <= MAX_SELECTED_LANGUAGES)
        require(languageTags.none(String::isBlank))
        require(timeoutMillis > 0L)
        val removedOperations = synchronized(stateLock) {
            selectedLanguageTags = languageTags.toSet()
            operations.values
                .filter { it.languageTag !in languageTags }
                .toList()
        }
        removedOperations.forEach { operation ->
            operation.job.cancel(
                CancellationException("Moonshine TTS language was removed from the session"),
            )
        }
        val drained = withTimeoutOrNull(timeoutMillis) {
            removedOperations.map(ClientOperation::job).joinAll()
            true
        } == true
        return MoonshineTtsLanguageSelectionResult(
            drained = drained,
            pendingLanguageTags = removedOperations
                .filterNot { it.job.isCompleted }
                .mapTo(linkedSetOf(), ClientOperation::languageTag),
        )
    }

    fun clearLanguageSelection() {
        synchronized(stateLock) {
            selectedLanguageTags = null
        }
    }

    internal fun selectedLanguagesForTest(): Set<String>? = synchronized(stateLock) {
        selectedLanguageTags?.toSet()
    }

    fun acceptsLanguage(languageTag: String): Boolean = synchronized(stateLock) {
        selectedLanguageTags?.contains(languageTag) != false
    }

    suspend fun pauseAndCancel(timeoutMillis: Long): Boolean {
        require(timeoutMillis > 0L)
        // Registration and the paused snapshot are one state transition. A register that observed
        // the previous epoch can no longer land after an empty snapshot and then be accepted merely
        // because resume() already reopened the next epoch.
        val active = synchronized(stateLock) {
            accepting = false
            operations.values.map(ClientOperation::job)
        }
        active.forEach { job ->
            job.cancel(CancellationException("Moonshine TTS resources are being released"))
        }
        return withTimeoutOrNull(timeoutMillis) {
            active.joinAll()
            true
        } == true
    }

    fun resume() {
        synchronized(stateLock) {
            if (!closed) accepting = true
        }
    }

    fun closeAndCancel() {
        val active = synchronized(stateLock) {
            closed = true
            accepting = false
            operations.values.map(ClientOperation::job)
        }
        active.forEach { job ->
            job.cancel(CancellationException("Moonshine TTS provider is closed"))
        }
    }

    private data class ClientOperation(
        val languageTag: String,
        val job: Job,
    )

    private companion object {
        const val MAX_SELECTED_LANGUAGES = MAX_MOONSHINE_BROADCAST_LANGUAGES
        const val UNRESTRICTED_TEST_LANGUAGE = "<unrestricted>"
    }
}

internal interface MoonshineTtsClientCallback {
    fun onProgress(
        currentFile: String?,
        fileIndex: Int,
        fileCount: Int,
        bytesRead: Long,
        totalBytes: Long,
    ) = Unit

    fun onPrepared()
    fun onSynthesized(path: String)
    fun onError(message: String)
    /** Preserves a local Binder/native failure type when no AIDL serialization is involved. */
    fun onFailure(error: Throwable) = onError(error.message ?: error.javaClass.simpleName)
    /** Sent only after the worker removed the request from its registry and language queue. */
    fun onFinished()
}

/**
 * Suspends on an asynchronous Binder callback. Cancellation completes locally first, then sends a
 * best-effort remote cancel; a native call already executing in the worker may still finish later.
 */
internal suspend fun awaitMoonshineTtsRequest(
    start: (MoonshineTtsClientCallback) -> Unit,
    cancel: () -> Unit,
    onProgress: (
        currentFile: String?,
        fileIndex: Int,
        fileCount: Int,
        bytesRead: Long,
        totalBytes: Long,
    ) -> Unit = { _, _, _, _, _ -> },
    discardLateSynthesis: (String) -> Unit = {},
): MoonshineTtsClientResult = suspendCancellableCoroutine { continuation ->
    val terminal = AtomicBoolean(false)
    val startReturned = AtomicBoolean(false)
    val cancellationRequested = AtomicBoolean(false)
    val workerFinished = AtomicBoolean(false)
    val outcome = AtomicReference<MoonshineTtsClientOutcome?>(null)

    fun discardSynthesis(result: MoonshineTtsClientOutcome?) {
        val synthesized = (result as? MoonshineTtsClientOutcome.Success)?.result
            as? MoonshineTtsClientResult.Synthesized
        if (synthesized != null) discardLateSynthesis(synthesized.path)
    }

    fun deliverIfWorkerFinished() {
        if (!workerFinished.get()) return
        val completed = outcome.get() ?: return
        if (!terminal.compareAndSet(false, true)) return
        when (completed) {
            is MoonshineTtsClientOutcome.Success -> continuation.resume(completed.result) {
                    _, cancelledResult, _ ->
                if (cancelledResult is MoonshineTtsClientResult.Synthesized) {
                    discardLateSynthesis(cancelledResult.path)
                }
            }

            is MoonshineTtsClientOutcome.Failure ->
                continuation.resumeWithException(completed.error)
        }
    }

    fun accept(completed: MoonshineTtsClientOutcome) {
        if (!outcome.compareAndSet(null, completed)) {
            discardSynthesis(completed)
            return
        }
        if (terminal.get()) {
            discardSynthesis(completed)
        } else {
            deliverIfWorkerFinished()
        }
    }

    val callback = object : MoonshineTtsClientCallback {
        override fun onProgress(
            currentFile: String?,
            fileIndex: Int,
            fileCount: Int,
            bytesRead: Long,
            totalBytes: Long,
        ) {
            if (!terminal.get()) {
                onProgress(currentFile, fileIndex, fileCount, bytesRead, totalBytes)
            }
        }

        override fun onPrepared() = accept(
            MoonshineTtsClientOutcome.Success(MoonshineTtsClientResult.Prepared),
        )

        override fun onSynthesized(path: String) = accept(
            MoonshineTtsClientOutcome.Success(MoonshineTtsClientResult.Synthesized(path)),
        )

        override fun onError(message: String) = accept(
            MoonshineTtsClientOutcome.Failure(
                IllegalStateException(
                    message.ifBlank { "Moonshine TTS worker request failed" },
                ),
            ),
        )

        override fun onFailure(error: Throwable) = accept(
            MoonshineTtsClientOutcome.Failure(error),
        )

        override fun onFinished() {
            workerFinished.set(true)
            deliverIfWorkerFinished()
        }
    }

    continuation.invokeOnCancellation {
        if (terminal.compareAndSet(false, true)) {
            discardSynthesis(outcome.get())
            cancellationRequested.set(true)
            if (startReturned.get()) runCatching(cancel)
        }
    }

    if (continuation.isActive && !terminal.get()) {
        try {
            start(callback)
            startReturned.set(true)
            // Cancellation can win while the one-way transaction is being submitted.
            if (cancellationRequested.get()) runCatching(cancel)
        } catch (error: Throwable) {
            startReturned.set(true)
            if (cancellationRequested.get()) runCatching(cancel)
            accept(MoonshineTtsClientOutcome.Failure(error))
            // A local Binder submission error has no remote cleanup callback to await.
            workerFinished.set(true)
            deliverIfWorkerFinished()
        }
    }
}

private sealed interface MoonshineTtsClientOutcome {
    data class Success(val result: MoonshineTtsClientResult) : MoonshineTtsClientOutcome
    data class Failure(val error: Throwable) : MoonshineTtsClientOutcome
}

internal interface MoonshineTtsServiceCallback {
    fun onProgress(
        currentFile: String,
        fileIndex: Int,
        fileCount: Int,
        bytesRead: Long,
        totalBytes: Long,
    ) = Unit

    fun onSuccess(result: String?)
    fun onPcmChunk(pcm: ByteArray) = Unit
    fun onError(message: String)
    fun onDiscardedResult(result: String?) = Unit
    fun onFinished()
}

internal data class MoonshineTtsRequestKey(
    val clientId: String,
    val requestId: Long,
)

/**
 * Bounded worker-side dispatch with a small waiting room. Requests for the same language are
 * ordered without occupying a native execution lane. Each service process owns one language, so
 * a cancelled native call can finish without blocking any sibling language process. The next
 * same-language request starts only after the previous call has really returned; cancellation
 * never pretends that native cleanup completed.
 */
internal class MoonshineTtsAsyncRequestDispatcher(
    private val scope: CoroutineScope,
    maxConcurrentOperations: Int,
    maxPendingOperations: Int = DEFAULT_MAX_PENDING_OPERATIONS,
    private val beforeRegistrationForTest: (() -> Unit)? = null,
) {
    private sealed interface OperationOutcome {
        data class Success(val result: String?) : OperationOutcome
        data class Failure(val error: Throwable) : OperationOutcome
        data object Cancelled : OperationOutcome
    }

    private class ActiveOperation(
        val requestKey: MoonshineTtsRequestKey,
        val languageKey: String,
        val predecessor: ActiveOperation?,
    ) {
        val cancelled = AtomicBoolean(false)
        val finished = CompletableDeferred<Unit>()
        val job = AtomicReference<Job?>()
        val outcome = AtomicReference<OperationOutcome>(OperationOutcome.Cancelled)
    }

    private val executionPermits = CoroutineSemaphore(maxConcurrentOperations)
    private val outstandingPermits = Semaphore(
        maxConcurrentOperations + maxPendingOperations,
        true,
    )
    private val operations = ConcurrentHashMap<MoonshineTtsRequestKey, ActiveOperation>()
    private val languageQueueLock = Any()
    private val languageTails = mutableMapOf<String, ActiveOperation>()
    private val closed = AtomicBoolean(false)
    private val shutdownLock = Any()
    private var shutdownAction: (() -> Unit)? = null

    init {
        require(maxConcurrentOperations > 0) { "At least one native operation lane is required" }
        require(maxPendingOperations >= 0)
    }

    /** File/preparation compatibility overload; streaming callers use the PCM-aware form. */
    fun submit(
        clientId: String,
        requestId: Long,
        key: String,
        callback: MoonshineTtsServiceCallback,
        operation: suspend () -> String?,
    ): Boolean = submit(clientId, requestId, key, callback) { _, _ -> operation() }

    fun submit(
        clientId: String,
        requestId: Long,
        key: String,
        callback: MoonshineTtsServiceCallback,
        operation: suspend (
            progress: (String, Int, Int, Long, Long) -> Unit,
            pcm: (ByteArray) -> Unit,
        ) -> String?,
    ): Boolean {
        if (!isValidClientId(clientId) || requestId <= 0L || key.isBlank()) {
            callback.reject("Invalid Moonshine TTS request")
            return false
        }
        if (closed.get() || !outstandingPermits.tryAcquire()) {
            callback.reject("Moonshine TTS worker is busy; use the offline fallback for this phrase")
            return false
        }

        val requestKey = MoonshineTtsRequestKey(clientId, requestId)
        val active = synchronized(languageQueueLock) {
            if (closed.get() || operations.containsKey(requestKey)) {
                null
            } else {
                // Deterministic regression hook; production never supplies it. shutdown() takes
                // this same lock, so it cannot close the runtime across registration.
                beforeRegistrationForTest?.invoke()
                ActiveOperation(
                    requestKey = requestKey,
                    languageKey = key,
                    predecessor = languageTails[key],
                ).also { candidate ->
                    operations[candidate.requestKey] = candidate
                    languageTails[key] = candidate
                }
            }
        }
        if (active == null) {
            outstandingPermits.release()
            callback.reject(
                if (closed.get()) {
                    "Moonshine TTS worker is shutting down"
                } else {
                    "Duplicate Moonshine TTS request"
                },
            )
            runShutdownIfIdle()
            return false
        }

        val job = scope.launch {
            try {
                active.predecessor?.finished?.await()
                if (active.cancelled.get()) return@launch
                executionPermits.withPermit {
                    if (active.cancelled.get()) return@withPermit
                    active.outcome.set(OperationOutcome.Success(
                        operation(
                            { file, index, count, read, total ->
                                if (!active.cancelled.get()) {
                                    runCatching {
                                        callback.onProgress(file, index, count, read, total)
                                    }
                                }
                            },
                            { pcm ->
                                if (!active.cancelled.get()) callback.onPcmChunk(pcm)
                            },
                        ),
                    ))
                }
            } catch (cancelled: CancellationException) {
                if (!active.cancelled.get()) {
                    active.outcome.set(OperationOutcome.Failure(cancelled))
                }
            } catch (error: Throwable) {
                active.outcome.set(OperationOutcome.Failure(error))
            } finally {
                finish(active, callback, active.outcome.get())
            }
        }
        active.job.set(job)
        // A dispatched coroutine can be cancelled before its body enters, in which case its
        // try/finally never runs. Completion cleanup is deliberately idempotent.
        job.invokeOnCompletion { finish(active, callback, active.outcome.get()) }
        if (active.cancelled.get()) job.cancel()
        return true
    }

    fun cancel(clientId: String, requestId: Long) {
        operations[MoonshineTtsRequestKey(clientId, requestId)]?.let { active ->
            active.cancelled.set(true)
            active.job.get()?.cancel()
        }
    }

    fun shutdown(onIdle: () -> Unit) {
        val activeOperations = synchronized(languageQueueLock) {
            if (!closed.compareAndSet(false, true)) {
                null
            } else {
                synchronized(shutdownLock) {
                    shutdownAction = onIdle
                }
                operations.values.toList()
            }
        }
        if (activeOperations == null) return
        activeOperations.forEach { active ->
            active.cancelled.set(true)
            active.job.get()?.cancel()
        }
        runShutdownIfIdle()
    }

    private fun finish(
        active: ActiveOperation,
        callback: MoonshineTtsServiceCallback,
        outcome: OperationOutcome,
    ) {
        if (operations.remove(active.requestKey, active)) {
            synchronized(languageQueueLock) {
                if (languageTails[active.languageKey] === active) {
                    val livePredecessor = active.predecessor?.takeIf { predecessor ->
                        operations[predecessor.requestKey] === predecessor
                    }
                    if (livePredecessor == null) {
                        languageTails.remove(active.languageKey)
                    } else {
                        languageTails[active.languageKey] = livePredecessor
                    }
                }
            }
            outstandingPermits.release()
            // Unblock the next same-language request only after the native operation returned and
            // this request disappeared from the registry. A queued request that is cancelled
            // before its predecessor finishes must preserve that ordering for its own successor.
            val predecessorFinished = active.predecessor?.finished
            if (predecessorFinished == null || predecessorFinished.isCompleted) {
                active.finished.complete(Unit)
            } else {
                predecessorFinished.invokeOnCompletion { active.finished.complete(Unit) }
            }
            when {
                active.cancelled.get() -> {
                    val result = (outcome as? OperationOutcome.Success)?.result
                    runCatching { callback.onDiscardedResult(result) }
                }

                outcome is OperationOutcome.Success ->
                    runCatching { callback.onSuccess(outcome.result) }

                outcome is OperationOutcome.Failure -> runCatching {
                    callback.onError(
                        outcome.error.message?.take(MAX_ERROR_MESSAGE_CHARACTERS)
                            ?: outcome.error.javaClass.simpleName,
                    )
                }

                else -> runCatching {
                    callback.onError("Moonshine TTS worker request was cancelled unexpectedly")
                }
            }
            runCatching { callback.onFinished() }
        }
        runShutdownIfIdle()
    }

    private fun runShutdownIfIdle() {
        if (!closed.get() || operations.isNotEmpty()) return
        val action = synchronized(shutdownLock) {
            if (operations.isEmpty()) shutdownAction.also { shutdownAction = null } else null
        }
        action?.invoke()
    }

    private fun MoonshineTtsServiceCallback.reject(message: String) {
        runCatching { onError(message) }
        runCatching { onFinished() }
    }

    private fun isValidClientId(clientId: String): Boolean =
        clientId.length in 8..64 && clientId.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private companion object {
        const val MAX_ERROR_MESSAGE_CHARACTERS = 500
        const val DEFAULT_MAX_PENDING_OPERATIONS = 4
    }
}

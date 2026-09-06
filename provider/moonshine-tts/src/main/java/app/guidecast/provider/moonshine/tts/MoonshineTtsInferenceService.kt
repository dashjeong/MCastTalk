package app.guidecast.provider.moonshine.tts

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Non-exported Binder service hosted outside the microphone/server process. */
abstract class MoonshineTtsInferenceService : Service() {
    protected abstract val isolatedLanguageTag: String

    private enum class ResultType { PREPARED, FILE, STREAM }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Default)
    private val runtimeCloseGate = MoonshineTtsRuntimeCloseGate()
    private val runtimeDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MoonshineTtsInferenceRuntime(this, isolatedLanguageTag)
    }
    private val runtime by runtimeDelegate
    private lateinit var requests: MoonshineTtsAsyncRequestDispatcher
    private lateinit var processGeneration: MoonshineTtsServiceGenerationGate.Generation
    private val shutdownStateLock = Any()
    private var shutdownStarted = false
    private var shutdownCompleted = false
    private var shutdownError: String? = null
    private val shutdownCallbacks = mutableListOf<IGuideCastMoonshineTtsShutdownCallback>()

    private val binder = object : IGuideCastMoonshineTts.Stub() {
        override fun prepareAssets(
            clientId: String,
            requestId: Long,
            languageTag: String,
            callback: IGuideCastMoonshineTtsCallback?,
        ) {
            if (callback == null) return
            dispatch(
                clientId = clientId,
                requestId = requestId,
                languageTag = languageTag,
                callback = callback,
                resultType = ResultType.PREPARED,
            ) { progress, _ ->
                runtime.prepareAssets(languageTag, progress)
                null
            }
        }

        override fun prepare(
            clientId: String,
            requestId: Long,
            languageTag: String,
            callback: IGuideCastMoonshineTtsCallback?,
        ) {
            if (callback == null) return
            dispatch(
                clientId = clientId,
                requestId = requestId,
                languageTag = languageTag,
                callback = callback,
                resultType = ResultType.PREPARED,
            ) { progress, _ ->
                runtime.prepare(languageTag, progress)
                null
            }
        }

        override fun synthesizeToFile(
            clientId: String,
            requestId: Long,
            text: String,
            languageTag: String,
            callback: IGuideCastMoonshineTtsCallback?,
        ) {
            if (callback == null) return
            dispatch(
                clientId = clientId,
                requestId = requestId,
                languageTag = languageTag,
                callback = callback,
                resultType = ResultType.FILE,
            ) { _, _ ->
                runtime.synthesizeToFile(text, languageTag)
            }

        }

        override fun synthesizeStreaming(
            clientId: String,
            requestId: Long,
            text: String,
            languageTag: String,
            callback: IGuideCastMoonshineTtsCallback?,
        ) {
            if (callback == null) return
            dispatch(
                clientId = clientId,
                requestId = requestId,
                languageTag = languageTag,
                callback = callback,
                resultType = ResultType.STREAM,
            ) { progress, pcm ->
                runtime.synthesizeStreaming(text, languageTag, progress, pcm)
                null
            }
        }

        override fun cancel(clientId: String, requestId: Long) {
            requests.cancel(clientId, requestId)
        }

        override fun shutdown() {
            // Never kill this process directly. Samsung's app-stability monitor counts an
            // explicit SIGKILL from a private component as an app failure and can show the
            // user a misleading "clear the cache" crash dialog. Once the client unbinds,
            // Android is free to reclaim this cached worker normally under memory pressure.
            beginShutdown(callback = null)
        }

        override fun shutdownWhenIdle(callback: IGuideCastMoonshineTtsShutdownCallback?) {
            if (callback != null) beginShutdown(callback)
        }
    }

    override fun onCreate() {
        super.onCreate()
        processGeneration = MoonshineTtsProcessGenerationGate.beginGeneration()
        requests = MoonshineTtsAsyncRequestDispatcher(
            scope = serviceScope,
            // Each private process owns exactly one voice. Serial native access prevents two
            // utterances from corrupting the same engine while the other language processes
            // continue independently.
            maxConcurrentOperations = 1,
            maxPendingOperations = MAX_PENDING_OPERATIONS,
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        // Coroutine cancellation cannot preempt a native synthesize() already on the stack. Keep
        // the runtime alive until every bounded dispatch lane has really returned, then close it.
        beginShutdown(callback = null)
        super.onDestroy()
    }

    /**
     * A stop request is complete only after native work returned and runtime.close() finished.
     * The client intentionally keeps its binding until this callback so a replacement language
     * cannot make six native voice processes resident on a constrained device.
     */
    private fun beginShutdown(callback: IGuideCastMoonshineTtsShutdownCallback?) {
        var completedError: String? = null
        var notifyImmediately = false
        val startNow = synchronized(shutdownStateLock) {
            if (shutdownCompleted) {
                completedError = shutdownError
                notifyImmediately = callback != null
                false
            } else {
                if (callback != null) shutdownCallbacks += callback
                if (shutdownStarted) {
                    false
                } else {
                    shutdownStarted = true
                    true
                }
            }
        }
        if (callback != null && notifyImmediately) {
            notifyShutdownCallback(callback, completedError)
            return
        }
        if (startNow) requests.shutdown(::finishShutdown)
    }

    private fun finishShutdown() {
        val error = closeMoonshineTtsRuntimeWithRetry(
            maxAttempts = MAX_RUNTIME_CLOSE_ATTEMPTS,
            closeRuntime = ::closeRuntimeAfterRequests,
        )
            ?.let { it.message?.take(MAX_SHUTDOWN_ERROR_CHARACTERS) ?: it.javaClass.simpleName }
        serviceScope.cancel()
        val callbacks = synchronized(shutdownStateLock) {
            shutdownError = error
            shutdownCompleted = true
            shutdownCallbacks.toList().also { shutdownCallbacks.clear() }
        }
        callbacks.forEach { notifyShutdownCallback(it, error) }
        stopSelf()
    }

    private fun notifyShutdownCallback(
        callback: IGuideCastMoonshineTtsShutdownCallback,
        error: String?,
    ) {
        runCatching {
            if (error == null) callback.onShutdownComplete() else callback.onShutdownError(error)
        }
    }

    private fun dispatch(
        clientId: String,
        requestId: Long,
        languageTag: String,
        callback: IGuideCastMoonshineTtsCallback,
        resultType: ResultType,
        operation: suspend (
            progress: (String, Int, Int, Long, Long) -> Unit,
            pcm: (ByteArray) -> Unit,
        ) -> String?,
    ) {
        if (languageTag != isolatedLanguageTag) {
            runCatching {
                callback.onError(
                    requestId,
                    "TTS worker language mismatch: expected=$isolatedLanguageTag actual=$languageTag",
                )
            }
            runCatching { callback.onFinished(requestId) }
            return
        }
        val callbackBinder = callback.asBinder()
        val deathRecipient = IBinder.DeathRecipient { requests.cancel(clientId, requestId) }
        try {
            callbackBinder.linkToDeath(deathRecipient, 0)
        } catch (_: Throwable) {
            // No request was admitted and therefore no native operation can outlive this branch.
            // Best-effort terminal delivery also prevents a transient link failure from retaining
            // a transferred caller ticket until its timeout.
            runCatching { callback.onFinished(requestId) }
            return
        }

        val submitted = requests.submit(
            clientId = clientId,
            requestId = requestId,
            key = languageTag,
            callback = object : MoonshineTtsServiceCallback {
                override fun onProgress(
                    currentFile: String,
                    fileIndex: Int,
                    fileCount: Int,
                    bytesRead: Long,
                    totalBytes: Long,
                ) {
                    callback.onProgress(
                        requestId,
                        currentFile,
                        fileIndex,
                        fileCount,
                        bytesRead,
                        totalBytes,
                    )
                }

                override fun onSuccess(result: String?) {
                    when (resultType) {
                        ResultType.PREPARED -> callback.onPrepared(requestId)
                        ResultType.FILE -> callback.onSynthesized(
                            requestId,
                            requireNotNull(result) { "Moonshine TTS returned no output path" },
                        )
                        ResultType.STREAM -> callback.onStreamCompleted(requestId)
                    }
                }

                override fun onPcmChunk(pcm: ByteArray) {
                    callback.onPcmChunk(requestId, pcm)
                }

                override fun onError(message: String) {
                    callback.onError(requestId, message)
                }

                override fun onDiscardedResult(result: String?) {
                    if (resultType == ResultType.FILE && result != null) deleteIpcOutput(result)
                }

                override fun onFinished() {
                    runCatching { callback.onFinished(requestId) }
                    runCatching { callbackBinder.unlinkToDeath(deathRecipient, 0) }
                }
            },
            operation = { progress, pcm ->
                // A replacement Android Service can be constructed before a cancelled JNI call
                // in the old instance returns. Never initialize this generation's runtime until
                // the previous runtime has really closed.
                processGeneration.awaitPredecessorClosed()
                operation(progress, pcm)
            },
        )
        // Death can race between linkToDeath() and dispatcher registration.
        if (submitted && !callbackBinder.isBinderAlive) requests.cancel(clientId, requestId)
    }

    private fun deleteIpcOutput(path: String) {
        runCatching {
            val directory = File(
                File(cacheDir, MoonshineTtsInferenceRuntime.IPC_DIRECTORY),
                isolatedLanguageTag,
            ).canonicalFile
            val file = File(path).canonicalFile
            if (file.parentFile == directory && file.name.endsWith(".pcm")) file.delete()
        }
    }

    private fun closeRuntimeAfterRequests() {
        runtimeCloseGate.close {
            processGeneration.closeRuntimeThenMarkClosed {
                if (runtimeDelegate.isInitialized()) runtime.close()
            }
        }
    }

    private companion object {
        const val MAX_PENDING_OPERATIONS = 2
        const val MAX_SHUTDOWN_ERROR_CHARACTERS = 500
        const val MAX_RUNTIME_CLOSE_ATTEMPTS = 2
    }
}

/**
 * Commits the closed state only after the native runtime confirms its close. A thrown close remains
 * retryable by the same service shutdown, while the process-generation gate stays fail-closed.
 */
internal class MoonshineTtsRuntimeCloseGate {
    private val lock = Any()
    @Volatile private var closed = false

    fun close(operation: () -> Unit) {
        if (closed) return
        synchronized(lock) {
            if (closed) return
            operation()
            closed = true
        }
    }
}

/** A short bounded retry handles a transient close failure without acknowledging an unconfirmed close. */
internal fun closeMoonshineTtsRuntimeWithRetry(
    maxAttempts: Int,
    closeRuntime: () -> Unit,
): Throwable? {
    require(maxAttempts > 0) { "At least one runtime close attempt is required" }
    var previousFailure: Throwable? = null
    repeat(maxAttempts) {
        try {
            closeRuntime()
            return null
        } catch (error: Throwable) {
            previousFailure
                ?.takeIf { it !== error }
                ?.let { prior -> runCatching { error.addSuppressed(prior) } }
            previousFailure = error
        }
    }
    return previousFailure
}

class MoonshineTtsEnglishInferenceService : MoonshineTtsInferenceService() {
    override val isolatedLanguageTag: String = "en"
}

class MoonshineTtsJapaneseInferenceService : MoonshineTtsInferenceService() {
    override val isolatedLanguageTag: String = "ja"
}

class MoonshineTtsChineseInferenceService : MoonshineTtsInferenceService() {
    override val isolatedLanguageTag: String = "zh"
}

class MoonshineTtsDutchInferenceService : MoonshineTtsInferenceService() {
    override val isolatedLanguageTag: String = "nl"
}

class MoonshineTtsSpanishInferenceService : MoonshineTtsInferenceService() {
    override val isolatedLanguageTag: String = "es"
}

class MoonshineTtsArabicInferenceService : MoonshineTtsInferenceService() {
    override val isolatedLanguageTag: String = "ar"
}

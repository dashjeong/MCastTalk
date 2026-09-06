package app.guidecast.provider.mlkit.translation

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.google.mlkit.common.MlKit
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.Closeable
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Base implementation for seven manifest-declared services, each hosted in a distinct process.
 * A native fatal or permanently hung Google Task can therefore silence only its target language.
 */
abstract class MlKitInferenceService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nativeOperations = NativeOperationCoordinator(MAX_PENDING_REQUESTS)
    private val requests = MlKitServiceRequestRegistry<MlKitServiceRequest>(MAX_PENDING_REQUESTS)
    private val runtime = MlKitWorkerRuntime()
    private val runtimeCloseGate = MlKitRuntimeCloseGate()
    private val draining = AtomicBoolean(false)
    private lateinit var processGeneration: MlKitWorkerProcessGenerationGate.Generation

    private val binder = object : IGuideCastMlKitInference.Stub() {
        override fun translateAsync(
            requestId: Long,
            text: String?,
            sourceLanguageTag: String?,
            targetLanguageTag: String?,
            callback: IGuideCastMlKitInferenceCallback?,
        ) {
            val source = sourceLanguageTag.orEmpty()
            val target = targetLanguageTag.orEmpty()
            dispatchRequest(requestId, target, callback) { request ->
                runNative(request) { runtime.translate(text.orEmpty(), source, target) }
            }
        }

        override fun prepareAsync(
            requestId: Long,
            sourceLanguageTag: String?,
            targetLanguageTag: String?,
            requireWifi: Boolean,
            callback: IGuideCastMlKitInferenceCallback?,
        ) {
            val source = sourceLanguageTag.orEmpty()
            val target = targetLanguageTag.orEmpty()
            dispatchRequest(requestId, target, callback) { request ->
                runNative(request) {
                    runtime.prepare(source, target, requireWifi)
                    MODEL_READY_RESULT
                }
            }
        }

        override fun drainAndCloseAsync(
            requestId: Long,
            callback: IGuideCastMlKitInferenceCallback?,
        ) {
            if (requestId < 0L || callback == null) return
            if (!draining.compareAndSet(false, true)) {
                runCatching { callback.onError(requestId, "ML Kit 채널이 이미 종료 중입니다.") }
                runCatching { callback.onFinished(requestId) }
                return
            }
            // Close admission first. The coordinator owns any Google Task that already entered
            // native code and waits for its real terminal callback before runtime.close(). Only
            // then is model deletion in the client safe.
            serviceScope.launch {
                try {
                    awaitPredecessorThenDrain(processGeneration) {
                        nativeOperations.closeAndJoin()
                        runtimeCloseGate.close(runtime::close)
                    }
                    callback.onSuccess(requestId, WORKER_DRAINED_RESULT)
                } catch (error: Throwable) {
                    runCatching { callback.onError(requestId, error.safeIpcMessage()) }
                } finally {
                    // The drain itself is also a ticket-bearing request. Release its caller-side
                    // Binder/death lifetime only after all previously admitted native Tasks and
                    // the Translator runtime have reached this terminal boundary.
                    runCatching { callback.onFinished(requestId) }
                    stopSelf()
                }
            }
        }

        override fun cancel(requestId: Long) {
            requests.cancel(requestId)?.cancelFromClient()
        }

        override fun shutdown() {
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // MlKitInitProvider is created only in the default application process. Every private
        // language process must initialize its own MlKitContext before Translation.getClient().
        MlKitWorkerProcessInitializer.initialize(applicationContext)
        processGeneration = MlKitWorkerProcessGenerationGate.beginGeneration()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun dispatchRequest(
        requestId: Long,
        targetLanguageTag: String,
        callback: IGuideCastMlKitInferenceCallback?,
        operation: suspend (MlKitServiceRequest) -> String,
    ) {
        if (requestId < 0L || callback == null) return
        if (draining.get()) {
            runCatching { callback.onError(requestId, "ML Kit 채널이 종료 중입니다.") }
            runCatching { callback.onFinished(requestId) }
            return
        }
        val callbackBinder = callback.asBinder()
        lateinit var request: MlKitServiceRequest
        val job = serviceScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val result = operation(request)
                if (request.mayDeliverResult()) {
                    runCatching { callback.onSuccess(requestId, result) }
                }
            } catch (_: CancellationException) {
                // The native task remains owned by this language process until its real terminal
                // callback. Other language processes and their preparation calls keep running.
            } catch (error: Throwable) {
                if (request.mayDeliverResult()) {
                    runCatching { callback.onError(requestId, error.safeIpcMessage()) }
                }
            }
        }
        val deathRecipient = IBinder.DeathRecipient {
            requests.cancel(requestId)?.cancelFromClient()
        }
        try {
            callbackBinder.linkToDeath(deathRecipient, 0)
        } catch (_: Throwable) {
            job.cancel()
            // Registration never reached the native coordinator. Best-effort acknowledgement
            // prevents a transient link failure from retaining a transferred caller ticket.
            runCatching { callback.onFinished(requestId) }
            return
        }
        request = MlKitServiceRequest(
            work = job,
            callbackBinder = callbackBinder,
            deathRecipient = deathRecipient,
            reportServerCancellation = { message ->
                runCatching { callback.onError(requestId, message) }
            },
            reportNativeFinished = {
                runCatching { callback.onFinished(requestId) }
            },
        )
        when (requests.register(requestId, request)) {
            MlKitServiceRequestAdmission.ACCEPTED -> {
                job.invokeOnCompletion {
                    requests.complete(requestId, request)
                    request.finishClient()
                }
                job.start()
                if (!callbackBinder.isBinderAlive) {
                    requests.cancel(requestId)?.cancelFromClient()
                }
            }

            MlKitServiceRequestAdmission.OBSOLETE -> {
                request.cancelFromClient()
                runCatching { callback.onError(requestId, "취소된 이전 ML Kit 요청입니다.") }
                request.finishWithoutNativeWork()
            }

            MlKitServiceRequestAdmission.BUSY -> {
                request.cancelFromClient()
                runCatching {
                    callback.onError(
                        requestId,
                        "$targetLanguageTag ML Kit 채널 요청 대기열이 가득 찼습니다.",
                    )
                }
                request.finishWithoutNativeWork()
            }

            MlKitServiceRequestAdmission.CLOSED -> {
                request.cancelFromClient()
                runCatching { callback.onError(requestId, "ML Kit 채널이 종료 중입니다.") }
                request.finishWithoutNativeWork()
            }
        }
    }

    private suspend fun runNative(
        request: MlKitServiceRequest,
        operation: suspend () -> String,
    ): String = nativeOperations.run(onNativeFinished = request::markNativeFinished) {
        // A replacement Service may be created before an abandoned Google Task in the
        // predecessor returns. Never initialize this generation concurrently.
        processGeneration.awaitPredecessorClosed()
        if (!request.markNativeStarted()) throw CancellationException(
            "ML Kit request was cancelled before native inference",
        )
        operation()
    }

    override fun onDestroy() {
        requests.close().forEach { request ->
            request.cancelFromServer("ML Kit 언어 작업 공간이 종료되었습니다.")
        }
        // closeAndJoin runs outside a caller deadline. It never closes Translator under a Google
        // Task that survived coroutine cancellation, and the successor awaits markClosed().
        serviceScope.launch {
            try {
                nativeOperations.closeAndJoin()
                processGeneration.closeRuntimeThenMarkClosed {
                    runtimeCloseGate.close(runtime::close)
                }
            } catch (error: Throwable) {
                // Do not let a failed Translator.close() crash the private worker or release a
                // replacement onto native state whose shutdown was never confirmed. A later
                // close attempt may retry; otherwise Android process reclamation is the boundary.
                Log.e(LOG_TAG, "ML Kit native runtime close was not confirmed", error)
            } finally {
                serviceScope.cancel()
            }
        }
        super.onDestroy()
    }

    private companion object {
        const val LOG_TAG = "GuideCastMlKitService"
        const val MAX_PENDING_REQUESTS = 8
        const val MODEL_READY_RESULT = "guidecast-model-ready"
        const val WORKER_DRAINED_RESULT = "guidecast-worker-drained"
    }
}

class MlKitInferenceService0 : MlKitInferenceService()
class MlKitInferenceService1 : MlKitInferenceService()
class MlKitInferenceService2 : MlKitInferenceService()
class MlKitInferenceService3 : MlKitInferenceService()
class MlKitInferenceService4 : MlKitInferenceService()
class MlKitInferenceService5 : MlKitInferenceService()
class MlKitInferenceService6 : MlKitInferenceService()

/** A drain acknowledgement is unsafe until the previous Service generation closed its runtime. */
internal suspend fun awaitPredecessorThenDrain(
    generation: MlKitWorkerProcessGenerationGate.Generation,
    drain: suspend () -> Unit,
) {
    generation.awaitPredecessorClosed()
    drain()
}

/** Process-local because every `:mlkit_translate_*` VM has its own static object graph. */
private object MlKitWorkerProcessInitializer {
    private val gate = MlKitProcessInitializationGate()

    fun initialize(context: android.content.Context) {
        gate.initialize { MlKit.initialize(context.applicationContext) }
    }
}

internal class MlKitProcessInitializationGate {
    private val lock = Any()
    @Volatile private var initialized = false

    fun initialize(operation: () -> Unit) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            operation()
            initialized = true
        }
    }
}

/** A drain acknowledgement and Android onDestroy may race after the same native task returns. */
internal class MlKitRuntimeCloseGate {
    private val lock = Any()
    @Volatile private var closed = false

    fun close(operation: () -> Unit) {
        if (closed) return
        synchronized(lock) {
            if (closed) return
            // Commit the closed state only after the native operation returns successfully. If it
            // throws, another lifecycle path is allowed to retry instead of treating uncertain
            // native ownership as safely released.
            operation()
            closed = true
        }
    }
}

private class MlKitWorkerRuntime : Closeable {
    private var sourceLanguage: String? = null
    private var targetLanguage: String? = null
    private var translator: Translator? = null

    suspend fun translate(text: String, sourceTag: String, targetTag: String): String {
        require(text.isNotBlank() && text.length <= MAX_SOURCE_CHARACTERS) {
            "ML Kit source text is outside the supported size"
        }
        val active = translator(sourceTag, targetTag)
        return active.translate(text).awaitResult()
    }

    suspend fun prepare(sourceTag: String, targetTag: String, requireWifi: Boolean) {
        val conditions = DownloadConditions.Builder().apply {
            if (requireWifi) requireWifi()
        }.build()
        val active = translator(sourceTag, targetTag)
        active.downloadModelIfNeeded(conditions).awaitCompletion()
        // ML Kit 17.0.3 does not enter TranslateJni on downloadModelIfNeeded(). A real short
        // translation is the native mmap/warm boundary that the process-wide ticket must cover.
        val warmSource = mlKitWarmupSourceText(sourceTag)
        check(active.translate(warmSource).awaitResult().isNotBlank()) {
            "ML Kit native warm-up returned an empty translation"
        }
    }

    private fun translator(sourceTag: String, targetTag: String): Translator {
        val source = sourceTag.toMlKitLanguage()
        val target = targetTag.toMlKitLanguage()
        val active = translator ?: Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build(),
        ).also {
            sourceLanguage = source
            targetLanguage = target
            translator = it
        }
        check(sourceLanguage == source && targetLanguage == target) {
            "An ML Kit process cannot be reassigned while its channel is active"
        }
        return active
    }

    override fun close() {
        translator?.close()
        translator = null
        sourceLanguage = null
        targetLanguage = null
    }

    private companion object {
        const val MAX_SOURCE_CHARACTERS = 2_000
    }
}

internal fun mlKitWarmupSourceText(sourceLanguageTag: String): String = requireNotNull(
    mapOf(
        "ar" to "مرحبا",
        "en" to "Hello",
        "es" to "Hola",
        "ja" to "こんにちは",
        "ko" to "안녕하세요",
        "zh" to "你好",
    )[sourceLanguageTag.toMlKitLanguage()],
) { "ML Kit warm-up source is unavailable for $sourceLanguageTag" }

private class MlKitServiceRequest(
    private val work: Job,
    private val callbackBinder: IBinder,
    private val deathRecipient: IBinder.DeathRecipient,
    private val reportServerCancellation: (String) -> Unit,
    private val reportNativeFinished: () -> Unit,
) {
    private val acceptingResult = AtomicBoolean(true)
    private val clientFinished = AtomicBoolean(false)
    private val nativeFinished = AtomicBoolean(false)
    private val binderUnlinked = AtomicBoolean(false)
    private val nativeCancellation = MlKitNativeRequestCancellationGate()

    fun mayDeliverResult(): Boolean = acceptingResult.compareAndSet(true, false)

    fun markNativeStarted(): Boolean = nativeCancellation.markNativeStarted()

    fun markNativeFinished() {
        nativeCancellation.markNativeFinished()
        if (nativeFinished.compareAndSet(false, true)) reportNativeFinished()
        unlinkIfCompletelyFinished()
    }

    fun cancelFromClient() {
        acceptingResult.set(false)
        work.cancel()
        nativeCancellation.cancel()
    }

    fun cancelFromServer(message: String) {
        if (acceptingResult.compareAndSet(true, false)) reportServerCancellation(message)
        work.cancel()
        nativeCancellation.cancel()
    }

    fun finishClient() {
        clientFinished.set(true)
        if (nativeCancellation.canFinishWithoutNativeAcknowledgement()) markNativeFinished()
        unlinkIfCompletelyFinished()
    }

    fun finishWithoutNativeWork() {
        clientFinished.set(true)
        markNativeFinished()
    }

    private fun unlinkIfCompletelyFinished() {
        if (clientFinished.get() && nativeFinished.get() &&
            binderUnlinked.compareAndSet(false, true)
        ) {
            runCatching { callbackBinder.unlinkToDeath(deathRecipient, 0) }
        }
    }
}

/**
 * Prevents a request cancelled before native entry from starting later.
 *
 * A Google Task already inside JNI is deliberately allowed to reach its real terminal state in
 * this language-only process. GuideCast never sends SIGKILL to one of its own Android processes:
 * doing so is reported as an app crash by Samsung stability tooling. While that task remains
 * stuck, only this language's bounded queue degrades; sibling language processes keep running.
 */
internal class MlKitNativeRequestCancellationGate {
    private val state = java.util.concurrent.atomic.AtomicReference(
        MlKitNativeRequestState.WAITING,
    )

    fun markNativeStarted(): Boolean = state.compareAndSet(
        MlKitNativeRequestState.WAITING,
        MlKitNativeRequestState.RUNNING,
    )

    fun markNativeFinished() {
        while (true) {
            val current = state.get()
            if (current == MlKitNativeRequestState.FINISHED) return
            if (state.compareAndSet(current, MlKitNativeRequestState.FINISHED)) return
        }
    }

    fun canFinishWithoutNativeAcknowledgement(): Boolean = when (state.get()) {
        MlKitNativeRequestState.WAITING,
        MlKitNativeRequestState.CANCELLED_BEFORE_NATIVE,
        MlKitNativeRequestState.FINISHED,
        -> true

        MlKitNativeRequestState.RUNNING,
        MlKitNativeRequestState.CANCELLED_DURING_NATIVE,
        -> false
    }

    fun cancel() {
        while (true) {
            val current = state.get()
            if (current == MlKitNativeRequestState.CANCELLED_BEFORE_NATIVE ||
                current == MlKitNativeRequestState.CANCELLED_DURING_NATIVE ||
                current == MlKitNativeRequestState.FINISHED
            ) {
                return
            }
            val cancelled = if (current == MlKitNativeRequestState.RUNNING) {
                MlKitNativeRequestState.CANCELLED_DURING_NATIVE
            } else {
                MlKitNativeRequestState.CANCELLED_BEFORE_NATIVE
            }
            if (state.compareAndSet(current, cancelled)) {
                return
            }
        }
    }
}

private enum class MlKitNativeRequestState {
    WAITING,
    RUNNING,
    FINISHED,
    CANCELLED_BEFORE_NATIVE,
    CANCELLED_DURING_NATIVE,
}

/** Bounded request ownership including cancellation that overtakes a one-way request. */
internal class MlKitServiceRequestRegistry<R : Any>(
    private val maximumPendingRequests: Int,
    private val maximumEarlyCancellations: Int = 32,
) {
    private val lock = Any()
    private val requests = mutableMapOf<Long, R>()
    private val cancelledBeforeArrival = LinkedHashSet<Long>()
    private var accepting = true

    init {
        require(maximumPendingRequests > 0)
        require(maximumEarlyCancellations > 0)
    }

    fun register(requestId: Long, request: R): MlKitServiceRequestAdmission = synchronized(lock) {
        when {
            !accepting -> MlKitServiceRequestAdmission.CLOSED
            requestId < 0L || cancelledBeforeArrival.remove(requestId) ->
                MlKitServiceRequestAdmission.OBSOLETE
            requests.containsKey(requestId) || requests.size >= maximumPendingRequests ->
                MlKitServiceRequestAdmission.BUSY
            else -> {
                requests[requestId] = request
                MlKitServiceRequestAdmission.ACCEPTED
            }
        }
    }

    fun cancel(requestId: Long): R? = synchronized(lock) {
        requests[requestId] ?: run {
            if (accepting && requestId >= 0L) {
                cancelledBeforeArrival += requestId
                while (cancelledBeforeArrival.size > maximumEarlyCancellations) {
                    val oldest = cancelledBeforeArrival.iterator().next()
                    cancelledBeforeArrival.remove(oldest)
                }
            }
            null
        }
    }

    fun complete(requestId: Long, request: R): Boolean = synchronized(lock) {
        if (requests[requestId] !== request) false else {
            requests.remove(requestId)
            true
        }
    }

    fun close(): List<R> = synchronized(lock) {
        accepting = false
        cancelledBeforeArrival.clear()
        requests.values.toList().also { requests.clear() }
    }

    internal fun pendingCount(): Int = synchronized(lock) { requests.size }
}

internal enum class MlKitServiceRequestAdmission {
    ACCEPTED,
    OBSOLETE,
    BUSY,
    CLOSED,
}

private fun Throwable.safeIpcMessage(): String =
    (message?.replace(Regex("\\s+"), " ")?.trim()?.takeIf(String::isNotEmpty)
        ?: javaClass.simpleName).take(500)

package app.guidecast.provider.gemma.translation

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Non-exported binder service hosted in the dedicated LiteRT-LM process. */
class GemmaInferenceService : Service() {
    private val inferenceDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GuideCast-Gemma").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val inferenceScope = CoroutineScope(SupervisorJob() + inferenceDispatcher)
    private val requests = GemmaRequestRegistry<GemmaCancellableRequest<Job>>(MAX_PENDING_REQUESTS)
    private val runtimeCloseStarted = AtomicBoolean(false)
    private val runtimeDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GemmaInferenceRuntime(this)
    }
    private val runtime by runtimeDelegate
    private lateinit var processGeneration: GemmaServiceGenerationGate.Generation

    private val binder = object : IGuideCastGemmaInference.Stub() {
        override fun translateAsync(
            requestId: Long,
            modelVariantId: String,
            text: String,
            contextBefore: String,
            sourceLanguageTag: String,
            targetLanguageTag: String,
            glossaryHints: String,
            reviewDraft: String,
            callback: IGuideCastGemmaInferenceCallback?,
        ) {
            if (requestId < 0L || callback == null) return
            val terminalSignal = GemmaNativeTerminalSignal {
                runCatching { callback.onFinished(requestId) }
            }
            val job = inferenceScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    // Android can construct this replacement service before the previous service's
                    // cancelled JNI call returns. Never load a second E2B runtime in that window.
                    processGeneration.awaitPredecessorClosed()
                    val translated = runtime.translate(
                        GemmaCatalogStore(this@GemmaInferenceService).resolve(modelVariantId),
                        text,
                        contextBefore,
                        sourceLanguageTag,
                        targetLanguageTag,
                        glossaryHints,
                        reviewDraft,
                    )
                    currentCoroutineContext().ensureActive()
                    runCatching { callback.onSuccess(requestId, translated) }
                } catch (_: CancellationException) {
                    // The client already resumed through cancellation. A late native completion
                    // is deliberately discarded rather than replayed.
                } catch (error: Throwable) {
                    runCatching {
                        callback.onError(requestId, error.safeIpcMessage())
                    }
                }
            }
            val request = GemmaCancellableRequest(
                work = job,
                cancelWork = Job::cancel,
                reportServerCancellation = { message ->
                    runCatching { callback.onError(requestId, message) }
                    Unit
                },
            )
            when (requests.register(requestId, request)) {
                GemmaRequestAdmission.ACCEPTED -> {
                    // Job completion is the only boundary that covers both cancellation before
                    // a lazy coroutine enters its body and return from an already-running blocking
                    // LiteRT call. The client therefore releases process-wide cold-load admission
                    // only after this terminal acknowledgement, never from caller cancellation.
                    job.invokeOnCompletion {
                        requests.complete(requestId, request)
                        terminalSignal.reportOnce()
                    }
                    job.start()
                }
                GemmaRequestAdmission.OBSOLETE -> {
                    job.cancel()
                    runCatching {
                        callback.onError(requestId, "취소된 이전 Gemma 요청입니다.")
                    }
                    terminalSignal.reportOnce()
                }
                GemmaRequestAdmission.BUSY -> {
                    job.cancel()
                    runCatching {
                        callback.onError(
                            requestId,
                            "Gemma 실시간 요청이 밀려 경량 오프라인 번역으로 전환합니다.",
                        )
                    }
                    terminalSignal.reportOnce()
                }
                GemmaRequestAdmission.CLOSED -> {
                    job.cancel()
                    runCatching {
                        callback.onError(requestId, "Gemma 추론 작업 공간이 종료 중입니다.")
                    }
                    terminalSignal.reportOnce()
                }
            }
        }

        override fun cancel(requestId: Long) {
            // Cancellation is cooperative inside LiteRT-LM. If native code is already executing,
            // it may finish later, but its callback is suppressed and no client Binder thread is
            // held waiting for it.
            requests.cancel(requestId)?.cancelFromClient()
        }

        override fun resetEngine(cancelThroughRequestId: Long) {
            val jobs = requests.resetThrough(cancelThroughRequestId) ?: return
            jobs.forEach {
                it.cancelFromServer("Gemma 추론 작업 공간을 재설정해 현재 요청을 종료했습니다.")
            }
            // Native LiteRT may ignore coroutine cancellation. Queue close behind the active call
            // in this one worker and let Android reclaim the unbound process naturally. A newly
            // constructed service generation cannot enter its runtime until this one closes.
            inferenceScope.launch {
                if (runtimeDelegate.isInitialized()) runtime.resetEngine()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        processGeneration = GemmaProcessGenerationGate.beginGeneration()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        requests.close().forEach {
            it.cancelFromServer("Gemma 추론 작업 공간이 종료되어 현재 요청을 끝냈습니다.")
        }
        closeRuntimeAfterRequests()
        super.onDestroy()
    }

    private fun closeRuntimeAfterRequests() {
        if (!runtimeCloseStarted.compareAndSet(false, true)) return
        inferenceScope.launch {
            var nativeCloseConfirmed = !runtimeDelegate.isInitialized()
            try {
                if (runtimeDelegate.isInitialized()) {
                    // resetEngine takes the runtime mutex held across LiteRT inference. Unlike a
                    // direct close, this cannot destroy Engine while cancelled JNI is still live.
                    runtime.resetEngine()
                    nativeCloseConfirmed = true
                }
            } catch (error: Throwable) {
                // Do not turn a cleanup failure into the Samsung app-crash/cache dialog. The
                // unresolved generation keeps replacement services away from uncertain native
                // state until Android naturally reclaims this private worker process.
                Log.e(LOG_TAG, "Gemma native runtime close was not confirmed", error)
            } finally {
                // Never equate a returned-but-failed close with release of multi-gigabyte native
                // state. A new process gets a fresh gate; this same process remains fail-closed.
                if (nativeCloseConfirmed) processGeneration.confirmClosed()
                inferenceScope.cancel()
                inferenceDispatcher.close()
            }
        }
    }

    private companion object {
        const val LOG_TAG = "GuideCastGemmaService"
        const val MAX_PENDING_REQUESTS = 4
    }
}

/** Exactly-once worker terminal acknowledgement, independent of callback/result ordering. */
internal class GemmaNativeTerminalSignal(
    private val report: () -> Unit,
) {
    private val reported = AtomicBoolean(false)

    fun reportOnce() {
        if (reported.compareAndSet(false, true)) report()
    }
}

private fun Throwable.safeIpcMessage(): String =
    (message?.takeIf(String::isNotBlank) ?: javaClass.simpleName).take(500)

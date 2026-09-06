package app.guidecast.provider.gemma.translation

import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Callback surface kept independent of LiteRT-LM so its lifecycle can be regression-tested. */
internal interface GemmaNativeTerminalCallback {
    fun onChunk(chunk: String)

    fun onChunkFailure(error: Throwable)

    fun onDone()

    fun onError(error: Throwable)
}

/**
 * Waits for LiteRT-LM's actual terminal callback before allowing native owners to be released.
 *
 * A complete JSON value is remembered but is not itself a native terminal event. Cancellation is
 * therefore surfaced only after [GemmaNativeTerminalCallback.onDone] or `onError`, so the caller's
 * conversation `use` block and inference mutex remain owned while JNI may still be decoding.
 *
 * LiteRT-LM 0.16.1 does not document that a synchronous submission throw proves no JNI work was
 * accepted. Such a throw is recorded, but deliberately does not complete this bridge; the isolated
 * worker deadline remains responsible for reclaiming that ambiguous state if no callback follows.
 */
internal suspend fun awaitGemmaNativeTerminal(
    start: (GemmaNativeTerminalCallback) -> Unit,
    mergeChunk: (StringBuilder, String) -> Unit,
    completeTranslation: (CharSequence) -> String?,
    parseTerminal: (String) -> String,
): String {
    val callerJob = kotlinx.coroutines.currentCoroutineContext()[Job]
    callerJob?.ensureActive()
    val terminalResult = withContext(NonCancellable) {
        try {
            Result.success(
                suspendCoroutine { continuation ->
                    val callback = GemmaNativeTerminalCollector(
                        continuation = continuation,
                        mergeChunk = mergeChunk,
                        completeTranslation = completeTranslation,
                        parseTerminal = parseTerminal,
                    )
                    try {
                        start(callback)
                    } catch (submissionFailure: Throwable) {
                        callback.onSubmissionFailure(submissionFailure)
                    }
                },
            )
        } catch (terminalFailure: Throwable) {
            Result.failure(terminalFailure)
        }
    }
    // Cancellation wins only after the native terminal, including when that terminal is an error;
    // this prevents a cancelled GPU request from entering the runtime's CPU retry path.
    callerJob?.ensureActive()
    return terminalResult.getOrThrow()
}

private class GemmaNativeTerminalCollector(
    private val continuation: Continuation<String>,
    private val mergeChunk: (StringBuilder, String) -> Unit,
    private val completeTranslation: (CharSequence) -> String?,
    private val parseTerminal: (String) -> String,
) : GemmaNativeTerminalCallback {
    private val lock = Any()
    private val output = StringBuilder()
    private var firstCompletedTranslation: String? = null
    private var processingFailure: Throwable? = null
    private var submissionFailure: Throwable? = null
    private var terminal = false

    override fun onChunk(chunk: String) {
        synchronized(lock) {
            if (terminal || processingFailure != null) return
            try {
                mergeChunk(output, chunk)
                if (firstCompletedTranslation == null) {
                    firstCompletedTranslation = completeTranslation(output)
                }
            } catch (error: Throwable) {
                // Never unwind through a JNI callback. Drain to native terminal, then fail.
                processingFailure = error
            }
        }
    }

    override fun onChunkFailure(error: Throwable) {
        synchronized(lock) {
            if (!terminal && processingFailure == null) processingFailure = error
        }
    }

    override fun onDone() {
        val terminalResult = synchronized(lock) {
            if (terminal) return
            terminal = true
            processingFailure?.let { return@synchronized Result.failure(it) }
            submissionFailure?.let { return@synchronized Result.failure(it) }
            runCatching {
                firstCompletedTranslation ?: parseTerminal(output.toString())
            }
        }
        continuation.resumeWith(terminalResult)
    }

    override fun onError(error: Throwable) {
        val shouldResume = synchronized(lock) {
            if (terminal) false else {
                terminal = true
                true
            }
        }
        if (shouldResume) continuation.resumeWith(Result.failure(error))
    }

    fun onSubmissionFailure(error: Throwable) {
        synchronized(lock) {
            if (!terminal && submissionFailure == null) submissionFailure = error
        }
    }
}

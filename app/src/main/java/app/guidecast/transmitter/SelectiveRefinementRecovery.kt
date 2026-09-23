package app.guidecast.transmitter

import app.guidecast.core.translation.SelectiveRefinementOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal enum class SelectiveRefinementRecoveryResult { RECOVERED, FAILED, TIMED_OUT }

/**
 * One optional background preparation attempt per translation session. A slow reviewer must not
 * cause an endless timeout / model preparation loop or hold the current sentence's draft.
 * Readiness is supplied by the provider; this coordinator never marks an engine prepared itself.
 */
internal class SelectiveRefinementRecovery(
    private val scope: CoroutineScope,
    private val canRecover: () -> Boolean,
    private val isPrepared: () -> Boolean,
    private val recover: suspend () -> Unit,
    private val onResult: (SelectiveRefinementRecoveryResult) -> Unit = {},
    private val maximumRecoveryMillis: Long = 120_000L,
    private val cleanupLatestFailure: suspend () -> Boolean = { false },
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var observedFailure = false
    private var attempted = false
    private var recoveryJob: Job? = null

    init {
        require(maximumRecoveryMillis > 0)
    }

    fun onDiagnostic(outcome: SelectiveRefinementOutcome) {
        synchronized(lock) {
            if (closed || attempted || recoveryJob != null) return
            when (outcome) {
                SelectiveRefinementOutcome.REVIEW_TIMED_OUT,
                SelectiveRefinementOutcome.REVIEW_FAILED -> observedFailure = true
                SelectiveRefinementOutcome.REVIEW_UNAVAILABLE -> Unit
                else -> return
            }
            if (!observedFailure) return
        }
        // Do not call session/provider callbacks while holding our lock: session release closes
        // this coordinator under the service's resource lock. Prepared queue-wait timeouts need
        // no recovery. If cancellation invalidates readiness later, UNAVAILABLE checks again.
        if (!canRecover() || isPrepared()) return
        val job = synchronized(lock) {
            if (closed || attempted || recoveryJob != null) return
            scope.launch(start = CoroutineStart.LAZY) {
                if (!canRecover() || isPrepared()) return@launch
                synchronized(lock) {
                    if (closed) return@launch
                    // Reserving a job coalesces callers, but does not spend the attempt. Another
                    // request may have prepared the worker before this coroutine starts.
                    attempted = true
                }
                val result = try {
                    val completed = withTimeoutOrNull(maximumRecoveryMillis) {
                        // Cancellation is not necessarily in a provider's failure ledger. False
                        // means no failed generation was reset, and must not suppress warmup.
                        cleanupLatestFailure()
                        currentCoroutineContext().ensureActive()
                        if (!canRecover()) throw CancellationException("Refinement recovery is no longer allowed")
                        recover()
                        true
                    }
                    currentCoroutineContext().ensureActive()
                    when {
                        completed == null -> SelectiveRefinementRecoveryResult.TIMED_OUT
                        isPrepared() -> SelectiveRefinementRecoveryResult.RECOVERED
                        else -> SelectiveRefinementRecoveryResult.FAILED
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    SelectiveRefinementRecoveryResult.FAILED
                }
                currentCoroutineContext().ensureActive()
                if (canRecover()) onResult(result)
            }.also { scheduled ->
                recoveryJob = scheduled
                scheduled.invokeOnCompletion {
                    synchronized(lock) {
                        if (recoveryJob === scheduled) recoveryJob = null
                    }
                }
            }
        }
        job.start()
    }

    override fun close() {
        val job = synchronized(lock) {
            closed = true
            recoveryJob.also { recoveryJob = null }
        }
        job?.cancel()
    }
}

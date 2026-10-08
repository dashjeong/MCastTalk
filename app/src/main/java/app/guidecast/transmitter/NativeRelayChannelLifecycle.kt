package app.guidecast.transmitter

import java.util.Collections
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope

/** One run owns each language's state; a failed or closed route cannot revive on a late ACK. */
internal class NativeRelayChannelLifecycle(val sessionId: Long, targets: List<String>) : java.io.Closeable {
    val targets: List<String> = Collections.unmodifiableList(ArrayList(targets))
    private val ready = linkedSetOf<String>()
    private val failed = linkedSetOf<String>()
    private var closed = false
    private val closeSignal = CompletableDeferred<Unit>()

    init {
        require(this.targets.size in 1..5) { "Select one to five interpretation languages" }
        require(this.targets.all { it.isNotBlank() && it == it.trim() }) { "Invalid interpretation language" }
        require(this.targets.map { it.lowercase(Locale.ROOT) }.distinct().size == this.targets.size) {
            "Interpretation languages must be distinct"
        }
    }

    val isClosed: Boolean get() = synchronized(this) { closed }

    /** Repeated READY is harmless, while failure and whole-run closure remain terminal. */
    @Synchronized fun markReady(target: String): Boolean {
        require(target in targets)
        if (closed || target in failed) return false
        ready += target
        return true
    }

    /** Returns true only for the first failure of an open route. */
    @Synchronized fun fail(target: String): Boolean {
        require(target in targets)
        if (closed) return false
        ready -= target
        return failed.add(target)
    }

    /** Pending routes may accept input into their own bounded client queue. */
    @Synchronized fun accepts(target: String): Boolean = !closed && target in targets && target !in failed

    @Synchronized fun readyTargets(): List<String> =
        if (closed) emptyList() else targets.filter { it in ready && it !in failed }

    @Synchronized fun pendingTargets(): List<String> =
        if (closed) emptyList() else targets.filter { it !in ready && it !in failed }

    @Synchronized fun acceptingTargets(): List<String> =
        if (closed) emptyList() else targets.filter { it !in failed }

    @Synchronized fun allFailed(): Boolean = failed.size == targets.size

    override fun close() {
        val changed = synchronized(this) {
            if (closed) false else { closed = true; true }
        }
        // Resume waiters outside the state lock; no transport or callback runs under that lock.
        if (changed) closeSignal.complete(Unit)
    }

    fun revokeConsent() = close()

    /**
     * Wait for languages concurrently, retaining healthy siblings when one route fails.
     * The failure callback runs first so a caller can retire that route through fail().
     * It must return normally. Parent cancellation or a whole-run close retires every route.
     */
    suspend fun awaitReadyChannels(
        awaitReady: suspend (String) -> Unit,
        onFailure: (String, Throwable) -> Unit = { _, _ -> },
    ): List<String> = supervisorScope {
        check(!isClosed) { "Interpretation session is closed" }
        val lanes = targets.map { target -> async<Unit> {
            if (!accepts(target)) return@async
            try {
                awaitReady(target)
                markReady(target)
            } catch (failure: Throwable) {
                // A route-local timeout/cancel must not cancel an otherwise active parent.
                currentCoroutineContext().ensureActive()
                try { onFailure(target, failure) } finally { fail(target) }
            }
        } }
        val prepared = async {
            lanes.awaitAll()
            currentCoroutineContext().ensureActive()
            readyTargets().also {
                check(!isClosed) { "Interpretation session is closed" }
                check(it.isNotEmpty()) { "No interpretation language is ready" }
            }
        }
        try {
            select<List<String>> {
                closeSignal.onAwait { error("Interpretation session is closed") }
                prepared.onAwait { it }
            }
        } catch (failure: Throwable) {
            close()
            throw failure
        } finally {
            lanes.forEach { it.cancel() }
            prepared.cancel()
        }
    }
}

/** Resource cleanup must not relabel a failed relay as a user pause. */
internal fun nativeRelayPhaseAfterInputStop(phase: InterpreterRelayPhase): InterpreterRelayPhase =
    if (phase == InterpreterRelayPhase.FAILED) phase else InterpreterRelayPhase.PAUSED

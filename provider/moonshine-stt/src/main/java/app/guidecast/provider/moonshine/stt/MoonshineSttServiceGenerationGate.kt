package app.guidecast.provider.moonshine.stt

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred

/**
 * Serializes Android Service instances that share the private Moonshine STT process.
 *
 * A cancelled JNI call can outlive the Service instance that started it. A replacement Service
 * must therefore wait until the previous generation has confirmed that every native owner closed.
 * Empty intermediate generations stay chained and cannot accidentally bypass an older runtime.
 */
internal class MoonshineSttServiceGenerationGate {
    private val stateLock = Any()
    private var tail = completedSignal()

    fun beginGeneration(): Generation = synchronized(stateLock) {
        val generation = Generation(predecessorClosed = tail)
        tail = generation.closed
        generation
    }

    internal class Generation(
        private val predecessorClosed: CompletableDeferred<Unit>,
    ) {
        private val closeConfirmed = AtomicBoolean(false)
        internal val closed = CompletableDeferred<Unit>()

        suspend fun awaitPredecessorClosed() {
            predecessorClosed.await()
        }

        fun markClosed() {
            if (!closeConfirmed.compareAndSet(false, true)) return
            if (predecessorClosed.isCompleted) {
                closed.complete(Unit)
            } else {
                predecessorClosed.invokeOnCompletion { closed.complete(Unit) }
            }
        }

        /** A thrown native close deliberately leaves every successor generation blocked. */
        fun closeRuntimeThenMarkClosed(closeRuntime: () -> Unit) {
            closeRuntime()
            markClosed()
        }
    }

    private companion object {
        fun completedSignal() = CompletableDeferred(Unit)
    }
}

/** One coordinator for every Service instance in the private `:stt_inference` Linux process. */
internal object MoonshineSttProcessGenerationGate {
    private val delegate = MoonshineSttServiceGenerationGate()

    fun beginGeneration(): MoonshineSttServiceGenerationGate.Generation =
        delegate.beginGeneration()
}

/**
 * Owns every native transcriber reachable by one Service generation.
 *
 * This registry is confined to the Service's single native dispatcher. An owner is removed only
 * after its close call returns successfully. That makes a partial-load cleanup failure retryable
 * during shutdown and prevents a second transcriber from being created beside uncertain JNI state.
 */
internal class MoonshineSttNativeOwnerRegistry<T : Any> {
    private val trackedOwners = mutableListOf<T>()
    private var activeOwner: T? = null

    fun activeOrNull(): T? = activeOwner

    fun requireCanInitialize() {
        check(trackedOwners.isEmpty()) {
            "Moonshine STT native 초기화 실패 후 종료가 확인되지 않았습니다. " +
                "이 작업 공간에서는 새 음성인식 모델을 적재하지 않습니다."
        }
    }

    fun trackInitialization(candidate: T) {
        requireCanInitialize()
        trackedOwners += candidate
    }

    fun activate(candidate: T) {
        check(trackedOwners.any { it === candidate }) {
            "Untracked Moonshine STT native owner cannot become active"
        }
        activeOwner = candidate
    }

    fun closeFailedInitialization(
        candidate: T,
        initializationFailure: Throwable,
        closeCandidate: (T) -> Unit,
    ): Nothing {
        check(trackedOwners.any { it === candidate }) {
            "Failed Moonshine STT initialization owner is not tracked"
        }
        try {
            closeCandidate(candidate)
            removeConfirmedOwner(candidate)
        } catch (closeFailure: Throwable) {
            throw MoonshineSttNativeCloseNotConfirmedException(
                initializationFailure = initializationFailure,
                closeFailure = closeFailure,
            )
        }
        throw initializationFailure
    }

    fun closeAll(closeOwner: (T) -> Unit) {
        var closeFailure: Throwable? = null
        trackedOwners.toList().forEach { owner ->
            try {
                closeOwner(owner)
                removeConfirmedOwner(owner)
            } catch (error: Throwable) {
                val prior = closeFailure
                if (prior == null) {
                    closeFailure = error
                } else if (prior !== error) {
                    runCatching { prior.addSuppressed(error) }
                }
            }
        }
        closeFailure?.let { throw it }
    }

    internal fun tracks(owner: T): Boolean = trackedOwners.any { it === owner }

    internal fun trackedOwnerCount(): Int = trackedOwners.size

    private fun removeConfirmedOwner(owner: T) {
        val index = trackedOwners.indexOfFirst { it === owner }
        if (index >= 0) trackedOwners.removeAt(index)
        if (activeOwner === owner) activeOwner = null
    }
}

internal class MoonshineSttNativeCloseNotConfirmedException(
    initializationFailure: Throwable,
    closeFailure: Throwable,
) : IllegalStateException(
        "Moonshine STT native 초기화에 실패했고 부분 runtime 종료도 확인되지 않았습니다.",
        initializationFailure,
    ) {
    init {
        if (closeFailure !== initializationFailure) addSuppressed(closeFailure)
    }
}

/** Commits the closed state only after the native close operation returns successfully. */
internal class MoonshineSttRuntimeCloseGate {
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

/** Retries a transient close failure without ever acknowledging an unconfirmed native close. */
internal fun closeMoonshineSttRuntimeWithRetry(
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

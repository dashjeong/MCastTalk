package app.guidecast.transmitter

import android.app.ActivityManager
import android.content.Context
import app.guidecast.core.translation.NativeColdLoadTicket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Semaphore

/** Latest Android memory-pressure decision before starting another optional native worker. */
internal data class NativeSupportMemoryAdmission(
    val mayStartNewWorker: Boolean,
    val availableMemoryBytes: Long,
    val androidLowMemoryThresholdBytes: Long,
    val systemLowMemory: Boolean,
) {
    val operatorMessage: String
        get() {
            val availableMiB = availableMemoryBytes / (1024L * 1024L)
            return "현재 가용 메모리 ${availableMiB} MiB · 콜드 로드를 순차 실행합니다. " +
                "선택 언어와 음성 품질은 유지합니다."
        }

    companion object {
        /**
         * Leaves a modest reclaimable floor for the foreground server, audio capture and Android
         * itself. This is not a claim that every voice consumes this much: Android's own larger
         * low-memory threshold still wins on devices where the OS publishes one.
         */
        const val MIN_NEW_WORKER_HEADROOM_BYTES = 768L * 1024L * 1024L

        fun detect(context: Context): NativeSupportMemoryAdmission {
            val memory = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
            return forMemory(
                availableMemoryBytes = memory.availMem,
                androidLowMemoryThresholdBytes = memory.threshold,
                systemLowMemory = memory.lowMemory,
            )
        }

        fun forMemory(
            availableMemoryBytes: Long,
            androidLowMemoryThresholdBytes: Long,
            systemLowMemory: Boolean,
        ): NativeSupportMemoryAdmission {
            require(availableMemoryBytes >= 0L)
            require(androidLowMemoryThresholdBytes >= 0L)
            val requiredHeadroom = maxOf(
                MIN_NEW_WORKER_HEADROOM_BYTES,
                androidLowMemoryThresholdBytes,
            )
            return NativeSupportMemoryAdmission(
                mayStartNewWorker = !systemLowMemory &&
                    availableMemoryBytes >= requiredHeadroom,
                availableMemoryBytes = availableMemoryBytes,
                androidLowMemoryThresholdBytes = androidLowMemoryThresholdBytes,
                systemLowMemory = systemLowMemory,
            )
        }
    }
}

/**
 * Bounds the aggregate number of cold native loads across translation and speech without using a
 * volatile memory estimate as permission to remove a selected engine. Android pressure is an
 * operator/telemetry signal only: every caller eventually receives a lease unless its own
 * coroutine is cancelled. Actual provider failure, worker death or a provider timeout remains the
 * only reason to enter that language's fallback path.
 */
internal class NativeColdLoadCoordinator(
    maxParallelLoads: Int,
) {
    private val maximumParallelLoads = maxParallelLoads
    private val permits = AdaptiveNativeColdLoadPermitPool(maxParallelLoads)
    /** One process-wide lane per native worker key, shared across service/session gate instances. */
    private val keyPermits = ConcurrentHashMap<String, Semaphore>()

    init {
        require(maxParallelLoads > 0)
    }

    suspend fun acquire(
        key: String,
        serializeWithAllColdLoads: Boolean,
        currentAdmission: NativeSupportMemoryAdmission,
        onPressureDetected: (NativeSupportMemoryAdmission) -> Unit = {},
    ): NativeColdLoadTicket {
        require(key.isNotBlank())
        val keyPermit = keyPermits.computeIfAbsent(key) { Semaphore(1) }
        keyPermit.acquire()
        val pressureDetected = !currentAdmission.mayStartNewWorker
        val requestedPermits = if (serializeWithAllColdLoads || pressureDetected) {
            maximumParallelLoads
        } else {
            1
        }
        try {
            val acquiredPermits = permits.acquire(requestedPermits)
            if (pressureDetected) runCatching { onPressureDetected(currentAdmission) }
            return Lease(permits, acquiredPermits, keyPermit)
        } catch (error: Throwable) {
            keyPermit.release()
            throw error
        }
    }

    private class Lease(
        private val permits: AdaptiveNativeColdLoadPermitPool,
        private val permitsHeld: Int,
        private val keyPermit: Semaphore,
    ) : NativeColdLoadTicket {
        private val stateLock = Any()
        private var state = State.CALLER_OWNED

        override fun transferToNative() = synchronized(stateLock) {
            check(state == State.CALLER_OWNED) {
                "Native cold-load ticket cannot be transferred from $state"
            }
            state = State.NATIVE_OWNED
        }

        override fun completeNative() = synchronized(stateLock) {
            when (state) {
                State.CLOSED -> Unit
                State.CALLER_OWNED,
                State.NATIVE_OWNED,
                -> {
                    state = State.CLOSED
                    releaseAllPermits()
                }

                State.DETACHED_NATIVE_OWNED -> {
                    state = State.CLOSED
                    permits.completeDetachedNativeOwner()
                    keyPermit.release()
                }
            }
        }

        override fun close() = synchronized(stateLock) {
            when (state) {
                State.CALLER_OWNED -> {
                    state = State.CLOSED
                    releaseAllPermits()
                }

                State.NATIVE_OWNED -> {
                    // A caller result/timeout is not native completion. Retain the same-key lane
                    // and one global permit, but return excess exclusive permits so one sibling
                    // cold load can continue while this native owner remains unresolved.
                    permits.detachNativeOwner(permitsHeld)
                    state = State.DETACHED_NATIVE_OWNED
                }

                State.DETACHED_NATIVE_OWNED,
                State.CLOSED,
                -> Unit
            }
        }

        private fun releaseAllPermits() {
            permits.release(permitsHeld)
            keyPermit.release()
        }

        private enum class State {
            CALLER_OWNED,
            NATIVE_OWNED,
            DETACHED_NATIVE_OWNED,
            CLOSED,
        }
    }
}

/**
 * Atomically acquires multiple permits and degrades exclusive admission while a detached native
 * owner is unresolved. A plain semaphore cannot safely do this: taking one permit and suspending
 * for a second can deadlock exactly when an older owner downgrades to one retained permit.
 */
private class AdaptiveNativeColdLoadPermitPool(
    private val capacity: Int,
) {
    private val lock = Any()
    private var available = capacity
    private var unresolvedNativeOwners = 0
    private var changed = CompletableDeferred<Unit>()

    init {
        require(capacity > 0)
    }

    suspend fun acquire(requestedPermits: Int): Int {
        require(requestedPermits in 1..capacity)
        while (true) {
            var acquired = 0
            val waitForChange = synchronized(lock) {
                val effectiveRequest = if (unresolvedNativeOwners > 0) 1 else requestedPermits
                if (available >= effectiveRequest) {
                    available -= effectiveRequest
                    acquired = effectiveRequest
                    null
                } else {
                    changed
                }
            }
            if (acquired > 0) return acquired
            requireNotNull(waitForChange).await()
        }
    }

    fun release(permits: Int) = synchronized(lock) {
        require(permits in 1..capacity)
        check(available + permits <= capacity)
        available += permits
        signalChange()
    }

    fun detachNativeOwner(permitsHeld: Int) = synchronized(lock) {
        require(permitsHeld in 1..capacity)
        unresolvedNativeOwners++
        available += permitsHeld - 1
        check(available <= capacity)
        signalChange()
    }

    fun completeDetachedNativeOwner() = synchronized(lock) {
        check(unresolvedNativeOwners > 0)
        unresolvedNativeOwners--
        check(available < capacity)
        available++
        signalChange()
    }

    private fun signalChange() {
        val previous = changed
        changed = CompletableDeferred()
        previous.complete(Unit)
    }
}

/**
 * Atomically reserves estimated headroom across the otherwise independent translation and speech
 * gates. This is retained for capacity diagnostics and regression modelling; live service uses
 * [NativeColdLoadCoordinator] so an estimate cannot silently downgrade quality.
 */
internal class NativeSupportMemoryBudget(
    private val currentAdmission: () -> NativeSupportMemoryAdmission,
    private val reservationBytes: Long = DEFAULT_NEW_WORKER_RESERVATION_BYTES,
) {
    private val lock = Any()
    private var reservedBytes = 0L

    init {
        require(reservationBytes > 0L)
    }

    fun tryReserve(): ReservationAttempt = synchronized(lock) {
        val current = currentAdmission()
        val projectedAvailable = (current.availableMemoryBytes - reservedBytes - reservationBytes)
            .coerceAtLeast(0L)
        val projected = NativeSupportMemoryAdmission.forMemory(
            availableMemoryBytes = projectedAvailable,
            androidLowMemoryThresholdBytes = current.androidLowMemoryThresholdBytes,
            systemLowMemory = current.systemLowMemory,
        )
        if (!projected.mayStartNewWorker) {
            return@synchronized ReservationAttempt(current = current, reservation = null)
        }
        reservedBytes += reservationBytes
        ReservationAttempt(
            current = current,
            reservation = Reservation(this, reservationBytes),
        )
    }

    private fun release(bytes: Long) = synchronized(lock) {
        check(reservedBytes >= bytes)
        reservedBytes -= bytes
    }

    class Reservation internal constructor(
        private val owner: NativeSupportMemoryBudget,
        private val bytes: Long,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) owner.release(bytes)
        }
    }

    data class ReservationAttempt(
        val current: NativeSupportMemoryAdmission,
        val reservation: Reservation?,
    )

    companion object {
        // Moonshine's pinned model assets are ~20-85 MiB per voice before ORT/runtime state;
        // 256 MiB is a conservative, explicit admission reservation rather than a RAM guarantee.
        const val DEFAULT_NEW_WORKER_RESERVATION_BYTES = 256L * 1024L * 1024L
    }
}

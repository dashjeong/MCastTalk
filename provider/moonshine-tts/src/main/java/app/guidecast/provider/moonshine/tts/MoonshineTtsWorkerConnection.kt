package app.guidecast.provider.moonshine.tts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** One Binder connection owned by exactly one Moonshine output language. */
internal class MoonshineTtsWorkerConnection(
    private val applicationContext: Context,
    private val languageTag: String,
    private val serviceClass: Class<out MoonshineTtsInferenceService>,
) : Closeable {
    private val mutex = Mutex()
    private val stateLock = Any()
    private var connection: ServiceConnection? = null
    private var remote: IGuideCastMoonshineTts? = null
    private var activeGeneration = NO_WORKER_GENERATION
    private val nextGeneration = AtomicLong(1L)

    suspend fun worker(): IGuideCastMoonshineTts = mutex.withLock {
        val cached = synchronized(stateLock) {
            WorkerConnectionState(connection, remote, activeGeneration)
        }
        cached.worker?.let { worker ->
            if (runCatching { worker.asBinder().isBinderAlive }.getOrDefault(false)) return worker
            disconnectConnection(cached.connection, stopService = false)
        }
        bindWorker()
    }

    suspend fun invalidate(expectedWorker: IGuideCastMoonshineTts) = mutex.withLock {
        val expectedConnection = synchronized(stateLock) {
            if (remote?.asBinder() === expectedWorker.asBinder()) connection else null
        }
        if (expectedConnection != null) {
            disconnectConnection(expectedConnection, stopService = false)
        }
    }

    fun hasActiveConnection(): Boolean = synchronized(stateLock) {
        moonshineWorkerConnectionIsActive(
            hasConnection = connection != null,
            remoteBinderAlive = remote?.asBinder()?.let { binder ->
                runCatching { binder.isBinderAlive }.getOrDefault(false)
            },
        )
    }

    /** True only when the currently bound, live worker is the Binder that completed warm-up. */
    fun hasActiveConnection(expectedBinder: IBinder): Boolean = synchronized(stateLock) {
        remote?.asBinder() === expectedBinder &&
            runCatching { expectedBinder.isBinderAlive }.getOrDefault(false)
    }

    suspend fun disconnect(stopService: Boolean) = mutex.withLock {
        disconnectConnection(expectedConnection = null, stopService = stopService)
    }

    /**
     * Keeps the service bound until it proves that all native requests returned and its runtime
     * closed. A timeout is reported as unconfirmed; callers must not admit a replacement worker
     * merely because unbindService() itself returned.
     */
    suspend fun disconnectAndAwaitNativeClose(
        timeoutMillis: Long,
        retirementTracker: MoonshineTtsNativeRetirementTracker,
    ): Boolean = mutex.withLock {
        require(timeoutMillis > 0L)
        val state = synchronized(stateLock) {
            WorkerConnectionState(connection, remote, activeGeneration)
        }
        val worker = state.worker
        if (worker == null) {
            disconnectConnection(state.connection, stopService = false)
            return@withLock true
        }
        check(state.generation > NO_WORKER_GENERATION) {
            "Moonshine $languageTag TTS worker has no published generation"
        }
        val closeObservation = retirementTracker.observe(
            MoonshineTtsWorkerGeneration(languageTag, state.generation),
        )
        val binder = worker.asBinder()
        val closeResult = observeNativeClose(worker, closeObservation)
        val acknowledged = if (!runCatching { binder.isBinderAlive }.getOrDefault(false)) {
            // Binder death is a stronger boundary than the callback: the old process can no
            // longer retain its native runtime.
            closeObservation.markConfirmed()
            true
        } else {
            withTimeoutOrNull(timeoutMillis) { closeResult.await() } == true
        }
        if (!acknowledged) closeObservation.markUnconfirmed()
        // shutdownWhenIdle() starts cleanup independently of Android's bound-service lifetime.
        // Unbind only after acknowledgement or the bounded timeout; on timeout the provider keeps
        // replacement native languages closed and uses its Android fallback.
        disconnectConnection(state.connection, stopService = !acknowledged)
        acknowledged
    }

    /**
     * Starts a native-close observation whose lifetime is independent from the caller's short wait.
     * A timeout therefore leaves the death recipient and shutdown callback able to resolve the
     * exact language/generation later, rather than permanently downgrading every future voice.
     */
    private fun observeNativeClose(
        worker: IGuideCastMoonshineTts,
        observation: MoonshineTtsNativeCloseObservation,
    ): CompletableDeferred<Boolean> {
        val result = CompletableDeferred<Boolean>()
        val deathLinked = AtomicBoolean(false)
        val binder = worker.asBinder()
        lateinit var deathRecipient: IBinder.DeathRecipient

        fun unlinkDeathRecipient() {
            if (deathLinked.compareAndSet(true, false)) {
                runCatching { binder.unlinkToDeath(deathRecipient, 0) }
            }
        }

        fun confirmClosed() {
            observation.markConfirmed()
            result.complete(true)
            // confirmClosed can race immediately after linkToDeath() but before the flag is
            // published. Calling it again after the liveness probe safely removes that link.
            unlinkDeathRecipient()
        }

        fun reportCloseError() {
            // An error is not proof that native memory closed. Complete the bounded caller wait,
            // but keep observing Binder death so this generation can recover later.
            result.complete(false)
        }

        deathRecipient = IBinder.DeathRecipient { confirmClosed() }
        val callback = object : IGuideCastMoonshineTtsShutdownCallback.Stub() {
            override fun onShutdownComplete() = confirmClosed()

            override fun onShutdownError(message: String?) = reportCloseError()
        }
        try {
            binder.linkToDeath(deathRecipient, 0)
            deathLinked.set(true)
            if (!binder.isBinderAlive) {
                confirmClosed()
            } else {
                worker.shutdownWhenIdle(callback)
            }
        } catch (_: RemoteException) {
            if (!binder.isBinderAlive) confirmClosed() else reportCloseError()
        } catch (_: Throwable) {
            reportCloseError()
        }
        return result
    }

    private suspend fun bindWorker(): IGuideCastMoonshineTts =
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            val bindingAttempt = MoonshineTtsBindingAttemptState()

            fun fail(error: Throwable) {
                if (completed.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resumeWithException(error)
                }
            }

            val candidate = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val service = IGuideCastMoonshineTts.Stub.asInterface(binder)
                    if (service == null) {
                        fail(transportFailure("returned a null Binder"))
                        disconnectConnection(this, stopService = false)
                        return
                    }
                    val accepted = synchronized(stateLock) {
                        if (connection === this && remote == null &&
                            !completed.get() && continuation.isActive
                        ) {
                            remote = service
                            activeGeneration = nextWorkerGeneration()
                            true
                        } else {
                            false
                        }
                    }
                    if (!accepted) {
                        disconnectConnection(this, stopService = false)
                        fail(transportFailure("binding was superseded"))
                        return
                    }
                    if (completed.compareAndSet(false, true) && continuation.isActive) {
                        continuation.resume(service)
                    } else {
                        disconnectConnection(this, stopService = false)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    disconnectConnection(this, stopService = false)
                    fail(transportFailure("disconnected"))
                }

                override fun onBindingDied(name: ComponentName?) {
                    disconnectConnection(this, stopService = false)
                    fail(transportFailure("process died"))
                }

                override fun onNullBinding(name: ComponentName?) {
                    disconnectConnection(this, stopService = false)
                    fail(transportFailure("returned an empty binding"))
                }
            }

            synchronized(stateLock) {
                check(connection == null && remote == null) {
                    "A Moonshine TTS worker connection is already active for $languageTag"
                }
                connection = candidate
            }
            continuation.invokeOnCancellation {
                bindingAttempt.cancel()
                completed.compareAndSet(false, true)
                disconnectConnection(candidate, stopService = false)
            }
            if (!bindingAttempt.mayStartBinding(continuation.isActive)) {
                completed.compareAndSet(false, true)
                disconnectConnection(candidate, stopService = false)
                return@suspendCancellableCoroutine
            }
            val bound = runCatching {
                applicationContext.bindService(
                    Intent(applicationContext, serviceClass),
                    candidate,
                    Context.BIND_AUTO_CREATE or Context.BIND_NOT_FOREGROUND,
                )
            }.getOrElse { error ->
                disconnectConnection(candidate, stopService = false)
                fail(transportFailure("binding failed", error))
                false
            }
            if (!bound) {
                disconnectConnection(candidate, stopService = false)
                fail(transportFailure("Android refused the binding"))
            } else if (bindingAttempt.wasCancelled) {
                // bindService can return after cancellation already cleared local state.
                disconnectConnection(candidate, stopService = false)
            }
        }

    private fun disconnectConnection(
        expectedConnection: ServiceConnection?,
        stopService: Boolean,
    ): Boolean {
        val released = synchronized(stateLock) {
            if (expectedConnection != null && connection !== expectedConnection) {
                null
            } else {
                WorkerConnectionState(connection, remote, activeGeneration).also {
                    connection = null
                    remote = null
                    activeGeneration = NO_WORKER_GENERATION
                }
            }
        }
        if (released == null) {
            expectedConnection?.let { runCatching { applicationContext.unbindService(it) } }
            return false
        }
        if (stopService) released.worker?.let { runCatching { it.shutdown() } }
        released.connection?.let { runCatching { applicationContext.unbindService(it) } }
        return true
    }

    private fun transportFailure(message: String, cause: Throwable? = null): RemoteException =
        RemoteException("Moonshine $languageTag TTS worker $message").also { error ->
            if (cause != null) error.initCause(cause)
        }

    override fun close() {
        disconnectConnection(expectedConnection = null, stopService = true)
    }

    private fun nextWorkerGeneration(): Long {
        val generation = nextGeneration.getAndIncrement()
        check(generation > NO_WORKER_GENERATION) {
            "Moonshine $languageTag TTS worker generation overflow"
        }
        return generation
    }

    private companion object {
        const val NO_WORKER_GENERATION = 0L
    }
}

private data class WorkerConnectionState(
    val connection: ServiceConnection?,
    val worker: IGuideCastMoonshineTts?,
    val generation: Long,
)

/** Exact private-process generation whose native-close acknowledgement is still outstanding. */
internal data class MoonshineTtsWorkerGeneration(
    val languageTag: String,
    val generation: Long,
) {
    init {
        require(languageTag.isNotBlank())
        require(generation > 0L)
    }
}

/**
 * Tracks only unconfirmed generations. A late shutdown callback or Binder death removes the exact
 * generation and cannot accidentally clear a newer failure for the same language.
 */
internal class MoonshineTtsNativeRetirementTracker {
    private val lock = Any()
    private val unresolved = linkedSetOf<MoonshineTtsWorkerGeneration>()

    fun observe(generation: MoonshineTtsWorkerGeneration): MoonshineTtsNativeCloseObservation =
        MoonshineTtsNativeCloseObservation(
            generation = generation,
            publishUnconfirmed = { unresolvedGeneration ->
                synchronized(lock) { unresolved += unresolvedGeneration }
            },
            publishConfirmed = { resolvedGeneration ->
                synchronized(lock) { unresolved -= resolvedGeneration }
            },
        )

    fun snapshot(): Set<MoonshineTtsWorkerGeneration> =
        synchronized(lock) { unresolved.toSet() }
}

/** Race-free bridge between a bounded caller wait and the longer-lived Binder observation. */
internal class MoonshineTtsNativeCloseObservation(
    private val generation: MoonshineTtsWorkerGeneration,
    private val publishUnconfirmed: (MoonshineTtsWorkerGeneration) -> Unit,
    private val publishConfirmed: (MoonshineTtsWorkerGeneration) -> Unit,
) {
    private val lock = Any()
    private var unconfirmedPublished = false
    private var confirmed = false

    fun markUnconfirmed() = synchronized(lock) {
        if (confirmed || unconfirmedPublished) return@synchronized
        publishUnconfirmed(generation)
        unconfirmedPublished = true
    }

    fun markConfirmed() = synchronized(lock) {
        if (confirmed) return@synchronized
        confirmed = true
        if (unconfirmedPublished) publishConfirmed(generation)
    }
}

/** `null` means bindService is in progress and no remote Binder has arrived yet. */
internal fun moonshineWorkerConnectionIsActive(
    hasConnection: Boolean,
    remoteBinderAlive: Boolean?,
): Boolean = hasConnection && (remoteBinderAlive == null || remoteBinderAlive)

/** Stable language-to-process mapping; a running slot is never reassigned to another voice. */
internal object MoonshineTtsWorkerServices {
    private val services = linkedMapOf<String, Class<out MoonshineTtsInferenceService>>(
        "en" to MoonshineTtsEnglishInferenceService::class.java,
        "ja" to MoonshineTtsJapaneseInferenceService::class.java,
        "zh" to MoonshineTtsChineseInferenceService::class.java,
        "nl" to MoonshineTtsDutchInferenceService::class.java,
        "es" to MoonshineTtsSpanishInferenceService::class.java,
        "ar" to MoonshineTtsArabicInferenceService::class.java,
    )

    fun forLanguage(languageTag: String): Class<out MoonshineTtsInferenceService> =
        requireNotNull(services[languageTag]) {
            "No isolated Moonshine TTS worker for $languageTag"
        }

    internal fun snapshot(): Map<String, Class<out MoonshineTtsInferenceService>> = services.toMap()
}

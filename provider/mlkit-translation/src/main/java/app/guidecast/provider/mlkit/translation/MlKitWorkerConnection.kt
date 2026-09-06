package app.guidecast.provider.mlkit.translation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Owns the binding for exactly one static ML Kit language-worker process. */
internal class MlKitWorkerConnection(
    private val applicationContext: Context,
    private val serviceClass: Class<out MlKitInferenceService>,
) : Closeable {
    private val connectionMutex = Mutex()
    private val stateLock = Any()
    private var connection: ServiceConnection? = null
    private var remote: IGuideCastMlKitInference? = null

    suspend fun worker(): IGuideCastMlKitInference = connectionMutex.withLock {
        val cached = synchronized(stateLock) { WorkerState(connection, remote) }
        cached.worker?.let { worker ->
            if (runCatching { worker.asBinder().isBinderAlive }.getOrDefault(false)) return worker
            disconnectConnection(cached.connection, stopService = false)
        }
        bindWorker()
    }

    suspend fun invalidate(expectedWorker: IGuideCastMlKitInference) = connectionMutex.withLock {
        val expectedConnection = synchronized(stateLock) {
            if (remote?.asBinder() === expectedWorker.asBinder()) connection else null
        }
        if (expectedConnection != null) {
            disconnectConnection(expectedConnection, stopService = false)
        }
    }

    fun hasActiveConnection(): Boolean = synchronized(stateLock) {
        connection != null && remote?.asBinder()?.isBinderAlive == true
    }

    /** Returns the exact live Binder generation; a binding alone is not native-readiness proof. */
    fun activeBinder(): IBinder? = synchronized(stateLock) {
        remote?.asBinder()?.takeIf { binder ->
            connection != null && runCatching { binder.isBinderAlive }.getOrDefault(false)
        }
    }

    suspend fun disconnect(stopService: Boolean) = connectionMutex.withLock {
        disconnectConnection(expectedConnection = null, stopService = stopService)
    }

    private suspend fun bindWorker(): IGuideCastMlKitInference =
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            val bindingReturned = AtomicBoolean(false)

            fun fail(error: Throwable) {
                if (completed.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resumeWithException(error)
                }
            }

            val candidate = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val service = IGuideCastMlKitInference.Stub.asInterface(binder)
                    if (service == null) {
                        fail(MlKitWorkerTransportException("ML Kit worker returned a null binder"))
                        disconnectConnection(this, stopService = false)
                        return
                    }
                    val accepted = synchronized(stateLock) {
                        if (connection === this && remote == null &&
                            !completed.get() && continuation.isActive
                        ) {
                            remote = service
                            true
                        } else {
                            false
                        }
                    }
                    if (!accepted) {
                        disconnectConnection(this, stopService = false)
                        fail(MlKitWorkerTransportException("ML Kit worker binding was superseded"))
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
                    fail(MlKitWorkerTransportException("ML Kit worker disconnected"))
                }

                override fun onBindingDied(name: ComponentName?) {
                    disconnectConnection(this, stopService = false)
                    fail(MlKitWorkerTransportException("ML Kit worker process died"))
                }

                override fun onNullBinding(name: ComponentName?) {
                    disconnectConnection(this, stopService = false)
                    fail(MlKitWorkerTransportException("ML Kit worker returned an empty binding"))
                }
            }

            synchronized(stateLock) {
                check(connection == null && remote == null) {
                    "An ML Kit worker connection is already active"
                }
                connection = candidate
            }
            continuation.invokeOnCancellation {
                completed.compareAndSet(false, true)
                disconnectConnection(candidate, stopService = false)
                if (bindingReturned.get()) {
                    // bindService can return after cancellation cleared state. Once Android owns
                    // the binding, repeat the identity-safe unbind to avoid resurrecting it.
                    disconnectConnection(candidate, stopService = false)
                }
            }
            if (!continuation.isActive) {
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
                fail(MlKitWorkerTransportException("ML Kit worker binding failed", error))
                false
            }
            bindingReturned.set(true)
            if (!bound) {
                disconnectConnection(candidate, stopService = false)
                fail(MlKitWorkerTransportException("Android refused the ML Kit worker binding"))
            } else if (!continuation.isActive) {
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
                WorkerState(connection, remote).also {
                    connection = null
                    remote = null
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

    override fun close() {
        // Application.onTerminate and test shutdown are synchronous. State release is still
        // identity-safe; Binder teardown itself is non-blocking.
        disconnectConnection(expectedConnection = null, stopService = true)
    }
}

private data class WorkerState(
    val connection: ServiceConnection?,
    val worker: IGuideCastMlKitInference?,
)

internal class MlKitWorkerTransportException(
    message: String,
    cause: Throwable? = null,
) : RemoteException(message) {
    init {
        if (cause != null) initCause(cause)
    }
}

internal fun Throwable.isMlKitWorkerTransportFailure(): Boolean {
    var current: Throwable? = this
    repeat(8) {
        if (current is DeadObjectException || current is RemoteException ||
            current is MlKitWorkerTransportException
        ) {
            return true
        }
        val next = current?.cause
        if (next == null || next === current) return false
        current = next
    }
    return false
}

internal object MlKitInferenceServices {
    private val services: List<Class<out MlKitInferenceService>> = listOf(
        MlKitInferenceService0::class.java,
        MlKitInferenceService1::class.java,
        MlKitInferenceService2::class.java,
        MlKitInferenceService3::class.java,
        MlKitInferenceService4::class.java,
        MlKitInferenceService5::class.java,
        MlKitInferenceService6::class.java,
    )

    fun forSlot(slot: Int): Class<out MlKitInferenceService> = services[slot]
}

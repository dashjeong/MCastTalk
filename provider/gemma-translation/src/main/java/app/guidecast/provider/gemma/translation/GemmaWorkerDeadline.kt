package app.guidecast.provider.gemma.translation

import android.app.Application
import android.os.Process
import android.util.Log
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Only a definitive native terminal may disarm a submitted generation's safety deadline. */
internal class GemmaDeadlineState {
    private val claimed = AtomicBoolean(false)
    fun complete(): Boolean = claimed.compareAndSet(false, true)
    fun expire(): Boolean = claimed.compareAndSet(false, true)
}

internal fun isDedicatedGemmaWorker(packageName: String, processName: String): Boolean =
    packageName.isNotBlank() && processName == "$packageName:gemma_inference"

/**
 * JNI cancellation is not a memory-reclamation boundary. If a submitted decode never signals
 * terminal, retire only this application's dedicated worker, never the UI/broadcast process.
 * This is a leak/stall safety ceiling, NOT the user-facing translation latency budget.
 */
internal object GemmaWorkerDeadline {
    private const val NATIVE_TERMINAL_DEADLINE_MILLIS = 120_000L
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "GuideCast-Gemma-deadline").apply { isDaemon = true }
    }

    fun arm(packageName: String): Closeable {
        check(isDedicatedGemmaWorker(packageName, Application.getProcessName())) {
            "Native inference must run in the dedicated Gemma worker"
        }
        val state = GemmaDeadlineState()
        val future = scheduler.schedule({
            if (state.expire() && isDedicatedGemmaWorker(packageName, Application.getProcessName())) {
                Log.e("GuideCastGemma", "native_terminal_deadline: retiring dedicated worker")
                // Binder death supplies the definitive native release to the parent process.
                Process.killProcess(Process.myPid())
            }
        }, NATIVE_TERMINAL_DEADLINE_MILLIS, TimeUnit.MILLISECONDS)
        return Closeable {
            if (state.complete()) future.cancel(false)
        }
    }
}

package app.guidecast.provider.android.tts

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal interface RemovableTtsBinderExecutor : Executor {
    fun removeQueued(task: Runnable)
}

/**
 * A frozen vendor cannot occupy another vendor's dispatch threads. Even cancelled transactions
 * retain their lane until the actual OS call returns; repeated retries never replace stuck threads.
 * Idle vendors may be evicted, but the process retains at most [maximumVendors] lanes of fixed size.
 */
internal class AndroidTtsVendorBinderLanes(
    private val maximumVendors: Int = 8,
    private val threadsPerVendor: Int = 2,
    private val queueCapacity: Int = 14,
) : Closeable {
    init { require(maximumVendors > 0 && threadsPerVendor > 0 && queueCapacity > 0) }
    private val lock = Any()
    private val lanes = LinkedHashMap<String?, Lane>()
    private var closed = false
    private var nextLane = 0L

    fun executorFor(vendor: String?): RemovableTtsBinderExecutor = object : RemovableTtsBinderExecutor {
        override fun execute(command: Runnable) = submit(vendor, command)
        override fun removeQueued(task: Runnable) {
            synchronized(lock) {
                val lane = lanes[vendor] ?: return
                val wrapped = lane.pending[task] ?: return
                if (lane.pool.remove(wrapped)) lane.pending.remove(task)
            }
        }
    }

    private fun submit(vendor: String?, task: Runnable) {
        synchronized(lock) {
            if (closed) throw RejectedExecutionException("Android TTS vendor dispatcher is closed")
            val lane = lanes[vendor] ?: run {
                if (lanes.size >= maximumVendors) {
                    val idle = lanes.entries.firstOrNull { it.value.pending.isEmpty() }
                        ?: throw RejectedExecutionException("Android TTS vendor lane limit reached")
                    lanes.remove(idle.key)
                    idle.value.pool.shutdown()
                }
                val name = "guidecast-tts-vendor-${++nextLane}"
                Lane(ThreadPoolExecutor(threadsPerVendor, threadsPerVendor, 30L, TimeUnit.SECONDS,
                    ArrayBlockingQueue(queueCapacity),
                    ThreadFactory { job -> Thread(job, name).apply { isDaemon = true } },
                    ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) })
                    .also { lanes[vendor] = it }
            }
            val wrapped = Runnable {
                try { task.run() }
                finally { synchronized(lock) { lane.pending.remove(task) } }
            }
            lane.pending[task] = wrapped
            try {
                lane.pool.execute(wrapped)
            } catch (failure: RuntimeException) {
                lane.pending.remove(task)
                throw failure
            }
        }
    }

    internal val retainedLaneCount: Int get() = synchronized(lock) { lanes.size }
    internal val pendingOperationCount: Int get() = synchronized(lock) { lanes.values.sumOf { it.pending.size } }

    override fun close() = synchronized(lock) {
        closed = true
        lanes.values.forEach { it.pool.shutdownNow() }
    }

    private class Lane(val pool: ThreadPoolExecutor) {
        val pending = java.util.IdentityHashMap<Runnable, Runnable>()
    }
}

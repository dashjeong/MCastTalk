package app.guidecast.core.translation

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout

data class FairTranslationQueueConfig(
    val maxPendingPerLanguage: Int = 8,
    val queueWaitTimeoutMillis: Long = 20_000L,
    val inferenceTimeoutMillis: Long = 4_000L,
    /** Scheduling credit added to a language on each round. It never limits input length. */
    val costQuantum: Int = 256,
    /** Each full interval reduces an old head ticket's scheduling cost by one [costQuantum]. */
    val agingQuantumMillis: Long = 1_000L,
) {
    init {
        require(maxPendingPerLanguage > 0) { "Per-language pending capacity must be positive" }
        require(queueWaitTimeoutMillis > 0L) { "Queue wait timeout must be positive" }
        require(inferenceTimeoutMillis > 0L) { "Inference timeout must be positive" }
        require(queueWaitTimeoutMillis <= Long.MAX_VALUE - inferenceTimeoutMillis) {
            "Combined translation timeout is too large"
        }
        require(costQuantum > 0) { "Scheduling cost quantum must be positive" }
        require(agingQuantumMillis > 0L) { "Scheduling aging interval must be positive" }
    }

    val maximumCallDurationMillis: Long = queueWaitTimeoutMillis + inferenceTimeoutMillis
}

enum class FairTranslationOutcome {
    SUCCESS,
    FAILURE,
    CANCELLED,
    QUEUE_WAIT_TIMEOUT,
    QUEUE_FULL,
    CLOSED,
}

data class FairTranslationTiming(
    val targetLanguageTag: String,
    val queueWaitMillis: Long,
    val inferenceMillis: Long,
    val outcome: FairTranslationOutcome,
)

fun interface FairTranslationQueueObserver {
    fun onTranslationFinished(timing: FairTranslationTiming)

    companion object {
        val NONE = FairTranslationQueueObserver {}
    }
}

class TranslationQueueFullException(
    val targetLanguageTag: String,
    val capacity: Int,
) : IllegalStateException(
    "Translation queue for $targetLanguageTag already has $capacity pending requests",
)

class TranslationQueueWaitTimeoutException(
    val targetLanguageTag: String,
    val timeoutMillis: Long,
) : IllegalStateException(
    "Translation queue admission for $targetLanguageTag exceeded ${timeoutMillis}ms",
)

class TranslationInferenceTimeoutException(
    val targetLanguageTag: String,
    val timeoutMillis: Long,
) : IllegalStateException(
    "Translation inference for $targetLanguageTag exceeded ${timeoutMillis}ms",
)

class TranslationQueueClosedException : CancellationException("Translation queue is closed")

/**
 * Serializes complete translation calls through one shared engine while preserving FIFO within
 * each target language. Scheduling uses deficit round-robin with age credit: request length only
 * affects selection order and the original text is always passed to the delegate unchanged.
 *
 * The worker is a child of [parentScope] and [close] cancels the complete session queue. Cancelling
 * an admitted request cancels its execution child, but the worker does not select another request
 * until that child actually terminates. A native delegate that delays cancellation therefore keeps
 * logical ownership of the shared engine until its own call returns or throws.
 */
class FairQueuedTranslationEngineProvider(
    private val delegate: TranslationEngineProvider,
    parentScope: CoroutineScope,
    private val config: FairTranslationQueueConfig = FairTranslationQueueConfig(),
    private val observer: FairTranslationQueueObserver = FairTranslationQueueObserver.NONE,
    private val nanoTime: () -> Long = System::nanoTime,
) : TranslationEngineProvider, AutoCloseable {
    private val stateLock = Any()
    private val queues = LinkedHashMap<String, LanguageQueue>()
    private val fairRing = ArrayDeque<String>()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val supervisor = SupervisorJob(parentScope.coroutineContext[Job])
    private val workerScope = CoroutineScope(parentScope.coroutineContext.minusKey(Job) + supervisor)

    private var closed = false
    private var activeTicket: Ticket? = null
    private val worker = workerScope.launch { runWorker() }

    val isClosed: Boolean
        get() = synchronized(stateLock) { closed }

    internal val isWorkerActive: Boolean
        get() = worker.isActive

    override fun engineFor(targetLanguageTag: String): TextTranslationEngine {
        synchronized(stateLock) {
            if (closed) throw TranslationQueueClosedException()
        }
        val delegateEngine = delegate.engineFor(targetLanguageTag)
        return object : BoundedQueuedTranslationEngine {
            override val maximumCallDurationMillis: Long = config.maximumCallDurationMillis

            override suspend fun translateWithContext(
                text: String,
                contextBefore: String?,
                sourceLanguageTag: String,
                targetLanguageTag: String,
            ): String {
                val ticket = Ticket(
                    targetLanguageTag = targetLanguageTag,
                    text = text,
                    contextBefore = contextBefore,
                    sourceLanguageTag = sourceLanguageTag,
                    delegate = delegateEngine,
                    executionContext = currentCoroutineContext().minusKey(Job),
                    enqueuedAtNanos = nanoTime(),
                    cost = text.length.coerceAtLeast(1).toLong(),
                )
                enqueue(ticket)

                try {
                    try {
                        withTimeout(config.queueWaitTimeoutMillis) {
                            ticket.admitted.await()
                        }
                    } catch (_: TimeoutCancellationException) {
                        val timeout = TranslationQueueWaitTimeoutException(
                            targetLanguageTag = targetLanguageTag,
                            timeoutMillis = config.queueWaitTimeoutMillis,
                        )
                        if (expireWhileQueued(ticket, timeout)) throw timeout

                        // Admission won the timeout race, so only the inference deadline applies.
                        ticket.admitted.await()
                    }
                    return ticket.result.await()
                } catch (cancelled: CancellationException) {
                    cancelTicket(ticket, cancelled)
                    throw cancelled
                }
            }
        }
    }

    override fun close() {
        val pending: List<Ticket>
        val active: Ticket?
        synchronized(stateLock) {
            if (closed) return
            closed = true
            pending = queues.values.flatMap { it.tickets }
            queues.clear()
            fairRing.clear()
            pending.forEach { it.state = TicketState.TERMINAL }
            active = activeTicket
            activeTicket = null
        }

        val closedException = TranslationQueueClosedException()
        pending.forEach { ticket ->
            ticket.admitted.completeExceptionally(closedException)
            ticket.result.completeExceptionally(closedException)
            report(ticket, FairTranslationOutcome.CLOSED, nanoTime(), inferenceMillis = 0L)
        }
        active?.let { ticket ->
            ticket.result.completeExceptionally(closedException)
            report(
                ticket,
                FairTranslationOutcome.CLOSED,
                ticket.admittedAtNanos ?: nanoTime(),
                inferenceMillis = elapsedMillis(ticket.admittedAtNanos ?: nanoTime(), nanoTime()),
            )
        }
        supervisor.cancel(closedException)
    }

    private fun enqueue(ticket: Ticket) {
        var failure: Throwable? = null
        synchronized(stateLock) {
            if (closed) {
                failure = TranslationQueueClosedException()
            } else {
                val queue = queues.getOrPut(ticket.targetLanguageTag) {
                    fairRing.addLast(ticket.targetLanguageTag)
                    LanguageQueue()
                }
                if (queue.tickets.size >= config.maxPendingPerLanguage) {
                    failure = TranslationQueueFullException(
                        targetLanguageTag = ticket.targetLanguageTag,
                        capacity = config.maxPendingPerLanguage,
                    )
                    if (queue.tickets.isEmpty()) {
                        queues.remove(ticket.targetLanguageTag)
                        fairRing.remove(ticket.targetLanguageTag)
                    }
                } else {
                    queue.tickets.addLast(ticket)
                }
            }
        }

        failure?.let { error ->
            val outcome = if (error is TranslationQueueFullException) {
                FairTranslationOutcome.QUEUE_FULL
            } else {
                FairTranslationOutcome.CLOSED
            }
            report(ticket, outcome, ticket.enqueuedAtNanos, inferenceMillis = 0L)
            throw error
        }
        wakeups.trySend(Unit)
    }

    private suspend fun runWorker() {
        try {
            while (currentCoroutineContext().isActive) {
                val ticket = takeNextTicket()
                if (ticket == null) {
                    wakeups.receive()
                } else {
                    execute(ticket)
                }
            }
        } finally {
            close()
        }
    }

    private fun takeNextTicket(): Ticket? = synchronized(stateLock) {
        if (closed || fairRing.isEmpty()) return@synchronized null

        while (fairRing.isNotEmpty()) {
            val languagesThisRound = fairRing.size
            repeat(languagesThisRound) {
                val language = fairRing.removeFirst()
                val queue = queues[language] ?: return@repeat
                val head = queue.tickets.firstOrNull()
                if (head == null) {
                    queues.remove(language)
                    return@repeat
                }

                queue.deficit = saturatingAdd(queue.deficit, config.costQuantum.toLong())
                val ageMillis = elapsedMillis(head.enqueuedAtNanos, nanoTime())
                val agingRounds = ageMillis / config.agingQuantumMillis
                val ageCredit = saturatingMultiply(agingRounds, config.costQuantum.toLong())
                val effectiveCost = (head.cost - ageCredit).coerceAtLeast(1L)
                if (queue.deficit >= effectiveCost) {
                    queue.deficit = (queue.deficit - head.cost).coerceAtLeast(0L)
                    queue.tickets.removeFirst()
                    if (queue.tickets.isEmpty()) {
                        queues.remove(language)
                    } else {
                        fairRing.addLast(language)
                    }
                    head.state = TicketState.ADMITTED
                    head.admittedAtNanos = nanoTime()
                    activeTicket = head
                    head.admitted.complete(Unit)
                    return@synchronized head
                }
                fairRing.addLast(language)
            }
        }
        null
    }

    private suspend fun execute(ticket: Ticket) = supervisorScope {
        val execution = async(
            context = ticket.executionContext,
            start = CoroutineStart.LAZY,
        ) {
            try {
                withTimeout(config.inferenceTimeoutMillis) {
                    ticket.delegate.translatePreservingContext(
                        text = ticket.text,
                        contextBefore = ticket.contextBefore,
                        sourceLanguageTag = ticket.sourceLanguageTag,
                        targetLanguageTag = ticket.targetLanguageTag,
                    )
                }
            } catch (_: TimeoutCancellationException) {
                throw TranslationInferenceTimeoutException(
                    targetLanguageTag = ticket.targetLanguageTag,
                    timeoutMillis = config.inferenceTimeoutMillis,
                )
            }
        }

        val cancellation = synchronized(stateLock) {
            ticket.execution = execution
            if (ticket.state == TicketState.CANCELLING) {
                ticket.cancellation
            } else {
                ticket.state = TicketState.ACTIVE
                null
            }
        }
        if (cancellation == null) execution.start() else execution.cancel(cancellation)

        val result = runCatching { execution.await() }
        val finishedAtNanos = nanoTime()
        synchronized(stateLock) {
            if (activeTicket === ticket) activeTicket = null
            ticket.execution = null
            ticket.state = TicketState.TERMINAL
        }

        result.fold(
            onSuccess = { translated ->
                ticket.result.complete(translated)
                report(
                    ticket,
                    FairTranslationOutcome.SUCCESS,
                    finishedAtNanos,
                    inferenceMillis = elapsedMillis(
                        ticket.admittedAtNanos ?: finishedAtNanos,
                        finishedAtNanos,
                    ),
                )
            },
            onFailure = { error ->
                ticket.result.completeExceptionally(error)
                report(
                    ticket,
                    if (error is CancellationException) {
                        FairTranslationOutcome.CANCELLED
                    } else {
                        FairTranslationOutcome.FAILURE
                    },
                    finishedAtNanos,
                    inferenceMillis = elapsedMillis(
                        ticket.admittedAtNanos ?: finishedAtNanos,
                        finishedAtNanos,
                    ),
                )
            },
        )
    }

    private fun expireWhileQueued(ticket: Ticket, timeout: TranslationQueueWaitTimeoutException): Boolean {
        val removed = synchronized(stateLock) {
            if (ticket.state != TicketState.QUEUED) return@synchronized false
            removeQueuedTicket(ticket)
            ticket.state = TicketState.TERMINAL
            true
        }
        if (removed) {
            ticket.admitted.completeExceptionally(timeout)
            ticket.result.completeExceptionally(timeout)
            report(ticket, FairTranslationOutcome.QUEUE_WAIT_TIMEOUT, nanoTime(), inferenceMillis = 0L)
        }
        return removed
    }

    private fun cancelTicket(ticket: Ticket, cancellation: CancellationException) {
        var execution: Deferred<String>? = null
        var cancelledWhileQueued = false
        synchronized(stateLock) {
            when (ticket.state) {
                TicketState.QUEUED -> {
                    removeQueuedTicket(ticket)
                    ticket.state = TicketState.TERMINAL
                    cancelledWhileQueued = true
                }
                TicketState.ADMITTED, TicketState.ACTIVE -> {
                    ticket.state = TicketState.CANCELLING
                    ticket.cancellation = cancellation
                    execution = ticket.execution
                }
                TicketState.CANCELLING, TicketState.TERMINAL -> Unit
            }
        }
        if (cancelledWhileQueued) {
            ticket.admitted.cancel(cancellation)
            ticket.result.cancel(cancellation)
            report(ticket, FairTranslationOutcome.CANCELLED, nanoTime(), inferenceMillis = 0L)
        }
        execution?.cancel(cancellation)
    }

    private fun removeQueuedTicket(ticket: Ticket) {
        val queue = queues[ticket.targetLanguageTag] ?: return
        queue.tickets.remove(ticket)
        if (queue.tickets.isEmpty()) {
            queues.remove(ticket.targetLanguageTag)
            fairRing.remove(ticket.targetLanguageTag)
        }
    }

    private fun report(
        ticket: Ticket,
        outcome: FairTranslationOutcome,
        finishedAtNanos: Long,
        inferenceMillis: Long,
    ) {
        val shouldReport = synchronized(stateLock) {
            if (ticket.reported) false else {
                ticket.reported = true
                true
            }
        }
        if (!shouldReport) return

        val admittedAt = ticket.admittedAtNanos ?: finishedAtNanos
        try {
            observer.onTranslationFinished(
                FairTranslationTiming(
                    targetLanguageTag = ticket.targetLanguageTag,
                    queueWaitMillis = elapsedMillis(ticket.enqueuedAtNanos, admittedAt),
                    inferenceMillis = inferenceMillis,
                    outcome = outcome,
                ),
            )
        } catch (_: Exception) {
            // Diagnostics must not break translation or stop the shared scheduler.
        }
    }

    private fun elapsedMillis(startNanos: Long, endNanos: Long): Long =
        ((endNanos - startNanos).coerceAtLeast(0L)) / NANOS_PER_MILLISECOND

    private class LanguageQueue(
        val tickets: ArrayDeque<Ticket> = ArrayDeque(),
        var deficit: Long = 0L,
    )

    private class Ticket(
        val targetLanguageTag: String,
        val text: String,
        val contextBefore: String?,
        val sourceLanguageTag: String,
        val delegate: TextTranslationEngine,
        val executionContext: CoroutineContext,
        val enqueuedAtNanos: Long,
        val cost: Long,
        val admitted: CompletableDeferred<Unit> = CompletableDeferred(),
        val result: CompletableDeferred<String> = CompletableDeferred(),
        var admittedAtNanos: Long? = null,
        var state: TicketState = TicketState.QUEUED,
        var execution: Deferred<String>? = null,
        var cancellation: CancellationException? = null,
        var reported: Boolean = false,
    )

    private enum class TicketState {
        QUEUED,
        ADMITTED,
        ACTIVE,
        CANCELLING,
        TERMINAL,
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L

        fun saturatingAdd(left: Long, right: Long): Long =
            if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

        fun saturatingMultiply(left: Long, right: Long): Long =
            if (left == 0L || right == 0L) 0L
            else if (left > Long.MAX_VALUE / right) Long.MAX_VALUE
            else left * right
    }
}

private suspend fun TextTranslationEngine.translatePreservingContext(
    text: String,
    contextBefore: String?,
    sourceLanguageTag: String,
    targetLanguageTag: String,
): String = if (this is ContextualTextTranslationEngine) {
    translateWithContext(text, contextBefore, sourceLanguageTag, targetLanguageTag)
} else {
    translate(text, sourceLanguageTag, targetLanguageTag)
}

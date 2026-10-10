package app.guidecast.transmitter

import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout

/** Candidate provenance is independent of the native translated audio. No native pair is created. */
internal data class DeferredTeacherSource(
    val session: Long,
    val lane: String,
    val observation: Long,
    val source: String,
    val original: String,
    val originalCompletionObserved: Boolean,
    val interrupted: Boolean = false,
    val renewalAmbiguous: Boolean = false,
) { override fun toString() = "DeferredTeacherSource(content=redacted)" }

internal data class DeferredTeacherFence(
    val apiRevision: Long,
    val corpusRevision: Long,
    val modelGeneration: Long,
    val inputEpoch: Long,
    val grantGeneration: Long,
    val idle: Boolean,
    val prepared: Boolean,
    val resourcesAvailable: Boolean,
    val activityLifetime: String = "",
) {
    fun sameMaterials(other: DeferredTeacherFence) = apiRevision == other.apiRevision &&
        corpusRevision == other.corpusRevision && modelGeneration == other.modelGeneration &&
        grantGeneration == other.grantGeneration && activityLifetime == other.activityLifetime
    fun mayWork() = idle && prepared && resourcesAvailable
}

/** The caller grants text upload and extra requests separately from native audio consent. */
internal data class DeferredTeacherGrant(
    val generation: Long,
    val targets: List<String>,
    val extraTextAndCostConfirmed: Boolean,
    val maximumRequests: Int = 3,
    val maximumSourceChars: Int = 1_500,
    val maximumOutputTokens: Int = 1_024,
)

/** localRequestId is locally generated correlation, never a provider utterance/pair ID. */
internal data class DeferredTeacherBatch(
    val localRequestId: String,
    val original: String,
    val source: String,
    val targets: List<String>,
    val translations: Map<String, String>,
) { override fun toString() = "DeferredTeacherBatch(content=redacted)" }

internal enum class DeferredTeacherPhase { OFF, WAITING_IDLE, REQUESTING, COMPARING, READY_FOR_REVIEW, STOPPED, REJECTED }
internal data class DeferredTeacherStatus(val phase: DeferredTeacherPhase = DeferredTeacherPhase.OFF,
    val attemptedRequests: Int = 0, val queued: Int = 0, val dropped: Int = 0)

/**
 * Archived local-ASR source admission and a separate purpose-authorized text request are required.
 * The worker cannot publish audio, prepare a model or auto-approve human review.
 */
internal class DeferredNativeTeacherComparison(
    scope: CoroutineScope,
    private val environment: () -> DeferredTeacherFence,
    private val grantCurrent: (DeferredTeacherGrant) -> Boolean,
    private val sourceTextValid: (String) -> Boolean,
    private val targetValid: (String) -> Boolean,
    private val teacher: suspend (DeferredTeacherSource, DeferredTeacherGrant, () -> Boolean) -> DeferredTeacherBatch,
    private val offline: suspend (DeferredTeacherSource, String, () -> Boolean) -> String,
    private val protectedPairAccepted: (String, String, String, String) -> Boolean,
    private val offerForReview: (DeferredTeacherSource, DeferredTeacherBatch, Map<String, String>, () -> Boolean) -> Unit,
    private val statusChanged: (DeferredTeacherStatus) -> Unit = {},
    private val externallyHeldLocalLease: Boolean = false,
    private val offlineTimeoutMillis: Long = 4_000L,
) : Closeable {
    init { require(offlineTimeoutMillis in 1L..10_000L) }
    private data class Pending(val source: DeferredTeacherSource, val captured: DeferredTeacherFence,
        val grant: DeferredTeacherGrant)
    private val lock = Any()
    private val pending = ArrayDeque<Pending>()
    private val seen = linkedSetOf<Triple<Long, String, Long>>()
    private var grant: DeferredTeacherGrant? = null
    private var attempted = 0
    private var admittedChars = 0
    private var dropped = 0
    private var newestGrantGeneration = Long.MIN_VALUE
    private var phase = DeferredTeacherPhase.OFF
    private var closed = false
    private var running: Job? = null
    private var runningWork: Pending? = null
    private var releasing = false
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val watcher = scope.launch {
        while (isActive) {
            val observing = synchronized(lock) {
                val job = running
                if (job?.isCompleted == true) { running = null; runningWork = null; releasing = false }
                if (running == null && pending.isEmpty()) false else {
                    val current = environment()
                    val active = runningWork
                    if (job != null && !job.isCompleted && (!current.mayWork() || active == null ||
                        grant !== active.grant || !grantCurrent(active.grant) || !active.captured.sameMaterials(current))) job.cancel()
                    if (!externallyHeldLocalLease && !closed && !releasing && running == null && pending.isNotEmpty() && current.mayWork()) {
                        val work = pending.removeFirst()
                        runningWork = work
                        running = launch { runOne(work) }
                    }
                    true
                }
            }
            if (observing) delay(100) else wake.receive() // OFF/empty parks; no polling or model/resource getter.
        }
    }

    /** A repeated/older grant cannot reset paid attempt counts or seen-source admission. */
    fun begin(explicitGrant: DeferredTeacherGrant): Boolean {
        var notification: DeferredTeacherStatus? = null
        val accepted = synchronized(lock) {
            if (closed || running?.isCompleted == false || explicitGrant.generation <= newestGrantGeneration ||
                !explicitGrant.extraTextAndCostConfirmed || explicitGrant.maximumRequests !in 1..3 ||
                explicitGrant.maximumSourceChars !in 1..1_500 || explicitGrant.maximumOutputTokens !in 1..1_024 ||
                explicitGrant.targets.size !in 1..5 || explicitGrant.targets.distinct().size != explicitGrant.targets.size ||
                explicitGrant.targets.any { !targetValid(it) } || !grantCurrent(explicitGrant)) false else {
                val captured = explicitGrant.copy(targets = java.util.Collections.unmodifiableList(explicitGrant.targets.toList()))
                pending.clear(); seen.clear(); attempted = 0; admittedChars = 0; dropped = 0
                newestGrantGeneration = captured.generation
                grant = captured
                notification = snapshot(DeferredTeacherPhase.WAITING_IDLE)
                true
            }
        }
        notification?.let(statusChanged)
        return accepted
    }

    /** O(1) bounded memory admission; this must not load models, query SQLite or call HTTP. */
    fun offer(source: DeferredTeacherSource): Boolean {
        var notification: DeferredTeacherStatus? = null
        val accepted = synchronized(lock) {
            val consent = grant
            if (consent == null) false else {
                val key = Triple(source.session, source.lane, source.observation)
                if (closed || !grantCurrent(consent) || !source.originalCompletionObserved || source.interrupted ||
                    attempted >= consent.maximumRequests || source.renewalAmbiguous || source.source !in setOf("ko", "ko-KR") || source.session <= 0 ||
                    source.observation <= 0 || source.lane.isBlank() || source.original.length !in 1..500 ||
                    !sourceTextValid(source.original) || key in seen || pending.size >= 3 || seen.size >= 128 ||
                    admittedChars + source.original.length > consent.maximumSourceChars) {
                    dropped++; notification = snapshot(DeferredTeacherPhase.REJECTED); false
                } else {
                    seen += key
                    admittedChars += source.original.length
                    pending.addLast(Pending(source, environment(), consent))
                    notification = snapshot(DeferredTeacherPhase.WAITING_IDLE); true
                }
            }
        }
        notification?.let(statusChanged)
        if (accepted) wake.trySend(Unit)
        return accepted
    }

    /** Production workflow keeps one already-admitted backend lease from ASR through teacher/local comparison. */
    suspend fun compareWhileHeld(source: DeferredTeacherSource, stillHeld: () -> Boolean): Boolean {
        if (!externallyHeldLocalLease || !stillHeld() || !offer(source)) return false
        val owner = requireNotNull(currentCoroutineContext()[Job])
        val work = synchronized(lock) {
            if (running?.isCompleted == false || !stillHeld() || !environment().mayWork()) null else {
                pending.firstOrNull()?.takeIf { it.source === source }?.also {
                    pending.removeFirst(); running = owner; runningWork = it
                }
            }
        } ?: return false
        try { runOne(work, stillHeld); return synchronized(lock) { phase == DeferredTeacherPhase.READY_FOR_REVIEW } }
        finally { synchronized(lock) { if (running === owner) { running = null; runningWork = null; releasing = false } } }
    }

    /** Stop/revoke wins; cancelled/unknown paid attempts are not refunded or retried. */
    fun stop() {
        val notification = synchronized(lock) {
            grant = null; pending.clear(); running?.cancel(); releasing = running?.isCompleted == false
            snapshot(DeferredTeacherPhase.STOPPED)
        }
        statusChanged(notification)
    }

    private suspend fun runOne(work: Pending, heldAdmission: () -> Boolean = { true }) {
        val idleFence = environment()
        fun allowed() = synchronized(lock) {
            val current = environment()
            !closed && heldAdmission() && grant === work.grant && grantCurrent(work.grant) &&
                work.captured.sameMaterials(current) && idleFence.inputEpoch == current.inputEpoch &&
                current.mayWork() && running?.isCancelled != true
        }
        fun storageAllowed() = synchronized(lock) {
            val current = environment()
            !closed && grant === work.grant && grantCurrent(work.grant) &&
                work.captured.sameMaterials(current) && idleFence.inputEpoch == current.inputEpoch && current.idle
        }
        try {
            if (!allowed()) return
            val reserved = synchronized(lock) {
                if (!allowed() || attempted >= work.grant.maximumRequests) null else {
                    attempted++ // Reserved before any callback/send, never refunded after unknown/cancelled dispatch.
                    snapshot(DeferredTeacherPhase.REQUESTING)
                }
            } ?: return
            statusChanged(reserved) // Outside lock: a reentrant stop must be observed before dispatch.
            currentCoroutineContext().ensureActive()
            if (!allowed()) return
            val batch = withTimeout(8_000) {
                currentCoroutineContext().ensureActive()
                if (!allowed()) throw CancellationException("Optional teacher authorization changed")
                teacher(work.source, work.grant, ::allowed)
            }
            currentCoroutineContext().ensureActive()
            if (!allowed() || batch.localRequestId.isBlank() || batch.original != work.source.original ||
                batch.source != work.source.source || batch.targets != work.grant.targets ||
                batch.translations.keys != work.grant.targets.toSet()) return
            val local = linkedMapOf<String, String>()
            for (target in work.grant.targets) {
                if (!allowed()) return
                statusChanged(synchronized(lock) { snapshot(DeferredTeacherPhase.COMPARING) })
                currentCoroutineContext().ensureActive()
                if (!allowed()) return
                val output = withTimeout(offlineTimeoutMillis) { offline(work.source, target, ::allowed) }
                currentCoroutineContext().ensureActive()
                val reference = batch.translations.getValue(target)
                if (!allowed() || !protectedPairAccepted(work.source.original, output, reference, target)) return
                local[target] = output
            }
            if (!allowed()) return
            // The caller may offer automatic checked examples; this never sets humanReviewed or writes a domain profile.
            offerForReview(work.source, batch, local, ::storageAllowed)
            val notification = synchronized(lock) { if (allowed()) snapshot(DeferredTeacherPhase.READY_FOR_REVIEW) else null }
            notification?.let(statusChanged)
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
        } catch (_: Exception) {
            // Fixed status below; never expose provider body or exception text.
        } finally {
            val notification = synchronized(lock) {
                if (!closed && grant === work.grant && phase in setOf(DeferredTeacherPhase.REQUESTING,
                        DeferredTeacherPhase.COMPARING)) snapshot(DeferredTeacherPhase.REJECTED) else null
            }
            notification?.let(statusChanged)
        }
    }

    /** Capture only; callbacks are always invoked by callers outside the admission lock. */
    private fun snapshot(nextPhase: DeferredTeacherPhase): DeferredTeacherStatus {
        phase = nextPhase
        return DeferredTeacherStatus(phase, attempted, pending.size, dropped)
    }
    override fun close() {
        synchronized(lock) { closed = true; grant = null; pending.clear(); running?.cancel() }
        watcher.cancel(); wake.close()
    }
}

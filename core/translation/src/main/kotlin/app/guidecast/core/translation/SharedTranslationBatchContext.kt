package app.guidecast.core.translation

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext

/** One input lifetime owns shared work; an individual language never owns its cancellation. */
class SharedTranslationBatchContext(
    private val requestScope: String,
    targets: List<String>,
    private val parentScope: CoroutineScope,
    private val isCurrent: () -> Boolean,
    private val capacity: Int = 32,
) : AbstractCoroutineContextElement(Key) {
    val targets: List<String> = java.util.Collections.unmodifiableList(targets.toList())
    val correlationId: String = java.util.UUID.randomUUID().toString()
    private data class Entry(val fingerprint: String, val result: Deferred<Map<String, String>>)
    private val entries = linkedMapOf<Long, Entry>()
    private var retiredThrough = -1L
    private data class SnapshotEntry(val fingerprint: String, val result: Deferred<Any>)
    private val snapshots = linkedMapOf<Long, SnapshotEntry>()
    private var snapshotRetiredThrough = -1L

    init {
        require(requestScope.isNotBlank())
        require(this.targets.size in 1..8 && this.targets.distinct().size == this.targets.size)
        require(this.targets.all { LANGUAGE_TAG.matches(it) })
        require(capacity in 1..64)
    }

    fun accepts(): Boolean = parentScope.coroutineContext[kotlinx.coroutines.Job]?.isActive != false && isCurrent()

    /** Freeze one immutable input snapshot before language workers or shared transport use it. */
    suspend fun <T : Any> freezeInputSnapshot(
        identity: TranslationRequestIdentity,
        fingerprint: String,
        capture: suspend () -> T,
    ): T {
        require(identity.scope == requestScope && fingerprint.length in 1..256)
        check(accepts()) { "Translation input lifetime ended" }
        val result = synchronized(snapshots) {
            snapshots[identity.sequence]?.also {
                check(it.fingerprint == fingerprint) { "Conflicting input snapshot identity" }
            }?.result ?: run {
                check(identity.sequence > snapshotRetiredThrough) { "Retired input snapshot cannot be recaptured" }
                if (snapshots.size >= capacity) {
                    val completed = snapshots.entries.filter { it.value.result.isCompleted }.minByOrNull { it.key }
                    check(completed != null) { "Input snapshot queue is full" }
                    snapshotRetiredThrough = maxOf(snapshotRetiredThrough, requireNotNull(completed).key)
                    snapshots.remove(completed.key)
                }
                val deferred = parentScope.async<Any>(start = CoroutineStart.LAZY) {
                    check(accepts()) { "Translation input lifetime ended" }
                    capture().also { check(accepts()) { "Translation input lifetime ended" } }
                }
                snapshots[identity.sequence] = SnapshotEntry(fingerprint, deferred)
                deferred
            }
        }
        result.start()
        val snapshot = result.await()
        currentCoroutineContext().ensureActive()
        check(accepts()) { "Translation input lifetime ended" }
        @Suppress("UNCHECKED_CAST")
        return snapshot as T
    }

    /** Failures remain cached too: no replay after timeout, cancellation or response loss. */
    suspend fun await(
        identity: TranslationRequestIdentity,
        fingerprint: String,
        produce: suspend () -> Map<String, String>,
    ): Map<String, String> {
        require(identity.scope == requestScope)
        require(fingerprint.length in 1..256)
        check(accepts()) { "Translation input lifetime ended" }
        val result = synchronized(entries) {
            entries[identity.sequence]?.also {
                check(it.fingerprint == fingerprint) { "Conflicting translation input identity" }
            }?.result ?: run {
                check(identity.sequence > retiredThrough) { "Retired translation input cannot be replayed" }
                if (entries.size >= capacity) {
                    val completed = entries.entries.filter { it.value.result.isCompleted }.minByOrNull { it.key }
                    check(completed != null) { "Shared translation queue is full" }
                    retiredThrough = maxOf(retiredThrough, requireNotNull(completed).key)
                    entries.remove(completed.key)
                }
                val deferred = parentScope.async(start = CoroutineStart.LAZY) {
                    check(accepts()) { "Translation input lifetime ended" }
                    val values = produce()
                    check(accepts()) { "Translation input lifetime ended" }
                    check(values.keys == targets.toSet()) { "Incomplete shared translation" }
                    java.util.Collections.unmodifiableMap(values.toMap())
                }
                entries[identity.sequence] = Entry(fingerprint, deferred)
                deferred
            }
        }
        result.start()
        val values = result.await()
        currentCoroutineContext().ensureActive()
        check(accepts()) { "Translation input lifetime ended" }
        return values
    }

    companion object Key : CoroutineContext.Key<SharedTranslationBatchContext>
}

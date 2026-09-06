package app.guidecast.provider.android.tts

import java.io.Closeable
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks the current engine generation per language while retaining an older, draining generation
 * until its last in-flight operation releases it.
 *
 * The registry deliberately removes deselected engines from [getOrCreate] before asking them to
 * retire. A replacement session can therefore never acquire a stale engine, while PCM already
 * being produced by the old session is not truncated by an eager `TextToSpeech.shutdown()`.
 */
internal class RetiringLanguageEngineRegistry<T : Any>(
    private val create: (languageTag: String, onDisposed: () -> Unit) -> T,
    private val retireWhenIdle: (T) -> Unit,
    private val forceClose: (T) -> Unit,
) {
    private val lock = Any()
    private val current = linkedMapOf<String, T>()
    private val all = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
    private val retainedByOwner = linkedMapOf<String, Set<String>>()

    fun getOrCreate(languageTag: String): T {
        require(languageTag.isNotBlank())
        synchronized(lock) {
            current[languageTag]?.let { return it }
            check(
                retainedByOwner.isEmpty() || retainedByOwner.values.any { languageTag in it },
            ) {
                "Android TTS language is outside the current retention generation: $languageTag"
            }
            var created: T? = null
            val engine = create(languageTag) {
                val disposed = checkNotNull(created) {
                    "Language engine was disposed before registry publication"
                }
                synchronized(lock) {
                    all.remove(disposed)
                    val iterator = current.entries.iterator()
                    while (iterator.hasNext()) {
                        if (iterator.next().value === disposed) iterator.remove()
                    }
                }
            }
            created = engine
            current[languageTag] = engine
            all += engine
            return engine
        }
    }

    fun reconcileLanguages(owner: String, retainedLanguageTags: Set<String>) {
        require(owner.isNotBlank())
        require(retainedLanguageTags.all(String::isNotBlank))
        reconcileLanguageOwners(mapOf(owner to retainedLanguageTags))
    }

    fun reconcileLanguageOwners(updates: Map<String, Set<String>>) {
        require(updates.isNotEmpty())
        require(updates.all { (owner, languages) ->
            owner.isNotBlank() && languages.all(String::isNotBlank)
        })
        val retiring = synchronized(lock) {
            updates.forEach { (owner, languages) ->
                if (languages.isEmpty()) {
                    retainedByOwner.remove(owner)
                } else {
                    retainedByOwner[owner] = languages.toSet()
                }
            }
            val retainedAcrossOwners = retainedByOwner.values.flatMapTo(hashSetOf()) { it }
            val removed = mutableListOf<T>()
            val iterator = current.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key !in retainedAcrossOwners) {
                    iterator.remove()
                    removed += entry.value
                }
            }
            removed
        }
        retiring.forEach(retireWhenIdle)
    }

    fun forEachCurrent(action: (String, T) -> Unit) {
        val snapshot = synchronized(lock) {
            current.toMap()
        }
        snapshot.forEach(action)
    }

    fun closeAll() {
        val snapshot = synchronized(lock) {
            current.clear()
            retainedByOwner.clear()
            all.toList().also { all.clear() }
        }
        snapshot.forEach(forceClose)
    }

    internal fun currentLanguageTags(): Set<String> =
        synchronized(lock) { current.keys.toSet() }

    internal fun retainedLanguageTags(): Set<String> =
        synchronized(lock) { retainedByOwner.values.flatMapTo(linkedSetOf()) { it } }
}

/**
 * Admission gate for one Android TTS engine.
 *
 * [retireWhenIdle] rejects new work immediately but defers resource disposal until existing
 * prepare/synthesis operations finish. [closeNow] is reserved for provider shutdown and aborts the
 * resource immediately. The disposal action is exactly-once across retire, close and lease races.
 */
internal class RetirableResourceLifecycle(
    private val dispose: () -> Unit,
) {
    private val lock = Any()
    private var accepting = true
    private var activeOperations = 0
    private var disposalStarted = false

    fun acquire(): Closeable {
        synchronized(lock) {
            check(accepting && !disposalStarted) { "Android TTS engine is retiring or closed" }
            activeOperations += 1
        }
        return OperationLease(::release)
    }

    fun retireWhenIdle() {
        val disposeNow = synchronized(lock) {
            accepting = false
            beginDisposalIfLocked(activeOperations == 0)
        }
        if (disposeNow) dispose()
    }

    fun closeNow() {
        val disposeNow = synchronized(lock) {
            accepting = false
            beginDisposalIfLocked(true)
        }
        if (disposeNow) dispose()
    }

    private fun release() {
        val disposeNow = synchronized(lock) {
            check(activeOperations > 0) { "Android TTS operation lease underflow" }
            activeOperations -= 1
            beginDisposalIfLocked(!accepting && activeOperations == 0)
        }
        if (disposeNow) dispose()
    }

    private fun beginDisposalIfLocked(condition: Boolean): Boolean {
        if (!condition || disposalStarted) return false
        disposalStarted = true
        return true
    }

    private class OperationLease(
        private val release: () -> Unit,
    ) : Closeable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }
}

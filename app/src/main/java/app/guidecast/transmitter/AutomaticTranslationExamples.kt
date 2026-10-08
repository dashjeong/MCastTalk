package app.guidecast.transmitter

import app.guidecast.core.translation.TranslationStyle
import app.guidecast.core.translation.requireProtectedTranslationMeaning
import java.security.MessageDigest
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock as withAdmissionLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Automatically checked examples are separate from human-reviewed domain materials. */
internal data class AutomaticTranslationExample(
    val comparison: ShadowComparison,
    val domainIdentity: String,
    val instructionsIdentity: String,
    val adoptionPolicyVersion: Int = AUTOMATIC_EXAMPLE_ADOPTION_POLICY_VERSION,
) {
    override fun toString() = "AutomaticTranslationExample(text=redacted)"
    val key: String get() = automaticExampleKey(comparison.original, comparison.contextBefore,
        comparison.source, comparison.target, comparison.style, domainIdentity, instructionsIdentity)
    internal val storageKey: String get() = key + ":" + automaticExampleHash(listOf(
        exactExampleText(comparison.online), exactExampleText(comparison.offline)).joinToString("\u0000"))
}

internal data class AutomaticExampleReviewSnapshot(
    val example: AutomaticTranslationExample,
    val currentDomain: Pair<Long, String>,
    val workLifetime: String,
    val deletionEpoch: Long,
) {
    // This revision binds a NEW human decision to current materials; the stored comparison remains unchanged.
    val comparison: ShadowComparison get() = example.comparison.copy(corpusRevision = currentDomain.first)
    override fun toString() = "AutomaticExampleReviewSnapshot(text=redacted)"
}

internal data class AutomaticExampleStatus(
    val enabled: Boolean = false, val ready: Boolean = false, val stored: Int = 0,
    val accepted: Long = 0, val reused: Long = 0, val rejected: Long = 0,
    val deferred: Long = 0, val message: String? = null, val reviewPending: Int = 0,
)

internal interface AutomaticExamplePersistence {
    suspend fun load(): List<AutomaticTranslationExample>
    /** Commit only while the originating opt-in, source and corpus still match. */
    suspend fun save(examples: List<AutomaticTranslationExample>, allowed: () -> Boolean): Boolean
    /** Durable adapters execute their final commit through this admission. Legacy adapters retain save compatibility. */
    suspend fun saveWithCommitAdmission(examples: List<AutomaticTranslationExample>, allowed: () -> Boolean,
        commitAdmission: ((() -> Boolean) -> Boolean)): Boolean = save(examples, allowed)
}

internal fun automaticExampleHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun automaticExampleInstructions(options: TranslationApiOptions): String = automaticExampleHash(
    listOf(options.interpretationMode.name, options.domainPrompt, options.interpreterInstructions).joinToString("\u0000"))

private fun exactExampleText(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFC)

internal fun automaticExampleKey(original: String, context: String?, source: String, target: String,
    style: TranslationStyle, domain: String, instructions: String): String = automaticExampleHash(listOf(
    exactExampleText(original), exactExampleText(context.orEmpty()), source.trim().lowercase(java.util.Locale.ROOT),
    target.trim().lowercase(java.util.Locale.ROOT), style.name, domain, instructions).joinToString("\u0000"))

/** Valid content may wait for direct review but never gains automatic-use authority from storage. */
internal fun automaticExampleReviewable(example: AutomaticTranslationExample): Boolean = runCatching {
    val c = example.comparison
    require(c.nativeIdentity == null) // Native captions without a directly bound text request are not evidence.
    require(listOf(example.domainIdentity, example.instructionsIdentity).all { it.matches(Regex("[a-f0-9]{64}")) })
    require(listOf(c.source, c.target).all { it.matches(Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8}){0,3}")) })
    require(!c.source.substringBefore('-').equals(c.target.substringBefore('-'), ignoreCase = true))
    require(c.style != TranslationStyle.AUTO) // A future context may choose a different register.
    require(c.contextBefore.orEmpty().length <= 1_000)
    require(listOf(c.original, c.online, c.offline).all { it.length in 1..500 })
    val texts = listOf(c.original, c.online, c.offline, c.contextBefore.orEmpty())
    require(texts.all { validContextUnicode(it) && !containsNativeContextCredentialLikeText(it) &&
        it.none { ch -> ch.code < 32 || ch.code == 127 } &&
        !Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|(?:\\+?\\d[ -]?){9,}").containsMatchIn(it) })
    require(!exactExampleText(c.online).equals(exactExampleText(c.original), ignoreCase = true))
    true
}.getOrDefault(false)

/** Finite equivalence/asset checks do not establish human review or general semantic accuracy. */
internal fun automaticExampleAccepted(example: AutomaticTranslationExample): Boolean = runCatching {
    val c = example.comparison
    require(example.adoptionPolicyVersion == AUTOMATIC_EXAMPLE_ADOPTION_POLICY_VERSION)
    require(automaticExampleReviewable(example))
    require(exactExampleText(c.online) != exactExampleText(c.offline))
    require(automaticExampleEquivalentRefinement(c.offline, c.online, c.target))
    require(conservativeReviewAccepted(c.original, c.offline, c.online, c.target))
    requireProtectedTranslationMeaning(c.original, c.online, c.source, c.target)
    true
}.getOrDefault(false)

/**
 * At most one active item and five pending items; validation/persistence run off the service path, only while idle.
 * Playback performs one bounded key hash and one immutable map lookup, with no disk or network.
 */
internal class AutomaticTranslationExamples(
    private val scope: CoroutineScope,
    private val persistence: AutomaticExamplePersistence,
    private val idle: () -> Boolean,
    private val currentDomain: () -> Pair<Long, String>,
    initialEnabled: Boolean = false,
    private val persistEnabled: (Boolean) -> Boolean = { true },
    private val onDisabled: () -> Unit = {},
    private val reviewLifetime: () -> String? = { null },
    reviewCommitLocks: List<Any> = emptyList(),
) {
    private val reviewLocks = reviewCommitLocks.toList()
    private val mutableState = MutableStateFlow(AutomaticExampleStatus(enabled = initialEnabled))
    val state = mutableState.asStateFlow()
    private val mutableReviewCandidates = MutableStateFlow<List<AutomaticTranslationExample>>(emptyList())
    val reviewCandidates = mutableReviewCandidates.asStateFlow()
    private val generation = AtomicLong()
    private val clearEpoch = AtomicLong()
    private val startupClearEpoch = clearEpoch.get()
    @Volatile private var storageReadSafe = false
    @Volatile private var clearing = false
    private val storageLock = Mutex()
    private val admissionLock = ReentrantLock()
    @Volatile private var index: Map<String, AutomaticTranslationExample> = emptyMap()
    @Volatile private var archived: Map<String, AutomaticTranslationExample> = emptyMap()
    private fun publishArchive(rows: Map<String, AutomaticTranslationExample>) {
        archived = rows
        index = rows.values.filter(::automaticExampleAccepted).associateBy { it.key }
        val review = rows.values.filter { automaticExampleReviewable(it) && !automaticExampleAccepted(it) }.toList()
        mutableReviewCandidates.value = review
        update { it.copy(stored = index.size, reviewPending = review.size) }
    }
    private data class Pending(val example: AutomaticTranslationExample, val generation: Long, val allowed: () -> Boolean)
    private val pending = Channel<Pending>(5)
    private fun update(block: (AutomaticExampleStatus) -> AutomaticExampleStatus) {
        mutableState.update(block)
    }
    init {
        scope.launch {
            try {
                storageLock.withLock {
                    val loaded = persistence.load().takeLast(MAX_EXAMPLES)
                    storageReadSafe = true
                    // An opt-in toggle cannot delete retained candidates; an explicit clear owns deletion.
                    if (!clearing && startupClearEpoch == clearEpoch.get()) publishArchive(loaded.associateBy { it.storageKey })
                }
                update { it.copy(ready = true, stored = index.size, message =
                    if (it.reviewPending > 0) "기존 기준 또는 내용이 달라진 예문은 직접 검수 대기입니다. 자동 적용하지 않습니다." else it.message) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { update { it.copy(ready = true, message = "저장 예문을 읽지 못했습니다. 기본 번역을 사용합니다.") } }
            for (item in pending) {
                fun allowed() = !clearing && state.value.enabled && generation.get() == item.generation && item.allowed() &&
                    currentDomain() == (item.example.comparison.corpusRevision to item.example.domainIdentity)
                try {
                    var waits = 0
                    while (allowed() && !idle() && waits++ < 600) delay(200)
                    if (!allowed() || !idle()) { reject("조건이 바뀌어 예문 저장을 건너뛰었습니다."); continue }
                    if (!automaticExampleReviewable(item.example)) { reject("보관 기준을 통과하지 못한 예문은 저장하지 않았습니다."); continue }
                    val autoAccepted = automaticExampleAccepted(item.example)
                    val stored = if (autoAccepted) item.example else item.example.copy(adoptionPolicyVersion = 0)
                    if (archived[stored.storageKey] == stored) continue
                    storageLock.withLock {
                        val next = LinkedHashMap(archived).apply {
                            if (autoAccepted) entries.removeAll { it.value.key == stored.key && automaticExampleAccepted(it.value) }
                            put(stored.storageKey, stored)
                            // Unreviewed/legacy rows are retained until an explicit deletion. Only old eligible rows may expire.
                            while (size > MAX_EXAMPLES) {
                                val oldestEligible = entries.firstOrNull { it.key != stored.storageKey && automaticExampleAccepted(it.value) }?.key ?: break
                                remove(oldestEligible)
                            }
                        }
                        if (next.size > MAX_EXAMPLES) { reject("검수 대기 보관함이 가득 찼습니다. 기존 예문을 유지하며 새 후보는 저장하지 않았습니다."); return@withLock }
                        if (allowed() && idle() && persistence.saveWithCommitAdmission(next.values.toList(), { allowed() && idle() }) { commit ->
                                admissionLock.withAdmissionLock { if (allowed() && idle()) commit() else false }
                            } && allowed() && idle()) {
                            publishArchive(next.toMap())
                            update { if (autoAccepted) it.copy(accepted = it.accepted + 1,
                                message = "표현 보존 검사 예문 저장됨 · 같은 원문·문맥에서 사용합니다.")
                                else it.copy(rejected = it.rejected + 1,
                                    message = "자동 적용하지 않고 직접 검수 대기로 보관했습니다.") }
                        } else reject("방송 또는 설정이 바뀌어 예문 저장을 보류했습니다.")
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { reject("예문 저장 실패 · 기본 번역을 유지합니다.") }
            }
        }
    }
    fun setEnabled(enabled: Boolean): Boolean {
        val changed = admissionLock.withAdmissionLock {
            if (!persistEnabled(enabled)) false else {
                generation.incrementAndGet()
                update { it.copy(enabled = enabled, message = if (enabled) "자동 검사 예문 사용 켜짐" else "자동 검사 예문 사용 꺼짐") }
                true
            }
        }
        if (changed && !enabled) onDisabled()
        return changed
    }
    /** The caller must provide a directly matched successful text request and its live admission. */
    fun offer(comparison: ShadowComparison, domainIdentity: String, instructionsIdentity: String, allowed: () -> Boolean): Boolean {
        val admittedGeneration = generation.get()
        if (clearing || !storageReadSafe || !state.value.enabled || !state.value.ready || !allowed()) return false
        val item = Pending(AutomaticTranslationExample(comparison, domainIdentity, instructionsIdentity), admittedGeneration, allowed)
        if (!admissionLock.tryLock()) {
            update { it.copy(rejected = it.rejected + 1, message = "예문 저장 중 · 방송은 계속됩니다.") }
            return false
        }
        val accepted = try {
            if (clearing || generation.get() != admittedGeneration || !state.value.enabled) return false
            pending.trySend(item).isSuccess
        } finally { admissionLock.unlock() }
        update { if (accepted) it.copy(deferred = it.deferred + 1, message = "방송이 끝나면 예문을 검사·저장합니다.")
            else it.copy(rejected = it.rejected + 1, message = "예문 대기열이 가득 찼습니다. 방송은 계속됩니다.") }
        return accepted
    }
    fun lookup(original: String, context: String?, source: String, target: String, style: TranslationStyle,
        corpusRevision: Long, instructionsIdentity: String): String? {
        if (clearing || !state.value.enabled || !state.value.ready || original.length > 500 || context.orEmpty().length > 1_000) return null
        val domain = currentDomain()
        if (domain.first != corpusRevision) return null
        val saved = index[automaticExampleKey(original, context, source, target, style, domain.second, instructionsIdentity)] ?: return null
        update { it.copy(reused = it.reused + 1) }
        return saved.comparison.online
    }
    /** Choosing a retained candidate never changes the stored example or gives it automatic-use authority. */
    fun captureReviewCandidate(example: AutomaticTranslationExample): AutomaticExampleReviewSnapshot? = admissionLock.withAdmissionLock {
        val lifetime = reviewLifetime() ?: return@withAdmissionLock null
        if (clearing || !state.value.ready || !idle() || archived[example.storageKey] != example ||
            !automaticExampleReviewable(example) || automaticExampleAccepted(example)) return@withAdmissionLock null
        AutomaticExampleReviewSnapshot(example, currentDomain(), lifetime, clearEpoch.get()).takeIf(::isReviewCandidateCurrent)
    }
    fun isReviewCandidateCurrent(snapshot: AutomaticExampleReviewSnapshot): Boolean =
        !clearing && state.value.ready && idle() && snapshot.workLifetime == reviewLifetime() &&
            snapshot.deletionEpoch == clearEpoch.get() && currentDomain() == snapshot.currentDomain &&
            archived[snapshot.example.storageKey] == snapshot.example
    fun reviewCommitAdmission(snapshot: AutomaticExampleReviewSnapshot) = NativeComparisonCommitAdmission(reviewLocks,
        atomicGate = { commit -> admissionLock.withAdmissionLock { commit() } }) { isReviewCandidateCurrent(snapshot) }

    fun clear() {
        val expected = admissionLock.withAdmissionLock {
            clearing = true
            generation.incrementAndGet()
            index = emptyMap()
            mutableReviewCandidates.value = emptyList()
            update { it.copy(stored = 0, reviewPending = 0, message = "자동 예문 삭제 중") }
            clearEpoch.incrementAndGet()
        }
        scope.launch {
            var saved: Boolean? = null
            try { saved = storageLock.withLock {
                // Turning the feature on/off cannot revoke an explicit deletion request.
                persistence.save(emptyList()) { true }.also { saved ->
                    if (saved) storageReadSafe = true
                    index = emptyMap(); archived = emptyMap(); mutableReviewCandidates.value = emptyList()
                }
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { saved = false }
            finally { admissionLock.withAdmissionLock {
                if (clearEpoch.get() == expected) {
                    clearing = false
                    saved?.let { completed -> update { it.copy(stored = index.size,
                        message = if (completed) "자동 예문을 삭제했습니다. 직접 검수한 자료는 유지합니다." else "예문 삭제를 완료하지 못했습니다. 다시 시도하세요.") } }
                }
            } }
        }
    }
    private fun reject(message: String) = update { it.copy(rejected = it.rejected + 1, message = message) }
    companion object { const val MAX_EXAMPLES = 512 }
}

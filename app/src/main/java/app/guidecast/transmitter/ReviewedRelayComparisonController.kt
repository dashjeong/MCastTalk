package app.guidecast.transmitter

import app.guidecast.core.translation.TranslationStyle
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal fun reviewedRelayExampleTextValid(text: String): Boolean = text.trim().let {
    it.length in 1..500 && validContextUnicode(it) && !containsNativeContextCredentialLikeText(it) &&
        it.none { character -> character.code < 32 || character.code == 127 }
}

internal fun reviewedRelayTranslationTextValid(text: String, target: String): Boolean =
    reviewedRelayExampleTextValid(text) && targetScriptMatches(text.trim(), target)

private fun reviewedRelayLanguageValid(tag: String): Boolean =
    tag.length in 2..35 && Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*").matches(tag)

internal enum class ReviewedRelayComparisonPhase {
    IDLE, WAITING_FOR_BROADCAST_END, COMPARING, READY, UNAVAILABLE, CANCELLED, FAILED,
}

internal data class ReviewedRelayComparisonAvailability(val available: Boolean, val message: String)

/** This local request number is an ownership token, never a provider input or response identity. */
internal data class ReviewedRelayComparisonRequest(
    val requestId: Long, val original: String, val translation: String,
    val source: String, val target: String, val style: TranslationStyle,
)

internal data class ReviewedRelayComparisonState(
    val phase: ReviewedRelayComparisonPhase = ReviewedRelayComparisonPhase.IDLE,
    val message: String = "원문과 번역을 직접 확인한 예문만 비교합니다. 모델 가중치 학습이 아닙니다.",
    val pending: ReviewedRelayComparisonRequest? = null,
    val comparison: ShadowComparison? = null,
)

internal data class ReviewedRelayComparisonEnvironment(
    val corpusRevision: Long, val modelId: String?, val preparationGeneration: Long,
    val inputEpoch: Long, val settingsRevision: Long, val controlGeneration: Long,
    val prepared: Boolean, val supported: Boolean, val broadcastActive: Boolean,
    val workActive: Boolean, val resourcesAvailable: Boolean,
) {
    fun sameMaterials(other: ReviewedRelayComparisonEnvironment): Boolean =
        corpusRevision == other.corpusRevision && modelId == other.modelId &&
            preparationGeneration == other.preparationGeneration && settingsRevision == other.settingsRevision &&
            controlGeneration == other.controlGeneration
    fun idleReady(): Boolean = prepared && supported && modelId != null && !broadcastActive &&
        !workActive && resourcesAvailable
}

/** Explicit human-confirmed references. Never uploads, prepares a model, or runs during broadcasting. */
internal class ReviewedRelayComparisonController(
    private val scope: CoroutineScope,
    private val environment: (source: String, target: String) -> ReviewedRelayComparisonEnvironment,
    private val compare: suspend (ReviewedRelayComparisonRequest, ReviewedRelayComparisonEnvironment, () -> Boolean) -> ShadowComparison?,
    private val commitLocks: List<Any> = emptyList(),
    private val budgetMillis: Long = 4_000,
) : Closeable {
    private val lock = Any()
    private val mutable = MutableStateFlow(ReviewedRelayComparisonState())
    val state = mutable.asStateFlow()
    private var next = 0L
    private var fence: ReviewedRelayComparisonEnvironment? = null
    private var job: Job? = null
    private var commitClaimed = false
    private var closed = false
    private val watcher = scope.launch {
        while (true) {
            val request = synchronized(lock) { mutable.value.pending }
            if (request != null) synchronized(lock) {
                val value = mutable.value
                if (value.pending === request) {
                    val current = environment(request.source, request.target)
                    val captured = fence
                    val runningOrReady = value.phase in setOf(ReviewedRelayComparisonPhase.COMPARING, ReviewedRelayComparisonPhase.READY)
                    if (captured != null && (!captured.sameMaterials(current) || !current.prepared || !current.supported ||
                            (runningOrReady && (captured.inputEpoch != current.inputEpoch || !current.idleReady())))) {
                        clearLocked(ReviewedRelayComparisonPhase.CANCELLED, "모델·자료·방송 상태가 바뀌어 예문 비교를 취소했습니다.")
                    }
                }
            }
            delay(250)
        }
    }
    init { require(budgetMillis in 100..5_000) }

    fun availability(source: String, target: String): ReviewedRelayComparisonAvailability {
        val releasing = synchronized(lock) { closed || (job?.isCompleted == false &&
            mutable.value.phase != ReviewedRelayComparisonPhase.COMPARING) }
        if (releasing) return ReviewedRelayComparisonAvailability(false,
            "이전 예문 비교를 정리 중입니다. 완료된 뒤 다시 비교하세요.")
        val current = environment(source, target)
        return when {
            !current.supported -> ReviewedRelayComparisonAvailability(false, "이 언어 쌍은 준비된 오프라인 모델에서 지원하지 않습니다.")
            !current.prepared || current.modelId == null -> ReviewedRelayComparisonAvailability(false, "오프라인 모델을 먼저 준비하세요. 예문 비교는 모델을 자동으로 불러오지 않습니다.")
            !current.resourcesAvailable -> ReviewedRelayComparisonAvailability(false, "기기의 열·메모리 여유가 생긴 뒤 비교하세요.")
            current.workActive -> ReviewedRelayComparisonAvailability(false, "다른 음성·파일·모델 작업이 끝난 뒤 비교하세요.")
            current.broadcastActive -> ReviewedRelayComparisonAvailability(true, "예문을 보관한 뒤 방송 종료 후 직접 비교를 시작하세요.")
            else -> ReviewedRelayComparisonAvailability(true, "준비된 오프라인 모델로 기기에서만 비교합니다.")
        }
    }

    fun captureApproval(source: String, target: String): ReviewedRelayComparisonEnvironment =
        synchronized(lock) { environment(source, target) }

    fun submit(original: String, translation: String, source: String, target: String,
        style: TranslationStyle, sameUtteranceConfirmed: Boolean,
        expectedEnvironment: ReviewedRelayComparisonEnvironment? = null): Boolean {
        if (!sameUtteranceConfirmed || !reviewedRelayExampleTextValid(original) ||
            !reviewedRelayTranslationTextValid(translation, target) || !reviewedRelayLanguageValid(source) || !reviewedRelayLanguageValid(target) ||
            source.equals(target, ignoreCase = true)) return false
        val decision = availability(source, target)
        if (!decision.available) {
            synchronized(lock) { if (!closed && mutable.value.phase !in setOf(ReviewedRelayComparisonPhase.COMPARING,
                    ReviewedRelayComparisonPhase.WAITING_FOR_BROADCAST_END))
                mutable.value = ReviewedRelayComparisonState(ReviewedRelayComparisonPhase.UNAVAILABLE, decision.message) }
            return false
        }
        synchronized(lock) {
            if (closed || job?.isCompleted == false || mutable.value.phase in setOf(ReviewedRelayComparisonPhase.COMPARING,
                    ReviewedRelayComparisonPhase.WAITING_FOR_BROADCAST_END)) return false
            val current = environment(source, target)
            if (expectedEnvironment != null && (!expectedEnvironment.sameMaterials(current) ||
                    expectedEnvironment.inputEpoch != current.inputEpoch)) return false
            if (!current.prepared || !current.supported || current.modelId == null || current.workActive || !current.resourcesAvailable) return false
            next = if (next == Long.MAX_VALUE) 1 else next + 1
            val request = ReviewedRelayComparisonRequest(next, original.trim(), translation.trim(), source, target, style)
            commitClaimed = false
            fence = current
            mutable.value = ReviewedRelayComparisonState(ReviewedRelayComparisonPhase.WAITING_FOR_BROADCAST_END,
                if (current.broadcastActive) "예문 1개 보관됨 · 방송 종료 후 비교를 시작하세요." else "직접 확인한 예문 비교 준비됨", request)
            if (current.broadcastActive) return true
        }
        return compareWaiting()
    }

    /** Queue retention never automatically starts inference when a broadcast ends. */
    fun compareWaiting(): Boolean {
        val launch: Job
        synchronized(lock) {
            val value = mutable.value
            val request = value.pending ?: return false
            if (closed || job?.isCompleted == false || value.phase != ReviewedRelayComparisonPhase.WAITING_FOR_BROADCAST_END) return false
            val current = environment(request.source, request.target)
            val approved = fence ?: return false
            if (!approved.sameMaterials(current) || !current.prepared || !current.supported) {
                clearLocked(ReviewedRelayComparisonPhase.CANCELLED, "모델·자료가 바뀌었습니다. 예문을 다시 확인하세요.")
                return false
            }
            if (!current.idleReady()) return false
            fence = current
            mutable.value = value.copy(phase = ReviewedRelayComparisonPhase.COMPARING,
                message = "직접 확인한 예문을 오프라인 모델과 비교 중 · 추가 API 요청 없음")
            launch = scope.launch(start = CoroutineStart.LAZY) {
                fun allowed() = synchronized(lock) { ownedAndCurrentLocked(request, current) }
                try {
                    val result = withTimeout(budgetMillis) { compare(request, current, ::allowed) }
                    currentCoroutineContext().ensureActive()
                    synchronized(lock) {
                        if (!ownedAndCurrentLocked(request, current)) return@synchronized
                        if (result == null || result.original != request.original || result.online != request.translation ||
                            result.source != request.source || result.target != request.target || result.style != request.style ||
                            result.corpusRevision != current.corpusRevision || result.nativeIdentity != null ||
                            result.offlineModel == null || !reviewedRelayTranslationTextValid(result.offline, request.target)) {
                            clearLocked(ReviewedRelayComparisonPhase.FAILED, "준비된 모델로 비교하지 못했습니다. 모델 상태를 확인하세요.")
                        } else mutable.value = mutable.value.copy(phase = ReviewedRelayComparisonPhase.READY,
                            message = "비교 완료 · 다시 검수한 번역만 기기에 저장·재사용할 수 있습니다.", comparison = result)
                    }
                } catch (_: TimeoutCancellationException) {
                    synchronized(lock) { if (mutable.value.pending === request)
                        clearLocked(ReviewedRelayComparisonPhase.FAILED, "예문 비교 시간이 초과됐습니다. 방송에는 적용하지 않았습니다.") }
                } catch (cancelled: CancellationException) {
                    synchronized(lock) { if (mutable.value.pending === request)
                        clearLocked(ReviewedRelayComparisonPhase.CANCELLED, "예문 비교를 취소했습니다.") }
                    throw cancelled
                } catch (_: Exception) {
                    synchronized(lock) { if (mutable.value.pending === request)
                        clearLocked(ReviewedRelayComparisonPhase.FAILED, "예문 비교에 실패했습니다. 방송에는 적용하지 않았습니다.") }
                }
            }
            job = launch
        }
        launch.start()
        return true
    }

    private fun ownedAndCurrentLocked(request: ReviewedRelayComparisonRequest, captured: ReviewedRelayComparisonEnvironment): Boolean {
        if (closed || mutable.value.pending !== request) return false
        val current = environment(request.source, request.target)
        return current.idleReady() && captured.sameMaterials(current) && captured.inputEpoch == current.inputEpoch
    }

    fun isCurrent(comparison: ShadowComparison): Boolean = synchronized(lock) {
        val value = mutable.value
        val request = value.pending
        val captured = fence
        !commitClaimed && reviewedRelayTranslationTextValid(comparison.online, comparison.target) &&
            reviewedRelayTranslationTextValid(comparison.offline, comparison.target) && value.phase == ReviewedRelayComparisonPhase.READY && value.comparison === comparison && request != null &&
            captured != null && ownedAndCurrentLocked(request, captured)
    }

    fun commitAdmission(comparison: ShadowComparison) = NativeComparisonCommitAdmission(listOf(lock) + commitLocks) {
        if (!isCurrent(comparison)) false else { commitClaimed = true; true }
    }

    private fun clearLocked(phase: ReviewedRelayComparisonPhase, message: String) {
        // Cancellation clears the draft immediately, but the inference owner remains until its
        // coroutine (including non-cancellable teardown) has completed. New submissions cannot overlap it.
        job?.cancel(); fence = null; commitClaimed = false
        mutable.value = ReviewedRelayComparisonState(phase, message)
    }
    fun cancel() = synchronized(lock) { clearLocked(ReviewedRelayComparisonPhase.CANCELLED, "예문 비교와 대기를 취소했습니다.") }
    override fun close() {
        synchronized(lock) { closed = true; clearLocked(ReviewedRelayComparisonPhase.CANCELLED, "예문 비교를 종료했습니다.") }
        watcher.cancel()
    }
}

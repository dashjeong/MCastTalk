package app.guidecast.transmitter

import app.guidecast.core.translation.BoundedShadowRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable

/** Provider identities establish correspondence; caption proximity never does. */
internal data class NativeLearningPair(val sequence: Long, val inputId: String, val responseId: String,
    val original: String, val translation: String, val corpusRevision: Long, val controlGeneration: Long)

private val nativeLearningIdentityPattern = Regex("[A-Za-z0-9_-]+")
internal fun nativeLearningHasConfirmedPair(event: OpenAiAudioEvent): Boolean =
    event.finished && event.sourceFinal && event.translationFinal && !event.interrupted &&
        !event.sourceFailed && !event.sourceExpired && event.status == "completed" &&
        event.inputSequence?.let { it > 0 } == true &&
        listOf(event.inputId, event.responseId.orEmpty()).all { it.length in 1..200 && nativeLearningIdentityPattern.matches(it) }

internal enum class NativeLearningPause(val label: String) {
    OFF("비교 꺼짐"), ALIGNMENT("이 모델의 원문·통역 대응 ID를 확인할 수 없어 비교 보류"),
    WAITING("확정 원문·통역 쌍 대기"), PREPARATION("오프라인 번역 모델을 먼저 준비하세요 · 중계는 계속됩니다"),
    RESOURCES("열·메모리 여유가 부족해 비교 보류 · 중계는 계속됩니다"),
    BUSY("다른 파일·음성·모델 작업 중이라 비교 보류 · 중계는 계속됩니다"),
    QUEUE("비교 대기열이 가득 차 이번 예문은 건너뜀"), INVALID("불완전하거나 검수 범위를 벗어난 예문 제외"),
    CHANGED("설정·자료·연결이 바뀌어 이전 비교 폐기"), FAILED("오프라인 비교 실패·시간 초과 · 중계는 계속됩니다"),
    RESTART("비교 설정 변경은 다음 중계 시작부터 적용됩니다. 현재 음성 중계는 계속됩니다."),
    INPUT_BOUNDARY("이미 시작된 입력은 비교하지 않습니다. 비교를 사용하려면 중계를 다시 시작하세요."),
    ENDED("중계가 끝나 미검수 비교 예문을 비웠습니다"),
}

internal class NativeLearningMonitor {
    internal val reviewAdmissionLock = Any()
    private var owner: Long? = null
    private var ownerCurrent: () -> Boolean = { false }
    private var newestSessionId = Long.MIN_VALUE
    private val mutable = MutableStateFlow(ShadowComparisonStatus())
    val state = mutable.asStateFlow()
    fun begin(sessionId: Long, reason: NativeLearningPause, generation: Long? = null,
        current: () -> Boolean = { true }) {
        synchronized(reviewAdmissionLock) { synchronized(this) {
            if (sessionId < newestSessionId) return
            newestSessionId = sessionId
            owner = sessionId; ownerCurrent = current
            mutable.value = ShadowComparisonStatus(lastPause = reason.label, nativeControlGeneration = generation)
        } }
    }
    @Synchronized fun ownsCandidate(candidate: ShadowComparison): Boolean =
        owner == candidate.nativeIdentity?.sessionId && ownerCurrent() && mutable.value.last === candidate
    @Synchronized fun update(sessionId: Long, change: (ShadowComparisonStatus) -> ShadowComparisonStatus) {
        if (owner == sessionId) mutable.value = change(mutable.value)
    }
    fun end(sessionId: Long) {
        synchronized(reviewAdmissionLock) { synchronized(this) {
            if (owner == sessionId) {
                val status = mutable.value
                mutable.value = status.copy(incomplete = maxOf(status.incomplete, status.attempted - status.completed),
                    last = null, lastPause = NativeLearningPause.ENDED.label)
                owner = null; ownerCurrent = { false }
            }
        } }
    }
}

/** One running comparison and one waiting pair; it never requests an online answer or publishes audio. */
internal class NativeLearningSession(
    scope: CoroutineScope,
    private val sessionId: Long,
    private val monitor: NativeLearningMonitor,
    private val enabled: () -> Boolean,
    private val authorized: () -> Boolean,
    private val isCurrent: () -> Boolean,
    private val controlGeneration: () -> Long,
    private val corpusRevision: () -> Long,
    private val readiness: () -> NativeLearningPause?,
    private val captureStartsAfterAdmission: Boolean = true,
    private val compare: suspend (NativeLearningPair) -> ShadowComparison,
) : Closeable {
    private val enabledAtSessionStart = enabled()
    private val initialGeneration = controlGeneration()
    private fun inputAdmissionCurrent() = captureStartsAfterAdmission && enabledAtSessionStart &&
        enabled() && controlGeneration() == initialGeneration
    private fun unavailableInputReason() = if (!enabled()) NativeLearningPause.OFF else
        if (!captureStartsAfterAdmission) NativeLearningPause.INPUT_BOUNDARY else NativeLearningPause.RESTART
    private val runner = BoundedShadowRunner(scope, 4_000)
    private data class Admission(val inputId: String, val enabled: Boolean, val generation: Long, val revision: Long,
        var consumed: Boolean = false)
    private val admissions = linkedMapOf<Long, Admission>()
    private var expiredThrough = Long.MIN_VALUE
    @Volatile private var closed = false
    private var observedGeneration = controlGeneration()
    private var observedRevision = corpusRevision()
    @Volatile private var cachedReadiness: NativeLearningPause? = null
    private val watcher = scope.launch(start = CoroutineStart.LAZY) {
        var ticks = 0
        while (isActive && !closed) {
            if (!authorized() || !isCurrent()) { close(); break }
            if (!inputAdmissionCurrent()) cachedReadiness = null
            else if (ticks++ % 2 == 0) cachedReadiness = try { readiness() } catch (_: Exception) { NativeLearningPause.RESOURCES }
            val generation = controlGeneration()
            val revision = corpusRevision()
            if (generation != observedGeneration || revision != observedRevision || !authorized() || !isCurrent()) {
                observedGeneration = generation; observedRevision = revision
                monitor.update(sessionId) {
                    if (it.last?.corpusRevision == revision && it.last.nativeIdentity?.controlGeneration == generation) it
                    else it.copy(last = null, lastPause = NativeLearningPause.CHANGED.label)
                }
            }
            if (!inputAdmissionCurrent()) monitor.update(sessionId) { it.copy(last = null, lastPause = unavailableInputReason().label) }
            else cachedReadiness?.let { pause -> monitor.update(sessionId) { it.copy(lastPause = pause.label) } }
            delay(250)
        }
    }
    init {
        monitor.begin(sessionId, if (inputAdmissionCurrent()) NativeLearningPause.WAITING else unavailableInputReason(), initialGeneration) {
            !closed && isCurrent() && authorized() && inputAdmissionCurrent()
        }
        watcher.start()
    }

    @Synchronized fun accept(event: OpenAiAudioEvent) {
        if (closed || !isCurrent() || !authorized()) return
        if (!inputAdmissionCurrent()) {
            monitor.update(sessionId) { it.copy(last = null, lastPause = unavailableInputReason().label) }
            return
        }
        val sequence = event.inputSequence?.takeIf { it > 0 } ?: return
        if (sequence <= expiredThrough || event.inputId.length !in 1..200 || !nativeLearningIdentityPattern.matches(event.inputId)) return
        val admission = admissions.getOrPut(sequence) {
            Admission(event.inputId, enabled(), controlGeneration(), corpusRevision())
        }
        while (admissions.size > 128) {
            val oldest = admissions.keys.min(); admissions.remove(oldest); expiredThrough = maxOf(expiredThrough, oldest)
        }
        if (event.interrupted || event.sourceFailed || event.sourceExpired ||
            (event.finished && event.status != "completed")) {
            if (!admission.consumed && admission.enabled && enabled()) skip(NativeLearningPause.INVALID)
            admission.consumed = true
            return
        }
        if (!nativeLearningHasConfirmedPair(event)) return
        if (admission.consumed) return
        admission.consumed = true
        if (!admission.enabled || !enabled()) return
        if (admission.inputId != event.inputId || admission.generation != controlGeneration() || admission.revision != corpusRevision()) {
            skip(NativeLearningPause.CHANGED); return
        }
        val pause = cachedReadiness
        if (pause != null) { skip(pause); return }
        if (listOf(event.source, event.translation).any { text ->
                text.isBlank() || text.length > DomainCorpusFormat.MAX_TEXT_LENGTH || !validContextUnicode(text) ||
                    containsNativeContextCredentialLikeText(text) || text.any { it.code < 32 || it.code == 127 }
            }) { skip(NativeLearningPause.INVALID); return }
        val pair = NativeLearningPair(sequence, event.inputId, requireNotNull(event.responseId), event.source,
            event.translation, admission.revision, admission.generation)
        fun allowed() = !closed && isCurrent() && authorized() && inputAdmissionCurrent() && cachedReadiness == null &&
            controlGeneration() == pair.controlGeneration && corpusRevision() == pair.corpusRevision
        val accepted = runner.offer(::allowed) {
            val preflight = try { readiness() } catch (_: Exception) { NativeLearningPause.RESOURCES }
            cachedReadiness = preflight
            if (preflight != null) { skip(preflight); return@offer }
            if (!allowed()) { skip(NativeLearningPause.CHANGED); return@offer }
            monitor.update(sessionId) { it.copy(attempted = it.attempted + 1, lastPause = null) }
            var complete = false
            try {
                val result = compare(pair)
                currentCoroutineContext().ensureActive()
                val identity = result.nativeIdentity
                if (allowed() && result.original == pair.original && result.online == pair.translation &&
                    result.corpusRevision == pair.corpusRevision && identity?.sessionId == sessionId &&
                    identity.inputId == pair.inputId && identity.responseId == pair.responseId &&
                    identity.inputSequence == pair.sequence && identity.controlGeneration == pair.controlGeneration &&
                    result.offline.isNotBlank() && result.offline.length <= DomainCorpusFormat.MAX_TEXT_LENGTH &&
                    validContextUnicode(result.offline) && !containsNativeContextCredentialLikeText(result.offline)) {
                    monitor.update(sessionId) { it.copy(completed = it.completed + 1, last = result, lastPause = null) }
                    complete = true
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Comparison failure cannot stop the audio session. */ }
            finally {
                if (!complete) monitor.update(sessionId) { it.copy(incomplete = it.incomplete + 1,
                    lastPause = if (allowed()) NativeLearningPause.FAILED.label else NativeLearningPause.CHANGED.label) }
            }
        }
        if (!accepted) skip(NativeLearningPause.QUEUE)
    }

    private fun skip(reason: NativeLearningPause) = monitor.update(sessionId) {
        it.copy(skipped = it.skipped + 1, lastPause = reason.label)
    }
    override fun close() {
        closed = true
        runner.close(); watcher.cancel(); monitor.end(sessionId)
    }
}

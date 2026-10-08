package app.guidecast.transmitter

import android.content.Context
import android.os.SystemClock
import app.guidecast.core.stream.RecordedBroadcast
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal interface DeferredTeacherLocalLease { suspend fun close() }
internal data class DeferredNativeTeacherWorkflowStatus(val message: String = "방송 후 예문 비교 꺼짐",
    val sourceAccepted: Int = 0, val sourceRejected: Int = 0)

/** One opt-in input interval is admitted only before capture. It never observes the hot PCM loop. */
internal class DeferredNativeTeacherWorkflow(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: TranslationApiSettings,
    private val environment: () -> DeferredTeacherFence,
    private val archive: (String) -> RecordedBroadcast?,
    private val flushArchive: () -> Unit,
    private val localLease: suspend (() -> Boolean) -> DeferredTeacherLocalLease?,
    private val comparisons: DeferredNativeTeacherComparison,
    // Existing-file warm-up only after broadcast EOF; never called from capture or the PCM callback.
    private val prepareExistingModel: suspend (List<String>, () -> Boolean) -> Boolean = { _, _ -> environment().prepared },
) : Closeable {
    private data class Work(val permit: DeferredGoogleTeacherPermit, val admission: DeferredSourceArchiveAdmission,
        val apiRevision: Long, val corpusRevision: Long, val activityLifetime: String)
    private val lock = Any()
    private val mutable = MutableStateFlow(DeferredNativeTeacherWorkflowStatus())
    val state = mutable.asStateFlow()
    private var capture: Work? = null
    private val waiting = ArrayDeque<Work>()
    private val ended = linkedSetOf<String>()
    private var acceptedIntervals = 0
    private var generation = Long.MIN_VALUE
    private var job: Job? = null
    private var currentWork: Work? = null
    private var closed = false
    private val watcher = scope.launch {
        while (isActive) {
            val running = synchronized(lock) { job to currentWork }
            if (running.first?.isCompleted == false && running.second?.let(::valid) != true) running.first?.cancel()
            synchronized(lock) {
                if (job?.isCompleted == true) { job = null; currentWork = null }
                val next = waiting.firstOrNull()
                if (next != null && (!settings.deferredTeacherAuthorized(next.permit) ||
                        environment().apiRevision != next.apiRevision || environment().corpusRevision != next.corpusRevision ||
                        environment().activityLifetime != next.activityLifetime)) {
                    waiting.removeFirst()
                    mutable.value = state.value.copy(sourceRejected = state.value.sourceRejected + 1,
                        message = "동의·자료가 바뀐 원음은 비교하지 않습니다.")
                }
                if (!closed && job == null && next != null && waiting.firstOrNull() === next && next.admission.recordingId in ended &&
                    environment().let { it.idle && it.resourcesAvailable }) {
                    waiting.removeFirst(); currentWork = next
                    job = launch { transcribe(next) }
                }
            }
            delay(200)
        }
    }

    /** UI calls this only after separate text/cost consent has produced the exact settings permit. */
    fun begin(permit: DeferredGoogleTeacherPermit): Boolean = synchronized(lock) {
        if (closed || job?.isCompleted == false || permit.generation <= generation ||
            !settings.deferredTeacherAuthorized(permit) || !comparisons.begin(permit.comparisonGrant())) return false
        generation = permit.generation; acceptedIntervals = 0; capture = null; waiting.clear(); ended.clear()
        mutable.value = DeferredNativeTeacherWorkflowStatus("다음 마이크 입력의 원음만 방송 종료 후 비교합니다.")
        true
    }

    /** Called inside the input coroutine, before its first AudioRecord frame, not on the PCM callback. */
    suspend fun beforeCapture(recordingId: String?, part: Long?, inputEpoch: Long, selectedSource: String) {
        if (selectedSource !in setOf("ko", "ko-KR") || recordingId == null || part == null ||
            !recordingId.matches(Regex("[a-fA-F0-9-]{36}")) || part <= 0) return
        val permit = settings.deferredTeacher.value ?: return
        val captured = environment()
        val admitted = synchronized(lock) { !closed && generation == permit.generation && acceptedIntervals < 3 && capture == null }
        if (!admitted || !settings.deferredTeacherAuthorized(permit)) return
        val offset = withContext(Dispatchers.IO) {
            val root = File(context.noBackupFilesDir, "broadcast-recordings").canonicalFile
            val folder = File(root, recordingId)
            check(folder.canonicalFile == folder.absoluteFile && folder.parentFile == root)
            val index = File(folder, "$part-source.index")
            if (index.exists()) { check(index.isFile && index.canonicalFile == index.absoluteFile && index.length() % 32 == 0L); index.length() }
            else 0L
        }
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            if (!closed && generation == permit.generation && capture == null && acceptedIntervals < 3 &&
                settings.deferredTeacherAuthorized(permit) && environment().inputEpoch == inputEpoch) {
                capture = Work(permit, DeferredSourceArchiveAdmission(recordingId, part, inputEpoch,
                    permit.generation, offset, maxOf(permit.grantedAtElapsedNanos, SystemClock.elapsedRealtimeNanos()), 0),
                    captured.apiRevision, captured.corpusRevision, captured.activityLifetime)
                acceptedIntervals++
            }
        }
    }

    /** Input OFF bounds the consent window. Failure is excluded even if its recording is later finished. */
    fun inputStopped(successful: Boolean) = synchronized(lock) {
        val work = capture ?: return
        capture = null
        if (!successful || !settings.deferredTeacherAuthorized(work.permit)) {
            mutable.value = state.value.copy(sourceRejected = state.value.sourceRejected + 1,
                message = "중단되거나 동의가 바뀐 원음은 비교하지 않습니다."); return
        }
        if (waiting.size < 3) waiting.addLast(work.copy(admission = work.admission.copy(inputStoppedNanos = SystemClock.elapsedRealtimeNanos())))
    }
    fun broadcastEnded(recordingId: String?, successful: Boolean) = synchronized(lock) {
        if (recordingId == null) return
        if (successful) ended += recordingId else waiting.removeAll { it.admission.recordingId == recordingId }
        while (ended.size > 3) ended.remove(ended.first())
        mutable.value = state.value.copy(message = if (successful)
            "방송 종료됨 · 준비된 오프라인 모델과 여유가 있으면 원음을 비교합니다." else "종료 실패 원음은 비교하지 않습니다.")
    }
    private fun valid(work: Work): Boolean {
        val current = environment()
        return !closed && settings.deferredTeacherAuthorized(work.permit) && current.idle && current.resourcesAvailable &&
            current.apiRevision == work.apiRevision && current.corpusRevision == work.corpusRevision &&
            current.activityLifetime == work.activityLifetime
    }
    private suspend fun transcribe(work: Work) {
        val start = environment()
        fun allowed() = valid(work) && environment().inputEpoch == start.inputEpoch &&
            environment().modelGeneration == start.modelGeneration
        var lease: DeferredTeacherLocalLease? = null
        try {
            if (!allowed()) return
            lease = localLease(::allowed) ?: return
            if (!allowed()) return
            if (!environment().prepared) {
                mutable.value = state.value.copy(message = "방송 종료됨 · 설치·검증된 오프라인 모델을 한 번 준비합니다.")
                if (!prepareExistingModel(work.permit.targets, ::allowed) || !allowed() || !environment().prepared) {
                    mutable.value = state.value.copy(sourceRejected = state.value.sourceRejected + 1,
                        message = "설치·검증된 오프라인 모델을 준비하지 못해 추가 API 요청 없이 비교를 보류했습니다.")
                    return
                }
            }
            fun preparedAllowed() = allowed() && environment().mayWork()
            if (!preparedAllowed()) return
            withContext(Dispatchers.IO) { check(preparedAllowed()); flushArchive(); check(preparedAllowed()) }
            val recording = withContext(Dispatchers.IO) { if (preparedAllowed()) archive(work.admission.recordingId) else null } ?: return
            val result = deferredArchivedKoreanTranscript(context, recording, work.admission, ::preparedAllowed) ?: run {
                mutable.value = state.value.copy(message = "설치된 한국어 음성 인식이 준비되지 않아 비교를 보류했습니다."); return
            }
            check(preparedAllowed())
            // ASR collection has closed. Retain the backend lease until every teacher/local stage unwinds.
            for (value in result.finalized) {
                check(preparedAllowed() && value.isFinal && !value.isRetracted)
                val source = DeferredTeacherSource(work.permit.generation, "archive-local-asr:${work.admission.part}:${work.admission.inputEpoch}", value.sequence + 1,
                    value.sourceLanguageTag, value.text.trim(), originalCompletionObserved = true)
                if (comparisons.compareWhileHeld(source) { lease != null && preparedAllowed() }) mutable.value = state.value.copy(sourceAccepted = state.value.sourceAccepted + 1)
                else mutable.value = state.value.copy(sourceRejected = state.value.sourceRejected + 1)
            }
            mutable.value = state.value.copy(message = "기기에서 확정한 원문을 별도 문장 API와 비교합니다. Live 출력과 대응하는 쌍은 아닙니다.")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.value = state.value.copy(sourceRejected = state.value.sourceRejected + 1,
            message = "확정된 원문을 만들지 못해 비교를 건너뛰었습니다. 방송 기록은 유지됩니다.") }
        finally { withContext(NonCancellable) { lease?.close() } }
    }
    fun stop() {
        synchronized(lock) { capture = null; waiting.clear(); ended.clear(); job?.cancel() }
        comparisons.stop(); settings.endDeferredTeacher()
        mutable.value = state.value.copy(message = "추가 예문 비교를 중지했습니다.")
    }
    override fun close() { synchronized(lock) { closed = true }; stop(); watcher.cancel() }
}

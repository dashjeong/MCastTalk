package app.guidecast.transmitter

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class VoiceNoteUiState(
    val library: List<VoiceNote> = emptyList(),
    val selected: VoiceNote? = null,
    val recording: Boolean = false,
    val busy: Boolean = false,
    val elapsedMs: Long = 0,
    val message: String? = null,
    val unavailable: Boolean = false,
    val playback: FileAudioPlaybackState = FileAudioPlaybackState(),
)

internal class VoiceNoteViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    val editorDraft = VoiceNoteEditorDraft()
    private val app = application as GuideCastApplication
    private val repository = VoiceNoteRepository(File(app.filesDir, "voice-notes"))
    private val mutableState = MutableStateFlow(VoiceNoteUiState())
    val state = mutableState.asStateFlow()
    private var work: Job? = null
    private val stopRequested = AtomicBoolean(false)
    private var playWhenPrepared = false
    private var seekWhenPrepared: Long? = null
    private var pendingExport: VoiceNoteExportRequest? = null
    private val audio = FileAudioPlayback(app, viewModelScope, ::onAudioState)
    private fun onAudioState(value: FileAudioPlaybackState) {
        mutableState.update { it.copy(playback = value) }
        updateOwnership()
        if (value.prepared && playWhenPrepared) {
            playWhenPrepared = false
            val position = seekWhenPrepared
            seekWhenPrepared = null
            if (position != null) audio.seek(position)
            audio.play()
        }
    }
    init {
        viewModelScope.launch { refresh() }
        viewModelScope.launch {
            combine(app.broadcastRuntime.state, app.localFileWorkActive, app.localModelWorkActive) { runtime, file, model ->
                runtime.dataTransferUnavailable() || file || model
            }.distinctUntilChanged().collect { unavailable ->
                mutableState.update { it.copy(unavailable = unavailable) }
                if (unavailable) {
                    stopRequested.set(true); playWhenPrepared = false; seekWhenPrepared = null
                    if (!state.value.recording) work?.cancel()
                    audio.pause()
                }
            }
        }
    }
    private fun updateOwnership() {
        app.localVoiceNoteWorkActive.value = state.value.recording || state.value.busy || state.value.playback.isPlaying
    }
    private fun canWork(): Boolean = !state.value.recording && !state.value.busy &&
        !app.broadcastRuntime.state.value.dataTransferUnavailable() && !app.localFileWorkActive.value && !app.localModelWorkActive.value
    private suspend fun refresh() {
        val notes = withContext(Dispatchers.IO) { repository.list() }
        mutableState.update { it.copy(library = notes) }
    }
    fun permissionDenied() { mutableState.update { it.copy(message = "마이크 권한을 허용한 뒤 다시 시작하세요.") } }

    fun record(title: String, source: String?, target: String) {
        if (!canWork()) return
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionDenied(); return
        }
        if (source != null && source !in VOICE_NOTE_LANGUAGES || target !in VOICE_NOTE_LANGUAGES) return
        audio.close(); stopRequested.set(false)
        mutableState.update { it.copy(recording = true, elapsedMs = 0, selected = null, message = "녹음 중 · 종료하면 원음을 저장합니다.") }
        updateOwnership()
        work = viewModelScope.launch(Dispatchers.IO) {
            var note: VoiceNote? = null
            var recorder: AudioRecord? = null
            var duration = 0L
            var finalized = false
            var message = "녹음을 저장했습니다. 받아쓰기를 시작할 수 있습니다."
            try {
                note = repository.create(title, source, target)
                val minimum = AudioRecord.getMinBufferSize(VOICE_NOTE_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0)
                @Suppress("MissingPermission")
                val candidate = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(VOICE_NOTE_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(minimum, 16_000)).build()
                recorder = candidate
                check(candidate.state == AudioRecord.STATE_INITIALIZED)
                VoiceNoteWav(repository.audio(note.id)).use { wav ->
                    candidate.startRecording()
                    check(candidate.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                    val buffer = ByteArray(3_200)
                    var lastCheckpoint = 0L
                    var lastRead = android.os.SystemClock.elapsedRealtime()
                    while (!stopRequested.get() && wav.byteCount < VOICE_NOTE_MAX_BYTES) {
                        currentCoroutineContext().ensureActive()
                        val count = candidate.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                        check(count >= 0)
                        if (count == 0) {
                            check(android.os.SystemClock.elapsedRealtime() - lastRead < 5_000)
                            delay(10); continue
                        }
                        lastRead = android.os.SystemClock.elapsedRealtime()
                        wav.append(buffer, minOf(count.toLong(), VOICE_NOTE_MAX_BYTES - wav.byteCount).toInt())
                        duration = wav.byteCount / 32
                        if (duration / 250 != state.value.elapsedMs / 250) {
                            mutableState.update { it.copy(elapsedMs = duration) }
                        }
                        if (duration - lastCheckpoint >= 1_000) {
                            wav.checkpoint(); lastCheckpoint = duration
                            check(candidate.activeRecordingConfiguration?.isClientSilenced != true)
                            check(repository.audio(note.id).usableSpace >= 8L * 1024 * 1024)
                        }
                    }
                    if (wav.byteCount >= VOICE_NOTE_MAX_BYTES) message = "60분 녹음 한도에 도달해 저장했습니다. 새 노트에서 계속 녹음하세요."
                }
                finalized = true
            } catch (_: CancellationException) { message = "녹음을 중지하고 저장된 음성을 보관했습니다." }
            catch (_: Exception) { message = "녹음이 중단됐습니다. 마이크 사용 상태와 저장 공간을 확인하세요. 저장된 음성은 보관합니다." }
            finally {
                runCatching { recorder?.stop() }; runCatching { recorder?.release() }
                val saved = note?.copy(durationMs = duration, interrupted = !finalized, notice = message)
                val persisted = saved?.let { runCatching { repository.save(it); it }.getOrNull() }
                if (note != null && persisted == null) message = "녹음 설명을 저장하지 못했습니다. 저장 공간을 확보한 뒤 보관함의 중단된 녹음을 복구하세요."
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    mutableState.update { it.copy(recording = false, selected = persisted, message = message) }
                    updateOwnership()
                    runCatching { refresh() }
                }
            }
        }
    }
    fun stopRecording() { stopRequested.set(true) }
    fun refreshLibrary() = task { refresh() }

    fun open(id: String) = task {
        playWhenPrepared = false
        audio.close()
        val note = withContext(Dispatchers.IO) { repository.load(id) }
        mutableState.update { it.copy(selected = note, message = note.notice) }
    }
    fun recover() {
        val id = state.value.selected?.id ?: return
        task {
            val note = withContext(Dispatchers.IO) {
                try { repository.recover(id) }
                catch (_: IllegalArgumentException) {
                    throw FileTranscriptionException("복구할 수 없는 녹음 형식입니다. 원본 파일은 변경하지 않았습니다.")
                }
            }
            mutableState.update { it.copy(selected = note, message = note.notice) }; refresh()
        }
    }
    fun transcribe(source: String?, target: String) {
        val selected = state.value.selected ?: return
        if (selected.interrupted || source != null && source !in VOICE_NOTE_LANGUAGES || target !in VOICE_NOTE_LANGUAGES) return
        if (Build.VERSION.SDK_INT < 33) { mutableState.update { it.copy(message = "받아쓰기는 Android 13 이상에서 지원합니다. 녹음 재생·내려받기는 사용할 수 있습니다.") }; return }
        task {
            audio.close()
            val result = FileSpeechTranscriber.transcribe(app, Uri.fromFile(repository.audio(selected.id)), source, VOICE_NOTE_LANGUAGES.keys.toList()) { progress ->
                mutableState.update { it.copy(message = "받아쓰기 ${voiceNoteTime(progress.processedMs)} · ${progress.segmentCount}개 구간") }
            }
            if (result.segments.isEmpty()) throw FileTranscriptionException("인식된 음성이 없습니다. 원음을 재생하고 말하는 언어를 확인한 뒤 다시 시도하세요.")
            if (result.segments.size > 2_000) throw FileTranscriptionException("받아쓰기 구간이 2,000개를 넘었습니다. 짧은 녹음으로 나눠 주세요. 원음은 보관했습니다.")
            val updated = selected.copy(sourceLanguage = source, targetLanguage = target,
                lines = result.voiceNoteLines(source),
                notice = result.warnings.joinToString("\n").ifBlank { null })
            // Commit the source before any translation/model preparation can fail.
            saveSelected(updated)
            translateSaved(updated, target)
        }
    }
    fun translateRemaining(target: String) {
        val note = state.value.selected ?: return
        if (note.lines.isEmpty() || target !in VOICE_NOTE_LANGUAGES) return
        task { audio.pause(); translateSaved(note, target) }
    }
    private suspend fun translateSaved(note: VoiceNote, target: String) {
        mutableState.update { it.copy(message = "원문 저장 완료 · 기기 내 번역 준비 중 (최초 사용 시 모델 다운로드)") }
        try {
            var qualityNotes = emptyList<String>()
            val updated = translateVoiceNote(note, target, ::saveSelected) { pending, onLine ->
                val entry = FileLibraryEntry(note.id, note.title, "", note.id, note.durationMs,
                    note.sourceLanguage, note.createdAt,
                    pending.mapIndexed { index, line -> FileSpeechSegment(index.toLong(), line.startMs, line.endMs, line.original, line.language) })
                val result = translateFileScript(app, entry, target, FileTranslationEngine.MLKIT,
                    allowCloudReview = false, onLine = onLine) { done, total ->
                    mutableState.update { it.copy(message = if (target != note.targetLanguage)
                        "새 언어 번역 $done / $total · 모두 완료할 때까지 기존 번역을 보관합니다."
                        else "남은 구간 번역 $done / $total · 완료 구간은 순차 저장합니다.") }
                }
                qualityNotes = result.notes
            }
            saveSelected(updated.copy(notice = (updated.notice.orEmpty().lines() + qualityNotes).filter { it.isNotBlank() }.distinct().joinToString("\n")))
            mutableState.update { it.copy(message = "원문과 번역을 저장했습니다. 문장 수정과 원음 대조를 할 수 있습니다.") }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutableState.update { it.copy(message = "번역을 완료하지 못했습니다. 녹음·원문과 저장된 번역은 유지됩니다. 인터넷·저장 공간·언어 설정을 확인한 뒤 ‘남은 구간 번역’으로 이어가세요.") }
        }
    }
    private suspend fun saveSelected(note: VoiceNote) {
        withContext(Dispatchers.IO) { repository.save(note) }
        mutableState.update { current -> current.copy(
            selected = if (current.selected?.id == note.id) note else current.selected,
            library = (current.library.filterNot { it.id == note.id } + note.copy(lines = emptyList())).sortedByDescending { it.createdAt },
        ) }
    }
    fun rename(title: String, onSaved: () -> Unit = {}) {
        val note = state.value.selected ?: return
        val clean = title.trim().replace('\n', ' ').replace('\r', ' ')
        if (clean.isBlank() || clean.length > 120) return
        task { saveSelected(note.copy(title = clean)); mutableState.update { it.copy(message = "노트 제목을 저장했습니다.") }; onSaved() }
    }
    fun editLine(index: Int, original: String, translation: String, translationEdited: Boolean, onSaved: () -> Unit = {}) {
        val note = state.value.selected ?: return
        if (index !in note.lines.indices) return
        task {
            val line = note.lines[index].corrected(original, translation, translationEdited)
            saveSelected(note.copy(lines = note.lines.mapIndexed { i, old -> if (i == index) line else old }))
            mutableState.update { it.copy(message = if (line.translation.isBlank()) "수정한 원문을 저장했습니다. ‘남은 구간 번역’으로 새 번역을 만들 수 있습니다." else "수정한 문장을 저장했습니다. 내려받기에도 반영됩니다.") }
            onSaved()
        }
    }
    fun setSpeaker(index: Int, label: String, onSaved: () -> Unit = {}) {
        val note = state.value.selected ?: return
        if (index !in note.lines.indices) return
        task {
            saveSelected(note.copy(lines = note.lines.mapIndexed { i, line -> if (i == index) line.copy(speaker = label.trim().take(80)) else line }))
            mutableState.update { it.copy(message = "화자 이름을 저장했습니다.") }
            onSaved()
        }
    }
    fun delete(id: String) = task {
        audio.close(); withContext(Dispatchers.IO) { repository.delete(id) }
        mutableState.update { it.copy(selected = if (it.selected?.id == id) null else it.selected, message = "음성노트를 삭제했습니다.") }; refresh()
    }
    fun prepareExport(kind: String, options: VoiceNoteExportOptions = VoiceNoteExportOptions()): Boolean {
        val note = state.value.selected ?: return false
        if (!canWork() || kind !in setOf("txt", "md", "srt", "json", "wav")) return false
        if (kind != "wav" && voiceNoteMatchingLines(note.lines, options.search.orEmpty()).isEmpty()) return false
        // Retain the chosen note and options while the system picker changes Activity state.
        pendingExport = VoiceNoteExportRequest(note, kind, options)
        savedState["voice_note_export"] = pendingExport!!.savedRequest()
        return true
    }
    fun exportPrepared(uri: Uri?, kind: String) {
        val memoryRequest = pendingExport
        val savedRequest = savedState.remove<android.os.Bundle>("voice_note_export")
        pendingExport = null
        if (uri == null) return
        if (memoryRequest == null && savedRequest == null) {
            mutableState.update { it.copy(message = "내려받기 준비가 초기화됐습니다. 노트를 열고 저장 위치를 다시 선택하세요.") }
            return
        }
        if (!canWork()) {
            mutableState.update { it.copy(message = "진행 중인 음성 작업을 마친 뒤 다시 내려받으세요. 선택한 위치에는 아직 내용을 쓰지 않았습니다.") }
            return
        }
        task {
            val request = memoryRequest ?: withContext(Dispatchers.IO) {
                restoreVoiceNoteExport(requireNotNull(savedRequest), repository::load)
            }
            if (request.kind != kind) throw FileTranscriptionException("내려받기 형식이 변경됐습니다. 저장 위치를 다시 선택하세요.")
            try { withContext(Dispatchers.IO) {
                val job = currentCoroutineContext()
                app.contentResolver.openOutputStream(uri, "wt").use { output ->
                    checkNotNull(output)
                    if (kind == "wav") repository.audio(request.note.id).inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            job.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    } else output.bufferedWriter(Charsets.UTF_8).use { writer ->
                        writeVoiceNoteExport(writer, request.note, kind, request.options) { job.ensureActive() }
                    }
                }
            } } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(message = "내려받기를 중지했습니다. 저장 위치에 미완성 파일이 남을 수 있습니다. 노트 원본은 유지됩니다.") }
                return@task
            } catch (_: Exception) {
                throw FileTranscriptionException("내려받기를 완료하지 못했습니다. 저장 공간·접근 권한을 확인하고 다시 저장하세요. 미완성 파일이 남을 수 있으며 노트 원본은 유지됩니다.")
            }
            mutableState.update { it.copy(message = "선택한 위치에 ${kind.uppercase()} 파일을 저장했습니다.") }
        }
    }
    fun playPause() {
        if (!canWork()) return
        val note = state.value.selected ?: return
        if (note.interrupted) return
        if (state.value.playback.prepared) audio.playPause()
        else preparePlayback(note, 0)
    }
    fun playFrom(ms: Long) {
        if (!canWork()) return
        val note = state.value.selected ?: return
        if (note.interrupted) return
        if (state.value.playback.prepared) { audio.seek(ms); audio.play() }
        else preparePlayback(note, ms)
    }
    private fun preparePlayback(note: VoiceNote, positionMs: Long) {
        task {
            playWhenPrepared = true
            seekWhenPrepared = positionMs
            try {
                withContext(Dispatchers.IO) {
                    ParcelFileDescriptor.open(repository.audio(note.id), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        withContext(Dispatchers.Main) { audio.prepare(descriptor) }
                    }
                }
            } catch (error: Throwable) {
                playWhenPrepared = false; seekWhenPrepared = null
                throw error
            }
        }
    }
    fun seek(ms: Long) { if (canWork()) audio.seek(ms) }
    fun skipPlayback(deltaMs: Long) { if (canWork()) audio.seek(state.value.playback.positionMs + deltaMs.coerceIn(-10_000, 10_000)) }
    fun setPlaybackSpeed(speed: Float) { if (canWork() && speed in listOf(0.75f, 1f, 1.25f, 1.5f)) audio.setSpeed(speed) }
    fun cancel() { if (state.value.recording) stopRecording() else work?.cancel() }
    fun pauseForBackground() { stopRecording(); playWhenPrepared = false; seekWhenPrepared = null; audio.pause(); if (!state.value.recording) work?.cancel() }
    fun leave() { pauseForBackground(); audio.close(); mutableState.update { it.copy(selected = null) } }
    private fun task(block: suspend () -> Unit) {
        if (!canWork()) return
        mutableState.update { it.copy(busy = true, message = null) }; updateOwnership()
        work = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { mutableState.update { it.copy(message = "작업을 중지했습니다. 저장된 녹음과 원문은 유지됩니다.") }; throw cancelled }
            catch (error: Exception) { mutableState.update { it.copy(message = if (error is FileTranscriptionException) error.message else "작업을 완료하지 못했습니다. 저장 공간·오프라인 언어팩·녹음 파일을 확인하세요.") } }
            finally { mutableState.update { it.copy(busy = false) }; updateOwnership() }
        }
    }
    override fun onCleared() { stopRecording(); work?.cancel(); audio.close(); app.localVoiceNoteWorkActive.value = false; super.onCleared() }
}

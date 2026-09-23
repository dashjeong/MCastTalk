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

internal class VoiceNoteViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as GuideCastApplication
    private val repository = VoiceNoteRepository(File(app.filesDir, "voice-notes"))
    private val mutableState = MutableStateFlow(VoiceNoteUiState())
    val state = mutableState.asStateFlow()
    private var work: Job? = null
    private val stopRequested = AtomicBoolean(false)
    private var playWhenPrepared = false
    private val audio = FileAudioPlayback(app, viewModelScope, ::onAudioState)
    private fun onAudioState(value: FileAudioPlaybackState) {
        mutableState.update { it.copy(playback = value) }
        updateOwnership()
        if (value.prepared && playWhenPrepared) { playWhenPrepared = false; audio.play() }
    }
    init {
        viewModelScope.launch { refresh() }
        viewModelScope.launch {
            combine(app.broadcastRuntime.state, app.localFileWorkActive, app.localModelWorkActive) { runtime, file, model ->
                runtime.dataTransferUnavailable() || file || model
            }.distinctUntilChanged().collect { unavailable ->
                mutableState.update { it.copy(unavailable = unavailable) }
                if (unavailable) { stopRequested.set(true); if (!state.value.recording) work?.cancel(); audio.pause() }
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
                        mutableState.update { it.copy(elapsedMs = duration) }
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

    fun open(id: String) = task {
        playWhenPrepared = false
        audio.close()
        val note = withContext(Dispatchers.IO) { repository.load(id) }
        mutableState.update { it.copy(selected = note, message = note.notice) }
    }
    fun recover() {
        val id = state.value.selected?.id ?: return
        task {
            val note = withContext(Dispatchers.IO) { repository.recover(id) }
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
            var updated = selected.copy(sourceLanguage = source, targetLanguage = target,
                lines = result.segments.map { VoiceNoteLine(it.startMs, it.endMs, it.text, it.languageTag) },
                notice = result.warnings.joinToString("\n").ifBlank { null })
            // Commit the source before any translation/model preparation can fail.
            saveSelected(updated)
            mutableState.update { it.copy(message = "원문 저장 완료 · 기기 내 번역 중 (최초 사용 시 모델 다운로드)") }
            try {
                val entry = FileLibraryEntry(selected.id, selected.title, "", selected.id, result.durationMs,
                    result.sourceLanguageTag, selected.createdAt, result.segments)
                val translated = translateFileScript(app, entry, target, FileTranslationEngine.MLKIT, allowCloudReview = false) { done, total ->
                    mutableState.update { it.copy(message = "기기 내 번역 $done / $total") }
                }
                updated = updated.copy(lines = updated.lines.mapIndexed { index, line -> line.copy(translation = translated.lines[index]) },
                    notice = (result.warnings + translated.notes).joinToString("\n"))
                saveSelected(updated)
                mutableState.update { it.copy(message = "받아쓰기와 번역을 저장했습니다.") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(message = "원문과 녹음은 저장했습니다. 번역 모델을 준비한 뒤 다시 받아쓰기를 실행하세요.") } }
        }
    }
    private suspend fun saveSelected(note: VoiceNote) {
        withContext(Dispatchers.IO) { repository.save(note) }
        mutableState.update { it.copy(selected = note) }; refresh()
    }
    fun setSpeaker(index: Int, label: String) {
        val note = state.value.selected ?: return
        if (index !in note.lines.indices) return
        task { saveSelected(note.copy(lines = note.lines.mapIndexed { i, line -> if (i == index) line.copy(speaker = label.trim().take(80)) else line })) }
    }
    fun delete(id: String) = task {
        audio.close(); withContext(Dispatchers.IO) { repository.delete(id) }
        mutableState.update { it.copy(selected = null, message = "음성노트를 삭제했습니다.") }; refresh()
    }
    fun export(uri: Uri, kind: String) {
        val note = state.value.selected ?: return
        task {
            withContext(Dispatchers.IO) {
                require(kind in setOf("txt", "srt", "wav"))
                app.contentResolver.openOutputStream(uri, "wt").use { output ->
                    checkNotNull(output)
                    if (kind == "wav") repository.audio(note.id).inputStream().use { it.copyTo(output) }
                    else output.write(voiceNoteTranscript(note.title, note.lines, kind == "srt").toByteArray(Charsets.UTF_8))
                }
            }
            mutableState.update { it.copy(message = "선택한 위치에 ${kind.uppercase()} 파일을 저장했습니다.") }
        }
    }
    fun playPause() {
        if (!canWork()) return
        val note = state.value.selected ?: return
        if (note.interrupted) return
        if (state.value.playback.prepared) audio.playPause()
        else runCatching {
            playWhenPrepared = true
            ParcelFileDescriptor.open(repository.audio(note.id), ParcelFileDescriptor.MODE_READ_ONLY).use(audio::prepare)
        }.onFailure { mutableState.update { it.copy(message = "녹음 파일을 열 수 없습니다.") } }
    }
    fun seek(ms: Long) { if (canWork()) audio.seek(ms) }
    fun cancel() { if (state.value.recording) stopRecording() else work?.cancel() }
    fun pauseForBackground() { stopRecording(); playWhenPrepared = false; audio.pause(); if (!state.value.recording) work?.cancel() }
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

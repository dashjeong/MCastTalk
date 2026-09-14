package app.guidecast.transmitter

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Build
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class FileTranslationViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as GuideCastApplication
    private val library = FileTranscriptLibrary(app)
    private val mutableUi = MutableStateFlow(FileTranslationUiState(
        automaticLanguageSupported = Build.VERSION.SDK_INT >= 34,
        fileTranscriptionSupported = Build.VERSION.SDK_INT >= 33,
    ))
    val uiState = mutableUi.asStateFlow()
    private val mutablePlayback = MutableStateFlow<FilePlaybackUiState?>(null)
    val playbackState = mutablePlayback.asStateFlow()
    private var selectedUri: Uri? = null
    private var selectedBatch: List<Uri> = emptyList()
    private var work: Job? = null
    private var fileLoad: Job? = null
    private var request = 0L
    private var relinkId: String? = null
    private val audio = FileAudioPlayback(app, viewModelScope) { update ->
        mutablePlayback.update { old -> old?.copy(positionMs = update.positionMs,
            isPlaying = update.isPlaying, isPrepared = update.prepared, speed = update.speed, repeat = update.repeat, errorMessage = update.error,
            statusMessage = if (!update.prepared && update.error == null) "오디오 준비 중" else old.statusMessage?.takeUnless { it == "오디오 준비 중" }) }
    }

    init {
        viewModelScope.launch {
            combine(mutableUi, mutablePlayback) { ui, playback ->
                ui.isConverting || ui.isLoading || playback?.isPlaying == true || playback?.isTranslating == true
            }.distinctUntilChanged().collect { app.localFileWorkActive.value = it }
        }
        viewModelScope.launch { refreshLibrary() }
        viewModelScope.launch {
            app.broadcastRuntime.state.map { runtime ->
                runtime.translationTestActive || runtime.phase in setOf(BroadcastPhase.STARTING, BroadcastPhase.LIVE, BroadcastPhase.PAUSED) ||
                    runtime.inputPhase in setOf(InputPhase.STARTING, InputPhase.ACTIVE, InputPhase.PAUSED)
            }.distinctUntilChanged().collect { busy ->
                mutableUi.update { it.copy(unavailableReason = if (busy) "입력·방송·실시간 통역을 중지한 뒤 파일을 변환하세요." else null) }
                if (busy) {
                    if (work?.isActive == true) cancel("실시간 입력·방송이 시작되어 파일 변환을 취소했습니다. 저장된 스크립트는 유지됩니다.")
                    audio.pause()
                }
            }
        }
    }

    fun selectSource(tag: String?) {
        if (isBusy()) return
        if (tag == null && !mutableUi.value.automaticLanguageSupported) {
            mutableUi.update { it.copy(errorMessage = "이 Android 버전에서는 원문 언어를 직접 선택하세요.") }
            return
        }
        mutableUi.update { it.copy(sourceLanguageTag = tag, errorMessage = null, selectedFiles = requeueCompleted(it.selectedFiles)) }
    }
    fun selectEngine(engine: FileTranslationEngine) { if (!isBusy()) mutableUi.update { it.copy(translationEngine = engine, selectedFiles = requeueCompleted(it.selectedFiles)) } }
    fun toggleTarget(tag: String) {
        if (isBusy()) return
        mutableUi.update { state ->
            val selected = if (tag in state.targetLanguageTags) state.targetLanguageTags - tag
                else if (state.targetLanguageTags.size < 4) state.targetLanguageTags + tag else state.targetLanguageTags
            state.copy(targetLanguageTags = selected, selectedFiles = if (selected != state.targetLanguageTags) requeueCompleted(state.selectedFiles) else state.selectedFiles)
        }
    }
    fun prepareRelink() { relinkId = mutablePlayback.value?.entry?.id }
    fun reportSpeechPermissionDenied() {
        mutableUi.update { it.copy(errorMessage = "Android 음성 인식 서비스를 사용하려면 마이크 권한이 필요합니다. 권한을 허용한 뒤 변환을 다시 누르세요.") }
    }
    fun cancelPicker() { relinkId = null }
    fun selectFiles(uris: List<Uri>) { selectBatch { uris.distinct() } }
    fun selectFolder(uri: Uri) {
        if (isBusy()) return
        runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        selectBatch { audioDocumentsInFolder(app, uri) }
    }
    private fun selectBatch(findFiles: suspend () -> List<Uri>) {
        if (isBusy()) return
        val generation = ++request
        fileLoad?.cancel()
        mutableUi.update { it.copy(isLoading = true, errorMessage = null, progressMessage = "파일 목록을 확인하고 있습니다.") }
        fileLoad = viewModelScope.launch {
            try {
                val uris = findFiles()
                if (uris.size > MAX_FILE_BATCH_SIZE) throw FileTranscriptionException("한 번에 최대 500개 파일을 선택할 수 있습니다.")
                if (uris.isEmpty()) return@launch
                val items = withContext(Dispatchers.IO) { uris.map { uri ->
                    currentCoroutineContext().ensureActive()
                    runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    FileConversionItem(uri.toString(), documentName(uri))
                } }
                selectedBatch = uris
                selectedUri = uris.first()
                mutableUi.update { it.copy(selectedFiles = items, selectedFileName = if (items.size == 1) items.first().displayName else "${items.size}개 파일",
                    selectedFileSha256 = null, detectedLanguageTag = null, languageNotice = null,
                    progressMessage = if (it.sourceLanguageTag != null) "선택한 원문 언어를 모든 파일에 적용합니다. 번역할 언어를 선택한 뒤 변환하세요."
                        else if (it.automaticLanguageSupported) "원문 언어는 파일별로 감지합니다. 번역할 언어를 선택한 뒤 변환하세요."
                        else "원문 언어를 직접 선택한 뒤 변환하세요.") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableUi.update { it.copy(errorMessage = fileTranscriptionUserMessage(error)) } }
            finally { if (generation == request) mutableUi.update { it.copy(isLoading = false) } }
        }
    }

    private fun updateQueue(uri: Uri, status: FileConversionStatus, message: String, hash: String? = null) {
        mutableUi.update { state -> state.copy(selectedFiles = state.selectedFiles.map { item ->
            if (item.uri == uri.toString()) item.copy(status = status, message = message, sha256 = hash ?: item.sha256) else item
        }) }
    }
    fun selectFile(uri: Uri) {
        if (isBusy()) return
        if (relinkId == null) { selectFiles(listOf(uri)); return }
        val expectedId = relinkId; relinkId = null
        val generation = ++request
        fileLoad?.cancel()
        mutableUi.update { it.copy(isLoading = true, errorMessage = null, progressMessage = "파일 해시 확인 중") }
        fileLoad = viewModelScope.launch {
            try {
                val details = inspectFile(uri)
                if (expectedId != null && details.hash != expectedId) {
                    mutablePlayback.update { it?.copy(errorMessage = "파일 해시가 다릅니다. 이 스크립트를 만든 원본 파일을 선택하세요.") }
                    error("선택한 파일의 해시가 기존 스크립트와 다릅니다.")
                }
                runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                selectedUri = uri
                selectedBatch = listOf(uri)
                val existing = withContext(Dispatchers.IO) { library.load(details.hash) }
                if (existing != null) {
                    withContext(Dispatchers.IO) { library.relink(existing.id, details.hash, uri.toString(), details.name) }
                    refreshLibrary()
                }
                mutableUi.update { it.copy(selectedFileName = details.name, selectedFileSha256 = details.hash,
                    detectedLanguageTag = existing?.sourceLanguageTag,
                    progressMessage = if (existing == null) "변환할 준비가 됐습니다." else "같은 파일의 저장된 스크립트를 찾았습니다. 재생 목록에서 열 수 있습니다.") }
                if (expectedId != null) openEntry(expectedId)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (generation == request) mutableUi.update { it.copy(errorMessage = if (expectedId != null) "원본 파일과 해시가 일치하는지 확인하세요." else "파일을 읽을 수 없습니다. 파일 앱에서 접근 가능한 오디오를 선택하세요.") }
            } finally { if (generation == request) mutableUi.update { it.copy(isLoading = false) } }
        }
    }

    fun convert(onlyFailed: Boolean = false, uriToRetry: String? = null) {
        if (isBusy()) return
        fileConversionPreflight(mutableUi.value)?.let { reason ->
            mutableUi.update { it.copy(errorMessage = reason) }
            return
        }
        val selected = selectedBatch.ifEmpty { listOfNotNull(selectedUri) }
        val batch = selected.filter { uri ->
            val item = mutableUi.value.selectedFiles.firstOrNull { it.uri == uri.toString() }
            when {
                uriToRetry != null -> uri.toString() == uriToRetry && item?.let(::retryableFileItem) == true
                onlyFailed -> item?.let(::retryableFileItem) == true
                else -> item?.status != FileConversionStatus.SAVED
            }
        }
        if (batch.isEmpty()) return
        val settings = mutableUi.value
        audio.pause()
        mutableUi.update { it.copy(isConverting = true, progress = null, errorMessage = null, progressMessage = "음성 인식 준비 중") }
        work = viewModelScope.launch {
            try {
              for ((fileIndex, uri) in batch.withIndex()) {
                currentCoroutineContext().ensureActive()
                try {
                updateQueue(uri, FileConversionStatus.READING, "원본 확인 중")
                val details = inspectFile(uri)
                val hash = details.hash
                val name = details.name
                updateQueue(uri, FileConversionStatus.CONVERTING, "음성 인식 중", hash)
                val existing = withContext(Dispatchers.IO) { library.load(hash) }
                val reusable = existing?.takeIf { saved -> settings.sourceLanguageTag == null || saved.sourceLanguageTag?.substringBefore('-') == settings.sourceLanguageTag.substringBefore('-') }
                val result = if (reusable != null) FileTranscriptionResult(reusable.segments, reusable.sourceLanguageTag, reusable.qualityNotes, reusable.durationMs)
                else FileSpeechTranscriber.transcribe(app, uri, settings.sourceLanguageTag) { progress ->
                    mutableUi.update { it.copy(progress = progress.durationMs?.takeIf { total -> total > 0 }?.let { total -> (progress.processedMs.toFloat() / total).coerceIn(0f, 1f) }, progressMessage = "${fileIndex + 1}/${batch.size} · ${progress.message}") }
                }
                val notes = inspectFileTranscript(result.segments, result.durationMs) + result.warnings
                // Rechecking avoids pairing a completed script with a document changed during recognition.
                check(inspectFile(uri).hash == hash) { "File changed" }
                var entry = FileLibraryEntry(hash, name, uri.toString(), hash, result.durationMs,
                    result.sourceLanguageTag, reusable?.createdAtMillis ?: System.currentTimeMillis(), result.segments,
                    translations = reusable?.translations.orEmpty(), qualityNotes = notes.distinct(), translationModes = reusable?.translationModes.orEmpty())
                withContext(Dispatchers.IO) { library.save(entry) }
                mutableUi.update { it.copy(detectedLanguageTag = result.sourceLanguageTag, languageNotice = result.warnings.joinToString(" · ").ifBlank { null }) }
                // Each target commits separately so a failed language cannot discard the source or other translations.
                for (target in settings.targetLanguageTags) {
                    if (!shouldTranslateFileTarget(entry, target, settings.translationEngine,
                            retryOnly = onlyFailed || uriToRetry != null)) continue
                    try {
                        val translated = translateFileScript(app, entry, target, settings.translationEngine) { done, total ->
                            mutableUi.update { it.copy(progressMessage = "번역·자동 검사 $done / $total", progress = done.toFloat() / total) }
                        }
                        val updated = entry.copy(translations = entry.translations + (target to translated.lines), translationModes = entry.translationModes + (target to translated.engine), qualityNotes = (entry.qualityNotes.filterNot { it.startsWith("$target 번역을 완료하지 못했습니다.") } + translated.notes).distinct())
                        withContext(Dispatchers.IO) { library.save(updated) }
                        entry = updated
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        entry = entry.copy(qualityNotes = (entry.qualityNotes + "$target 번역을 완료하지 못했습니다. 재생 화면에서 다시 요청할 수 있습니다.").distinct())
                        withContext(Dispatchers.IO) { library.save(entry) }
                    }
                }
                val missingTargets = settings.targetLanguageTags.filter { shouldTranslateFileTarget(entry, it, settings.translationEngine, retryOnly = true) }
                updateQueue(uri, if (missingTargets.isEmpty()) FileConversionStatus.SAVED else FileConversionStatus.PARTIAL,
                    if (missingTargets.isEmpty()) "스크립트 저장 완료" else "원문 저장 완료 · 미완료 번역은 재작업할 수 있습니다.", hash)
                refreshLibrary()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { updateQueue(uri, FileConversionStatus.FAILED, fileTranscriptionUserMessage(error)) }
              }
                val completed = mutableUi.value.selectedFiles.count { it.status == FileConversionStatus.SAVED }
                val failed = mutableUi.value.selectedFiles.count(::retryableFileItem)
                mutableUi.update { it.copy(progress = 1f, progressMessage = "${completed}개 완료 · ${failed}개 재작업 가능. 완료된 결과는 파일별로 보관했습니다.") }
            } catch (cancelled: CancellationException) {
                mutableUi.update { it.copy(progressMessage = "파일 변환을 취소했습니다. 완료되어 저장된 결과는 보관함에 남습니다.",
                    selectedFiles = it.selectedFiles.map { item -> if (item.status in setOf(FileConversionStatus.READY, FileConversionStatus.READING, FileConversionStatus.CONVERTING)) item.copy(status = FileConversionStatus.CANCELLED, message = "취소됨") else item }) }
                throw cancelled
            } catch (error: Exception) {
                // Only controlled backend messages are shown; vendor exception text may contain file paths.
                mutableUi.update { it.copy(errorMessage = fileTranscriptionUserMessage(error)) }
            } finally {
                mutableUi.update { it.copy(isConverting = false) }
                viewModelScope.launch { refreshLibrary() }
            }
        }
    }

    fun cancel(message: String = "파일 작업을 취소했습니다.") {
        work?.cancel()
        ++request
        fileLoad?.cancel()
        mutableUi.update { it.copy(progressMessage = message, isLoading = false) }
    }

    fun openEntry(id: String) {
        if (work?.isCompleted == false) return
        audio.close()
        val generation = ++request
        fileLoad?.cancel()
        mutableUi.update { it.copy(isLoading = true, errorMessage = null) }
        fileLoad = viewModelScope.launch {
            try {
                val entry = withContext(Dispatchers.IO) { library.load(id) } ?: return@launch
                if (generation != request) return@launch
                mutablePlayback.value = FilePlaybackUiState(entry, statusMessage = "파일 해시 확인 중")
                if (entry.requiresRelink) {
                    mutablePlayback.update { it?.copy(statusMessage = null,
                        errorMessage = "가져온 스크립트입니다. 파일 찾기로 원본 오디오를 선택하면 해시를 확인해 연결합니다.") }
                    return@launch
                }
                val uri = Uri.parse(entry.uri)
                val details = inspectFile(uri)
                check(details.hash == entry.sha256) { "File changed" }
                val descriptor = withContext(Dispatchers.IO) { requireNotNull(app.contentResolver.openFileDescriptor(uri, "r")) }
                descriptor.use { if (generation == request) audio.prepare(it) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (generation == request) {
                    if (mutablePlayback.value == null) mutableUi.update { it.copy(errorMessage = "스크립트를 열지 못했습니다. 저장공간과 스크립트 크기를 확인하세요. 기존 자료는 유지됩니다.") }
                    else mutablePlayback.update { it?.copy(errorMessage = "원본 파일을 읽을 수 없거나 해시가 달라졌습니다. 파일 찾기로 다시 연결하세요.", statusMessage = null) }
                }
            } finally { if (generation == request) mutableUi.update { it.copy(isLoading = false) } }
        }
    }

    fun closePlayback() { ++request; fileLoad?.cancel(); if (mutablePlayback.value?.isTranslating == true) work?.cancel(); audio.close(); mutablePlayback.value = null; mutableUi.update { it.copy(isLoading = false) } }
    fun playPause() { audio.playPause() }
    fun pauseForBackground() { audio.pause() }
    fun stop() { audio.stop() }
    fun seek(position: Long) { audio.seek(position) }
    fun setRepeat(value: Boolean) { audio.setRepeat(value) }
    fun setSpeed(value: Float) { audio.setSpeed(value) }
    fun adjacent(offset: Int) {
        val current = mutablePlayback.value?.entry?.id ?: return
        val list = mutableUi.value.library
        list.getOrNull(list.indexOfFirst { it.id == current } + offset)?.let { openEntry(it.id) }
    }

    fun translatePlayback(target: String?) {
        val current = mutablePlayback.value ?: return
        mutablePlayback.update { it?.copy(translationLanguageTag = target) }
        if (target == null || target in current.entry.translations || work?.isActive == true) return
        if (mutableUi.value.unavailableReason != null) {
            mutablePlayback.update { it?.copy(errorMessage = mutableUi.value.unavailableReason) }; return
        }
        val id = current.entry.id
        mutablePlayback.update { it?.copy(isTranslating = true, errorMessage = null) }
        work = viewModelScope.launch {
            try {
                val result = translateFileScript(app, current.entry, target, mutableUi.value.translationEngine) { done, total ->
                    mutablePlayback.update { if (it?.entry?.id == id) it.copy(statusMessage = "번역·자동 검사 $done / $total") else it }
                }
                val updated = current.entry.copy(translations = current.entry.translations + (target to result.lines), translationModes = current.entry.translationModes + (target to result.engine), qualityNotes = (current.entry.qualityNotes + result.notes).distinct())
                withContext(Dispatchers.IO) { library.save(updated) }
                mutablePlayback.update { if (it?.entry?.id == id) it.copy(entry = updated, statusMessage = "번역을 저장했습니다.") else it }
                refreshLibrary()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutablePlayback.update { if (it?.entry?.id == id) it.copy(errorMessage = "번역을 완료하지 못했습니다. 모델·원문 언어를 확인한 뒤 다시 선택하세요.") else it }
            } finally { mutablePlayback.update { if (it?.entry?.id == id) it.copy(isTranslating = false) else it } }
        }
    }

    fun delete(id: String) {
        if (isBusy()) return
        if (mutablePlayback.value?.entry?.id == id) closePlayback()
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { library.delete(id) }; refreshLibrary() }
            catch (_: Exception) { mutableUi.update { it.copy(errorMessage = "스크립트를 삭제하지 못했습니다. 다시 시도하세요.") } }
        }
    }
    private fun isBusy(): Boolean = work?.isCompleted == false || mutableUi.value.isConverting ||
        mutablePlayback.value?.isTranslating == true || mutableUi.value.isLoading
    private suspend fun refreshLibrary() {
        try { val entries = withContext(Dispatchers.IO) { library.list() }; mutableUi.update { it.copy(library = entries) } }
        catch (_: Exception) { mutableUi.update { it.copy(errorMessage = "파일 보관함을 읽지 못했습니다. 다시 열어 주세요.") } }
    }

    private data class FileDetails(val name: String, val hash: String)
    private fun documentName(uri: Uri): String = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.take(500)
    }.getOrNull() ?: "선택한 음성 파일"
    private suspend fun inspectFile(uri: Uri): FileDetails = withContext(Dispatchers.IO) {
        require(uri.scheme == "content" || uri.scheme == "file")
        val name = documentName(uri)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        requireNotNull(app.contentResolver.openInputStream(uri)).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= 8L * 1024 * 1024 * 1024) { "File too large" }
                digest.update(buffer, 0, count)
            }
        }
        require(total > 0)
        FileDetails(name, digest.digest().joinToString("") { "%02x".format(it) })
    }

    override fun onCleared() { work?.cancel(); fileLoad?.cancel(); audio.close(); library.close(); app.localFileWorkActive.value = false; super.onCleared() }
}

/** Controlled exception messages are supplied by the file backend; never forward OEM raw text. */
internal fun fileTranscriptionUserMessage(error: Exception): String =
    if (error is FileTranscriptionException) error.message ?: "음성 변환을 완료하지 못했습니다."
    else "음성 변환을 완료하지 못했습니다. 파일 변경 여부·언어·기기의 오프라인 음성인식 지원을 확인하세요."

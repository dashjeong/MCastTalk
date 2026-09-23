package app.guidecast.transmitter

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Build
import android.provider.OpenableColumns
import android.speech.SpeechRecognizer
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
    private val mutableUi = MutableStateFlow(withRecognitionSupport(FileTranslationUiState()))
    val uiState = mutableUi.asStateFlow()
    private val mutablePlayback = MutableStateFlow<FilePlaybackUiState?>(null)
    val playbackState = mutablePlayback.asStateFlow()
    private var selectedUri: Uri? = null
    private var selectedBatch: List<Uri> = emptyList()
    @Volatile private var work: Job? = null
    @Volatile private var fileLoad: Job? = null
    private val workOwner = Any()
    private val ownershipLock = Any()
    @Volatile private var cleared = false
    private var request = 0L
    private var relinkId: String? = null
    private val audio = FileAudioPlayback(app, viewModelScope) { update ->
        mutablePlayback.update { old -> old?.copy(positionMs = update.positionMs,
            isPlaying = update.isPlaying, isPrepared = update.prepared, speed = update.speed, repeat = update.repeat, errorMessage = update.error,
            statusMessage = if (!update.prepared && update.error == null) "오디오 준비 중" else old.statusMessage?.takeUnless { it == "오디오 준비 중" }) }
        updateOwnership()
    }

    init {
        viewModelScope.launch {
            combine(mutableUi, mutablePlayback) { ui, playback ->
                ui.isConverting || ui.isLoading || playback?.isPlaying == true || playback?.isTranslating == true
            }.distinctUntilChanged().collect { updateOwnership() }
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

    private fun updateOwnership() {
        synchronized(ownershipLock) {
            if (cleared) return // onCleared retains its token until every scope child has completed.
            val state = mutableUi.value
            val playback = mutablePlayback.value
            app.fileWorkOwners.setActive(workOwner, state.isConverting || state.isLoading ||
                playback?.isPlaying == true || playback?.isTranslating == true ||
                work?.isCompleted == false || fileLoad?.isCompleted == false)
        }
    }
    private fun claimWork(): Boolean = !cleared && !app.localVoiceNoteWorkActive.value &&
        app.fileWorkOwners.tryAcquire(workOwner)

    fun selectSource(tag: String?) {
        if (isBusy()) return
        refreshRecognitionSupport()
        if (tag == null && !mutableUi.value.automaticLanguageSupported) {
            mutableUi.update { it.copy(errorMessage = "자동 감지를 사용할 수 없습니다. 원문 언어를 직접 선택하세요.") }
            return
        }
        mutableUi.update { withRecognitionSupport(it.copy(sourceLanguageTag = tag, errorMessage = null, selectedFiles = requeueCompleted(it.selectedFiles))) }
    }
    fun refreshRecognitionSupport() { mutableUi.update(::withRecognitionSupport) }

    private fun withRecognitionSupport(state: FileTranslationUiState): FileTranslationUiState {
        val platform = Build.VERSION.SDK_INT >= 33 && runCatching {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(app)
        }.getOrDefault(false)
        val automatic = Build.VERSION.SDK_INT >= 34 && platform
        val source = state.sourceLanguageTag
        val appLanguage = source != null && FileSpeechTranscriber.usesAppRecognition(source)
        val capability = if (appLanguage) runCatching { app.speechRecognitionEngine.capability(requireNotNull(source)) }.getOrNull() else null
        val sourceUnavailable = when {
            source == null -> null
            appLanguage && capability?.available != true -> capability?.reason ?: "선택한 언어의 음성 인식을 사용할 수 없습니다. 다른 원문 언어를 선택하세요."
            !appLanguage && !platform -> "${fileLanguageLabel(source)} 파일 인식은 Android 13 이상의 기기 음성 인식 서비스와 해당 언어팩이 필요합니다. 지원되는 다른 원문 언어를 선택하세요."
            else -> null
        }
        return state.copy(
            automaticLanguageSupported = automatic,
            fileTranscriptionSupported = platform || runCatching { app.speechRecognitionEngine.capability().available }.getOrDefault(false),
            automaticLanguageUnavailableReason = if (automatic) null else if (Build.VERSION.SDK_INT < 34)
                "자동 감지는 Android 14 이상과 기기 음성 인식이 필요합니다. 원문 언어를 직접 선택하면 지원되는 앱 모델로 변환합니다."
                else "기기의 자동 파일 음성 인식을 사용할 수 없습니다. 원문 언어를 직접 선택하면 지원되는 앱 모델로 변환합니다.",
            sourceLanguageUnavailableReason = sourceUnavailable,
            recognitionSupportNotice = when {
                source == null -> null
                sourceUnavailable != null -> sourceUnavailable
                appLanguage -> "${fileLanguageLabel(source)} · 앱의 음성 인식 모델로 변환합니다. 저장된 모델을 재사용하며 필요한 언어는 처음 한 번 준비합니다."
                else -> "${fileLanguageLabel(source)} · 기기의 오프라인 파일 음성 인식을 사용합니다. 해당 언어팩과 파일 입력 지원이 필요합니다."
            },
        )
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
        if (isBusy() || !claimWork()) return
        val generation = ++request
        fileLoad?.cancel()
        mutableUi.update { it.copy(isLoading = true, errorMessage = null, progressMessage = "파일 목록을 확인하고 있습니다.") }
        fileLoad = viewModelScope.launch {
            try {
                val uris = findFiles()
                if (uris.size > MAX_FILE_BATCH_SIZE) throw FileTranscriptionException("한 번에 최대 500개 파일을 선택할 수 있습니다.")
                if (uris.isEmpty()) {
                    mutableUi.update { it.copy(errorMessage = "선택한 위치에 지원되는 음성 파일이 없습니다." +
                        if (it.selectedFiles.isNotEmpty()) " 기존 파일 선택은 유지했습니다." else " 다른 파일이나 폴더를 선택하세요.") }
                    return@launch
                }
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
        fileLoad?.invokeOnCompletion { updateOwnership() }
    }

    private fun updateQueue(uri: Uri, status: FileConversionStatus, message: String, hash: String? = null) {
        mutableUi.update { state -> state.copy(selectedFiles = state.selectedFiles.map { item ->
            if (item.uri == uri.toString()) item.copy(status = status, message = message, sha256 = hash ?: item.sha256) else item
        }) }
    }
    fun selectFile(uri: Uri) {
        if (isBusy()) return
        if (relinkId == null) { selectFiles(listOf(uri)); return }
        if (!claimWork()) return
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
        fileLoad?.invokeOnCompletion { updateOwnership() }
    }

    fun convert(onlyFailed: Boolean = false, uriToRetry: String? = null) {
        if (isBusy()) return
        refreshRecognitionSupport()
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
        if (!claimWork()) return
        val settings = mutableUi.value
        audio.pause()
        mutableUi.update { it.copy(isConverting = true, progress = null, errorMessage = null, progressMessage = "음성 인식 준비 중") }
        work = viewModelScope.launch {
            try {
              for ((fileIndex, uri) in batch.withIndex()) {
                currentCoroutineContext().ensureActive()
                var stage = FileConversionStage.INSPECT_SOURCE
                try {
                updateQueue(uri, FileConversionStatus.READING, "원본 확인 중")
                val details = inspectFile(uri)
                val hash = details.hash
                val name = details.name
                updateQueue(uri, FileConversionStatus.CONVERTING, "음성 인식 중", hash)
                stage = FileConversionStage.LOAD_SAVED_SCRIPT
                val existing = withContext(Dispatchers.IO) { library.load(hash) }
                val reusable = existing?.takeIf { saved -> settings.sourceLanguageTag == null || saved.sourceLanguageTag?.substringBefore('-') == settings.sourceLanguageTag.substringBefore('-') }
                stage = FileConversionStage.RECOGNIZE
                val result = if (reusable != null) FileTranscriptionResult(reusable.segments, reusable.sourceLanguageTag, reusable.qualityNotes, reusable.durationMs)
                else FileSpeechTranscriber.transcribe(app, uri, settings.sourceLanguageTag) { progress ->
                    mutableUi.update { it.copy(progress = progress.durationMs?.takeIf { total -> total > 0 }?.let { total -> (progress.processedMs.toFloat() / total).coerceIn(0f, 1f) }, progressMessage = "${fileIndex + 1}/${batch.size} · ${progress.message}") }
                }
                val notes = inspectFileTranscript(result.segments, result.durationMs) + result.warnings
                // Rechecking avoids pairing a completed script with a document changed during recognition.
                stage = FileConversionStage.RECHECK_SOURCE
                check(inspectFile(uri).hash == hash) { "File changed" }
                var entry = FileLibraryEntry(hash, name, uri.toString(), hash, result.durationMs,
                    result.sourceLanguageTag, reusable?.createdAtMillis ?: System.currentTimeMillis(), result.segments,
                    translations = reusable?.translations.orEmpty(), qualityNotes = notes.distinct(), translationModes = reusable?.translationModes.orEmpty())
                stage = FileConversionStage.SAVE_SOURCE
                withContext(Dispatchers.IO) { library.save(entry) }
                mutableUi.update { it.copy(detectedLanguageTag = result.sourceLanguageTag, languageNotice = result.warnings.joinToString(" · ").ifBlank { null }) }
                // Each target commits separately so a failed language cannot discard the source or other translations.
                for (target in settings.targetLanguageTags) {
                    stage = FileConversionStage.PLAN_TRANSLATION
                    if (!shouldTranslateFileTarget(entry, target, settings.translationEngine,
                            retryOnly = onlyFailed || uriToRetry != null)) continue
                    try {
                        stage = FileConversionStage.TRANSLATE_TARGET
                        entry = translateFileTargetWithCheckpoints(entry, target, settings.translationEngine,
                            save = { updated ->
                                withContext(Dispatchers.IO) { library.save(updated) }
                                entry = updated
                            }) { pending, onLine ->
                            translateFileScript(app, pending, target, settings.translationEngine, contextSegments = entry.segments, onLine = onLine) { done, total ->
                                mutableUi.update { it.copy(progressMessage = "남은 문장 번역·자동 검사 $done / $total", progress = done.toFloat() / total.coerceAtLeast(1)) }
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        RuntimeDiagnosticLog.record("file_translation_failure", fileConversionFailureDetail(stage, error))
                        stage = FileConversionStage.SAVE_TRANSLATION_FAILURE
                        entry = entry.copy(qualityNotes = (entry.qualityNotes + "$target 번역을 완료하지 못했습니다. 재생 화면에서 다시 요청할 수 있습니다.").distinct())
                        withContext(Dispatchers.IO) { library.save(entry) }
                    }
                }
                stage = FileConversionStage.FINALIZE_QUEUE
                val missingTargets = settings.targetLanguageTags.filter { shouldTranslateFileTarget(entry, it, settings.translationEngine, retryOnly = true) }
                updateQueue(uri, if (missingTargets.isEmpty()) FileConversionStatus.SAVED else FileConversionStatus.PARTIAL,
                    if (missingTargets.isEmpty()) "스크립트 저장 완료" else "원문 저장 완료 · 미완료 번역은 재작업할 수 있습니다.", hash)
                stage = FileConversionStage.REFRESH_LIBRARY
                refreshLibrary()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    RuntimeDiagnosticLog.record("file_conversion_failure", fileConversionFailureDetail(stage, error))
                    updateQueue(uri, FileConversionStatus.FAILED, fileTranscriptionUserMessage(error))
                }
              }
                val completed = mutableUi.value.selectedFiles.count { it.status == FileConversionStatus.SAVED }
                val failed = mutableUi.value.selectedFiles.count(::retryableFileItem)
                mutableUi.update { it.copy(progress = 1f, progressMessage = "${completed}개 완료 · ${failed}개 재작업 가능. 완료된 결과는 파일별로 보관했습니다.") }
            } catch (cancelled: CancellationException) {
                mutableUi.update { it.copy(progressMessage = (cancelled.message ?: "파일 변환을 취소했습니다.") + " 완료되어 저장된 결과는 보관함에 남습니다.",
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
        work?.invokeOnCompletion { updateOwnership() }
    }

    fun cancel(message: String = "파일 작업을 취소했습니다.") {
        work?.cancel(CancellationException(message))
        ++request
        fileLoad?.cancel()
        mutableUi.update { it.copy(progressMessage = message, isLoading = false) }
    }

    fun openEntry(id: String) {
        // Relinking calls this from its current fileLoad job; replacing that read is intentional.
        if (cleared || work?.isCompleted == false || app.fileWorkOwners.hasOther(workOwner) || !claimWork()) return
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
        fileLoad?.invokeOnCompletion { updateOwnership() }
    }

    fun closePlayback() { ++request; fileLoad?.cancel(); if (mutablePlayback.value?.isTranslating == true) work?.cancel(); audio.close(); mutablePlayback.value = null; mutableUi.update { it.copy(isLoading = false) } }
    fun playPause() { if (!isBusy() && claimWork()) { audio.playPause(); updateOwnership() } }
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
        if (isBusy()) return
        val current = mutablePlayback.value ?: return
        mutablePlayback.update { it?.copy(translationLanguageTag = target) }
        if (target == null || !shouldTranslateFileTarget(current.entry, target,
                mutableUi.value.translationEngine, retryOnly = true) || work?.isActive == true) return
        if (mutableUi.value.unavailableReason != null) {
            mutablePlayback.update { it?.copy(errorMessage = mutableUi.value.unavailableReason) }; return
        }
        if (!claimWork()) return
        val id = current.entry.id
        mutablePlayback.update { it?.copy(isTranslating = true, errorMessage = null) }
        work = viewModelScope.launch {
            try {
                val updated = translateFileTargetWithCheckpoints(current.entry, target, mutableUi.value.translationEngine,
                    save = { saved ->
                        withContext(Dispatchers.IO) { library.save(saved) }
                        mutablePlayback.update { if (it?.entry?.id == id) it.copy(entry = saved) else it }
                    }) { pending, onLine ->
                    translateFileScript(app, pending, target, mutableUi.value.translationEngine, contextSegments = current.entry.segments, onLine = onLine) { done, total ->
                        mutablePlayback.update { if (it?.entry?.id == id) it.copy(statusMessage = "남은 문장 번역·자동 검사 $done / $total") else it }
                    }
                }
                mutablePlayback.update { if (it?.entry?.id == id) it.copy(entry = updated, statusMessage = "번역을 저장했습니다.") else it }
                refreshLibrary()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutablePlayback.update { if (it?.entry?.id == id) it.copy(errorMessage = "번역을 완료하지 못했습니다. 모델·원문 언어를 확인한 뒤 다시 선택하세요.") else it }
            } finally { mutablePlayback.update { if (it?.entry?.id == id) it.copy(isTranslating = false) else it } }
        }
        work?.invokeOnCompletion { updateOwnership() }
    }

    fun delete(id: String) {
        if (isBusy() || !claimWork()) return
        if (mutablePlayback.value?.entry?.id == id) closePlayback()
        work = viewModelScope.launch {
            try { withContext(Dispatchers.IO) { library.delete(id) }; refreshLibrary() }
            catch (_: Exception) { mutableUi.update { it.copy(errorMessage = "스크립트를 삭제하지 못했습니다. 다시 시도하세요.") } }
        }
        updateOwnership()
        work?.invokeOnCompletion { updateOwnership() }
    }
    private fun isBusy(): Boolean = cleared || app.fileWorkOwners.hasOther(workOwner) ||
        work?.isCompleted == false || mutableUi.value.isConverting ||
        app.localVoiceNoteWorkActive.value ||
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

    override fun onCleared() {
        // ViewModelScope includes refresh/delete/IO descendants beyond work and fileLoad.
        // A replacement screen cannot use the shared recognizer while these are retiring.
        synchronized(ownershipLock) {
            cleared = true
            app.fileWorkOwners.setActive(workOwner, true)
        }
        audio.close()
        retireLocalWork(viewModelScope.coroutineContext[Job]) {
            try { library.close() }
            finally { app.fileWorkOwners.setActive(workOwner, false) }
        }
        super.onCleared()
    }
}

/** Controlled exception messages are supplied by the file backend; never forward OEM raw text. */
internal fun fileTranscriptionUserMessage(error: Exception): String =
    if (error is FileTranscriptionException) error.message ?: "음성 변환을 완료하지 못했습니다."
    else "음성 변환을 완료하지 못했습니다. 파일 변경 여부·언어·기기의 오프라인 음성인식 지원을 확인하세요."

internal enum class FileConversionStage {
    INSPECT_SOURCE, LOAD_SAVED_SCRIPT, RECOGNIZE, RECHECK_SOURCE, SAVE_SOURCE,
    PLAN_TRANSLATION, TRANSLATE_TARGET, SAVE_TRANSLATION_FAILURE, FINALIZE_QUEUE, REFRESH_LIBRARY,
}

/** No media, text, URI, filename, exception message, or unrestricted stack is retained. */
internal fun fileConversionFailureDetail(stage: FileConversionStage, error: Exception): String {
    fun identifier(value: String) = value.replace(Regex("[^A-Za-z0-9_.$]"), "_").take(160)
    val frames = error.stackTrace.filter { it.className.startsWith("app.guidecast.") }.take(4)
        .joinToString(";") { "${identifier(it.className)}.${identifier(it.methodName)}:${it.lineNumber}" }
    return "stage=${stage.name} type=${identifier(error.javaClass.name)} cause=${error.cause?.javaClass?.name?.let(::identifier) ?: "none"} frames=$frames"
}

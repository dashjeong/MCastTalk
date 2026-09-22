package app.guidecast.transmitter

import android.content.Context
import android.net.Uri
import app.guidecast.core.audio.MicrophoneNoiseMode
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** User-triggered local backup and validated restore; credentials and consent are never exported. */
class DataTransferRepository(private val app: GuideCastApplication) {
    /** The destination is fixed to the explicitly approved device Downloads backup folder. */
    suspend fun exportToDownloads(kind: DataTransferKind,
        onProgress: (DataTransferProgress) -> Unit = {}): DataTransferResult = transferLock.withLock {
        savePreparedDownload(app, kind, ::prepareBackupUnlocked, DownloadBackupStore(app), onProgress)
    }

    /** Local staging only. No SAF destination is opened and no private content leaves app storage. */
    internal suspend fun prepareBackup(kind: DataTransferKind,
        onProgress: (DataTransferProgress) -> Unit = {}): PreparedLocalBackup = transferLock.withLock {
        prepareBackupUnlocked(kind, onProgress)
    }

    private suspend fun prepareBackupUnlocked(kind: DataTransferKind,
        onProgress: (DataTransferProgress) -> Unit): PreparedLocalBackup = withContext(Dispatchers.IO) {
        val temporary = File.createTempFile("portable-export-", ".zip", app.cacheDir)
        try {
            DataTransferStage(File.createTempFile("portable-export-check-", ".db", app.cacheDir)).use { validation ->
            val coroutine = currentCoroutineContext()
            var count = 0L
            var noteAudio = emptyList<Pair<File, String>>()
            val digest = MessageDigest.getInstance("SHA-256")
            val validationBatch = ArrayList<JSONObject>(32)
            fun flushValidation() {
                if (validationBatch.isNotEmpty()) { validation.addBatch(validationBatch, kind); validationBatch.clear() }
            }
            onProgress(DataTransferProgress("앱 내부에서 백업 파일을 준비하고 있습니다."))
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("records.jsonl"))
                var bytes = 0L
                fun emit(row: JSONObject) {
                    coroutine.ensureActive()
                    DataTransferFormat.validate(row, kind)
                    val encoded = (row.toString() + "\n").toByteArray(Charsets.UTF_8)
                    require(encoded.size <= DataTransferFormat.MAX_RECORD_CHARS) { "단일 레코드가 백업 지원 크기를 초과합니다." }
                    bytes += encoded.size
                    require(bytes <= DataTransferFormat.MAX_EXPANDED_BYTES && ++count <= DataTransferFormat.MAX_RECORDS)
                    validationBatch += row
                    if (validationBatch.size == 32) flushValidation()
                    digest.update(encoded); zip.write(encoded)
                    if (count % 1_000 == 0L) onProgress(DataTransferProgress("앱 내부에서 백업 파일을 준비하고 있습니다.", count))
                }
                when (kind) {
                    DataTransferKind.SETTINGS -> emit(PortableSettings.capture(app))
                    DataTransferKind.DICTIONARY -> {
                        app.glossary.exportOverrides { row -> emit(JSONObject().apply {
                            val term = row.term
                            put("type", "glossary"); put("sourceLanguage", term.sourceLanguage); put("targetLanguage", term.targetLanguage)
                            put("term", term.sourceTerm); put("preferred", term.preferredTerm); put("replacement", term.replacement)
                            put("category", term.category); put("origin", term.origin); put("enabled", term.enabled); put("alternatives", row.alternatives)
                        }) }
                        app.speechCorrections.exportAll().forEach { row -> emit(JSONObject().apply {
                            put("type", "correction"); put("id", row.id); put("profile", row.profile); put("language", row.languageTag)
                            put("recognized", row.recognizedText); put("corrected", row.correctedText); put("hint", row.hint)
                            put("enabled", row.enabled); put("sourceKey", row.sourceKey); put("updated", row.updatedAt)
                        }) }
                        var offset = 0
                        do {
                            val rows = app.sentenceTranslationMemory.loadPage(offset, 1_000)
                            rows.forEach { row -> emit(JSONObject().apply {
                                put("type", "memory"); put("sourceLanguage", row.sourceLanguageTag); put("targetLanguage", row.targetLanguageTag)
                                put("register", row.translationRegister.name); put("original", row.original); put("corrected", row.corrected)
                                put("origin", row.origin.name); put("updated", row.updatedAtEpochMillis)
                            }) }
                            offset += rows.size
                        } while (rows.size == 1_000)
                        app.sentenceTranslationMemory.teacherReports().forEach { report -> emit(report.toJson().put("type", "teacherReport")) }
                    }
                    DataTransferKind.SCRIPTS -> {
                        var lastSession: Long? = null
                        do {
                            val sessions = app.transcriptArchive.exportSessionsPage(lastSession)
                            sessions.forEach { row -> emit(JSONObject().apply {
                                put("type", "session"); put("id", row.sessionId); put("started", row.startedAtEpochMillis); put("language", row.sourceLanguageTag)
                            }) }
                            lastSession = sessions.lastOrNull()?.sessionId
                        } while (sessions.size == 1_000)
                        var after: TranscriptArchiveKey? = null
                        do {
                            val rows = app.transcriptArchive.exportPortablePage(after)
                            rows.forEach { row -> emit(JSONObject().apply {
                                put("type", "broadcast"); put("session", row.key.sessionId); put("sequence", row.key.sequence)
                                put("started", row.sessionStartedAtEpochMillis); put("language", row.sourceLanguageTag); put("source", row.line.sourceText)
                                put("captured", row.line.capturedAtElapsedRealtimeNanos); put("translations", JSONObject(row.line.translations))
                                put("translationMs", JSONObject(row.line.translationLatencyMillis)); put("firstAudioMs", JSONObject(row.line.firstAudioLatencyMillis))
                                put("synthesisMs", JSONObject(row.line.synthesisLatencyMillis)); put("backup", row.isDailyBackup)
                            }) }
                            after = rows.lastOrNull()?.key
                        } while (rows.size == 200)
                        FileTranscriptLibrary(app).use { it.exportPortable(::emit) }
                        noteAudio = VoiceNoteTransfer.export(VoiceNoteRepository(File(app.filesDir, "voice-notes")), ::emit) { coroutine.ensureActive() }
                    }
                }
                flushValidation()
                validation.validateReferences(kind)
                zip.closeEntry()
                noteAudio.forEach { (audio, expectedHash) ->
                    bytes += audio.length()
                    require(bytes <= DataTransferFormat.MAX_EXPANDED_BYTES) { "백업이 지원 용량 8GB를 초과합니다." }
                    zip.putNextEntry(ZipEntry("voice-notes/${audio.name}"))
                    require(audio.inputStream().use { VoiceNoteTransfer.copy(it, zip) { coroutine.ensureActive() } } == expectedHash) {
                        "백업 준비 중 녹음이 변경됐습니다. 작업을 중지하고 다시 백업하세요."
                    }
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(DataTransferFormat.manifest(kind, count, digest.digest().joinToString("") { "%02x".format(it) }).toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            require(temporary.length() <= DataTransferFormat.MAX_COMPRESSED_BYTES)
            PreparedLocalBackup(temporary, kind, count)
            }
        } catch (error: Throwable) { temporary.delete(); throw error }
    }

    suspend fun import(kind: DataTransferKind, source: Uri, onProgress: (DataTransferProgress) -> Unit = {}): DataTransferResult =
        transferLock.withLock { withContext(Dispatchers.IO) {
            val file = File.createTempFile("portable-import-", ".db", app.cacheDir)
            val coroutine = currentCoroutineContext()
            DataTransferStage(file).use { stage ->
                onProgress(DataTransferProgress("선택한 백업의 형식과 내용을 검사하고 있습니다."))
                requireNotNull(app.contentResolver.openInputStream(source)).use { stage.readZip(it, kind, coroutine, onProgress) }
                coroutine.ensureActive()
                check(!app.dataTransferUnavailable()) { "입력·방송을 중지한 뒤 가져오세요." }
                if (kind == DataTransferKind.SCRIPTS) {
                    FileTranscriptLibrary(app).use { it.validatePortableCapacity(stage.records("file")) }
                    VoiceNoteRepository(File(app.filesDir, "voice-notes")).validateImport(stage.voiceNotes)
                }
                onProgress(DataTransferProgress("검사를 마쳤습니다. 기존 자료를 유지하며 병합합니다.", stage.count, applying = true))
                var inserted = 0L
                when (kind) {
                    DataTransferKind.SETTINGS -> { PortableSettings.apply(app, stage.records("settings").single()); inserted = 1 }
                    DataTransferKind.DICTIONARY -> {
                        inserted += app.speechCorrections.importMissing(stage.records("correction").map(DataTransferFormat::correction).toList())
                        coroutine.ensureActive()
                        inserted += app.glossary.importMissingOverrides(stage.records("glossary").onEach { coroutine.ensureActive() }.map(DataTransferFormat::glossary))
                        stage.records("memory").map(DataTransferFormat::memory).chunked(1_000).forEach { batch ->
                            coroutine.ensureActive(); inserted += app.sentenceTranslationMemory.importRecords(batch)
                        }
                        stage.records("teacherReport").forEach { row ->
                            coroutine.ensureActive()
                            if (app.sentenceTranslationMemory.importTeacherReport(TeacherLearningReport.fromJson(row))) inserted++
                        }
                    }
                    DataTransferKind.SCRIPTS -> {
                        stage.records("session").map(DataTransferFormat::session).chunked(1_000).forEach { batch ->
                            coroutine.ensureActive(); inserted += app.transcriptArchive.importPortableSessions(batch)
                        }
                        stage.records("broadcast").map(DataTransferFormat::broadcast).chunked(200).forEach { batch ->
                            coroutine.ensureActive(); inserted += app.transcriptArchive.importPortableLines(batch)
                            onProgress(DataTransferProgress("방송 스크립트를 병합하고 있습니다.", inserted, applying = true))
                        }
                        coroutine.ensureActive()
                        FileTranscriptLibrary(app).use { library -> inserted += library.importPortable { type ->
                            stage.records(type).onEach { coroutine.ensureActive() }
                        } }
                        inserted += VoiceNoteRepository(File(app.filesDir, "voice-notes")).importMissing(stage.voiceNotes) { coroutine.ensureActive() }
                    }
                }
                DataTransferResult(inserted, when {
                    stage.count == 0L -> "빈 백업입니다. 기존 자료를 유지했습니다."
                    kind == DataTransferKind.SETTINGS -> "설정을 가져왔습니다. 클라우드 전송과 자동 학습 동의는 꺼진 상태입니다."
                    kind == DataTransferKind.SCRIPTS -> "가져오기를 마쳤습니다. 새 항목 ${inserted}개 · 기존 항목과 확정 수정은 유지했습니다. 녹톡 원음은 함께 복원했습니다. 외부 파일 음원은 ‘파일 찾기’로 다시 연결하세요."
                    else -> "가져오기를 마쳤습니다. 새 항목 ${inserted}개 · 기존 항목과 확정 수정은 유지했습니다."
                })
            }
        } }
    private companion object { val transferLock = Mutex() }
}

internal data class PreparedLocalBackup(val file: File, val kind: DataTransferKind, val records: Long) : java.io.Closeable {
    override fun close() { file.delete() }
}

internal typealias BackupPreparer = suspend (DataTransferKind, (DataTransferProgress) -> Unit) -> PreparedLocalBackup

/** Shared by the real repository and synthetic UI fixtures; only explicit backups include note audio. */
internal suspend fun savePreparedDownload(
    app: GuideCastApplication,
    kind: DataTransferKind,
    prepareBackup: BackupPreparer,
    store: DownloadBackupStore,
    onProgress: (DataTransferProgress) -> Unit,
): DataTransferResult = withContext(Dispatchers.IO) {
    check(!app.dataTransferUnavailable()) { "입력·방송·파일 작업을 중지한 뒤 백업하세요." }
    prepareBackup(kind, onProgress).use { prepared ->
        currentCoroutineContext().ensureActive()
        check(prepared.kind == kind && !app.dataTransferUnavailable()) { "다른 작업이 시작되어 백업 저장을 중지했습니다." }
        val saved = store.save(prepared) { written, total ->
            checkExportStillAllowed(app)
            val percentage = if (total == 0L) 0 else (written * 100L / total).toInt()
            onProgress(DataTransferProgress("기기에 백업 저장 중 · $percentage%", prepared.records))
        }
        DataTransferResult(prepared.records, buildString {
            append("${kind.label} 백업 저장 완료 · ${prepared.records}개 레코드\n")
            append("${DownloadBackupStore.DISPLAY_DIRECTORY}\n${saved.displayName}")
            if (prepared.records == 0L) append("\n저장된 내용이 없는 빈 백업입니다.")
        })
    }
}

private fun checkExportStillAllowed(app: GuideCastApplication) {
    if (app.dataTransferUnavailable()) throw kotlinx.coroutines.CancellationException("다른 작업이 시작되었습니다.")
}

/** An explicit whitelist: never enumerate all preferences or read encrypted credential stores. */
internal object PortableSettings {
    fun capture(app: GuideCastApplication): JSONObject = JSONObject().apply {
        val options = app.developerLabSettings.portableOptions()
        put("type", "settings"); put("microphoneNoise", app.microphoneNoiseSettings.mode.value.name)
        put("developerInfo", app.getSharedPreferences("ui_display", Context.MODE_PRIVATE).getBoolean("developer_info", false))
        put("retentionPolicy", app.transcriptArchive.snapshot.value.retentionPolicy.name)
        put("correctionProfile", app.speechCorrections.activeProfile.value)
        put("operator", app.operatorSettings.state.value.toJson())
        put("translationApi", app.translationApiSettings.state.value.portable())
        put("lab", JSONObject().apply {
            put("expressiveTts", options.expressiveTtsEnabled); put("paraphrase", options.paraphraseEnabled)
            put("register", options.translationRegister.name); put("provider", options.provider.name); put("model", options.modelId)
            put("secondaryModel", options.secondaryModelId); put("comparisonSituation", options.comparisonSituation)
        })
        put("voices", JSONObject(app.speechSynthesisProvider.voicePreferences.value.mapValues { it.value.name }))
        put("registeredPackages", JSONArray(app.getSharedPreferences("playback-targets", Context.MODE_PRIVATE)
            .getStringSet("registered-packages", emptySet()).orEmpty().filter(::isValidAndroidPackageName)))
    }.let(::sanitized)
    /** Unknown future fields are tolerated on input, but only these names may leave the app. */
    internal fun sanitized(row: JSONObject): JSONObject = JSONObject().apply {
        put("type", "settings")
        listOf("microphoneNoise", "developerInfo", "retentionPolicy", "correctionProfile", "voices", "registeredPackages").forEach { key ->
            if (row.has(key)) put(key, row.get(key))
        }
        row.optJSONObject("operator")?.let { put("operator", OperatorOptions.fromJson(it).toJson()) }
        row.optJSONObject("translationApi")?.let { put("translationApi", TranslationApiOptions.fromPortable(it).portable()) }
        row.optJSONObject("lab")?.let { lab -> put("lab", JSONObject().apply {
            listOf("expressiveTts", "paraphrase", "register", "provider", "model", "secondaryModel", "comparisonSituation").forEach { key -> if (lab.has(key)) put(key, lab.get(key)) }
        }) }
    }
    fun validate(row: JSONObject) {
        if (row.has("microphoneNoise")) MicrophoneNoiseMode.valueOf(row.getString("microphoneNoise"))
        if (row.has("developerInfo")) require(row.get("developerInfo") is Boolean)
        if (row.has("retentionPolicy")) TranscriptRetentionPolicy.valueOf(row.getString("retentionPolicy"))
        if (row.has("correctionProfile")) SpeechCorrectionValidation.profile(row.getString("correctionProfile"))
        row.optJSONObject("operator")?.let { OperatorOptions.fromJson(it) }
        row.optJSONObject("translationApi")?.let { TranslationApiOptions.fromPortable(it) }
        row.optJSONObject("lab")?.let { lab ->
            listOf("expressiveTts", "paraphrase").forEach { if (lab.has(it)) require(lab.get(it) is Boolean) }
            if (lab.has("register")) TranslationRegister.valueOf(lab.getString("register"))
            if (lab.has("provider")) CloudReviewProvider.valueOf(lab.getString("provider"))
            if (lab.has("model")) require(validReviewModel(lab.getString("model")))
            if (lab.has("secondaryModel")) require(validReviewModel(lab.getString("secondaryModel")))
            if (lab.has("comparisonSituation")) {
                val value = lab.getString("comparisonSituation")
                require(value.length <= 300 && !containsCredentialLikeText(value) && value.none { it == '\u0000' || it.code < 32 && it !in "\n\r\t" })
            }
        }
        row.optJSONObject("voices")?.let { voices ->
            require(voices.length() <= 100)
            voices.keys().forEach { language -> normalizeMemoryLanguage(language); SpeechVoicePreference.valueOf(voices.getString(language)) }
        }
        row.optJSONArray("registeredPackages")?.let { packages ->
            require(packages.length() <= 500); repeat(packages.length()) { require(isValidAndroidPackageName(packages.getString(it))) }
        }
    }
    suspend fun apply(app: GuideCastApplication, row: JSONObject) {
        validate(row)
        check(!app.dataTransferUnavailable())
        app.developerLabSettings.setCloudReviewEnabled(false)
        app.developerLabSettings.setAutoLearnEnabled(false)
        app.translationApiSettings.setAllowOnline(false)
        row.optJSONObject("translationApi")?.let { app.translationApiSettings.configure(TranslationApiOptions.fromPortable(it)) }
        if (row.has("microphoneNoise")) app.microphoneNoiseSettings.select(MicrophoneNoiseMode.valueOf(row.getString("microphoneNoise")))
        if (row.has("developerInfo")) app.uiDisplaySettings.setDeveloperInfo(row.getBoolean("developerInfo"))
        if (row.has("retentionPolicy")) app.transcriptArchive.setRetentionPolicy(TranscriptRetentionPolicy.valueOf(row.getString("retentionPolicy")))
        if (row.has("correctionProfile")) app.speechCorrections.selectProfile(row.getString("correctionProfile"))
        row.optJSONObject("operator")?.let { incoming ->
            val merged = app.operatorSettings.state.value.toJson()
            incoming.keys().forEach { merged.put(it, incoming.get(it)) }
            app.operatorSettings.restore(OperatorOptions.fromJson(merged))
        }
        var options = app.developerLabSettings.state.value
        row.optJSONObject("lab")?.let { lab ->
            val provider = if (lab.has("provider")) CloudReviewProvider.valueOf(lab.getString("provider")) else options.provider
            options = options.copy(
                expressiveTtsEnabled = if (lab.has("expressiveTts")) lab.getBoolean("expressiveTts") else options.expressiveTtsEnabled,
                paraphraseEnabled = if (lab.has("paraphrase")) lab.getBoolean("paraphrase") else options.paraphraseEnabled,
                translationRegister = if (lab.has("register")) TranslationRegister.valueOf(lab.getString("register")) else options.translationRegister,
                provider = provider,
                modelId = if (lab.has("model")) lab.getString("model") else if (provider != options.provider) defaultReviewModel(provider) else options.modelId,
                secondaryModelId = if (lab.has("secondaryModel")) lab.getString("secondaryModel") else if (provider != options.provider) defaultReviewModel(provider.other()) else options.secondaryModelId,
                comparisonSituation = if (lab.has("comparisonSituation")) lab.getString("comparisonSituation") else options.comparisonSituation,
            )
        }
        app.developerLabSettings.importOptions(options)
        row.optJSONObject("voices")?.let { voices -> voices.keys().forEach { language ->
            app.speechSynthesisProvider.setVoicePreference(language, SpeechVoicePreference.valueOf(voices.getString(language)))
        } }
        row.optJSONArray("registeredPackages")?.let { packages ->
            val preferences = app.getSharedPreferences("playback-targets", Context.MODE_PRIVATE)
            val existing = preferences.getStringSet("registered-packages", emptySet()).orEmpty()
            val incoming = (0 until packages.length()).map(packages::getString)
            check(preferences.edit().putStringSet("registered-packages", existing + incoming).commit())
        }
    }
}

internal fun BroadcastSnapshot.dataTransferUnavailable(): Boolean = translationTestActive ||
    phase in setOf(BroadcastPhase.STARTING, BroadcastPhase.LIVE, BroadcastPhase.PAUSED) ||
    inputPhase in setOf(InputPhase.STARTING, InputPhase.ACTIVE, InputPhase.PAUSED)

internal fun GuideCastApplication.dataTransferUnavailable(): Boolean = broadcastRuntime.state.value.dataTransferUnavailable() ||
    localFileWorkActive.value || localVoiceNoteWorkActive.value || localModelWorkActive.value

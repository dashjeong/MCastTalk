package app.guidecast.transmitter

import app.guidecast.provider.gemma.translation.GemmaModelReadiness
import app.guidecast.provider.gemma.translation.GemmaBroadcastCapability
import app.guidecast.core.translation.SelectiveRefinementReason
import app.guidecast.core.translation.TranslationReviewContext
import app.guidecast.core.translation.TranslationStyle
import app.guidecast.core.translation.TranslationStyleContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal data class FileScriptTranslation(val lines: List<String>, val notes: List<String>, val engine: FileTranslationEngine)

/** Local translation with separately consented, bounded text-only developer review. */
internal suspend fun translateFileScript(
    app: GuideCastApplication,
    entry: FileLibraryEntry,
    target: String,
    mode: FileTranslationEngine,
    allowCloudReview: Boolean = true,
    onProgress: (Int, Int) -> Unit,
): FileScriptTranslation = app.withTranslationBackendUse {
    val notes = linkedSetOf<String>()
    val results = MutableList(entry.segments.size) { "" }
    var allReviewsCompleted = mode == FileTranslationEngine.GEMMA
    var allApiCompleted = mode == FileTranslationEngine.API
    if (mode == FileTranslationEngine.API) check(app.translationApiSettings.state.value.provider != TranslationApiProvider.LOCAL) {
        "설정의 번역 서비스에서 API를 선택하고 전송 허용을 확인하세요."
    }
    val grouped = entry.segments.indices.groupBy { index ->
        (entry.segments[index].languageTag ?: entry.sourceLanguageTag)?.substringBefore('-')
            ?: error("음성 언어를 확인한 뒤 다시 변환하세요.")
    }
    var completed = 0
    for ((source, indexes) in grouped) {
        currentCoroutineContext().ensureActive()
        if (source == target.substringBefore('-')) {
            indexes.forEach { index -> results[index] = entry.segments[index].text; onProgress(++completed, results.size) }
            continue
        }
        val owner = app.beginSettingsPreparation(source, setOf(target))
        try {
            withTimeout(180_000) {
                app.selectTranslationSource(owner)
                if (mode != FileTranslationEngine.API) app.prepareTranslationModelsWithProcessAdmission(setOf(target), true, owner)
                else app.refreshTranslationModels(setOf(target), owner)
            }
            val localEngine = app.translationProvider.engineFor(target)
            val draftEngine = if (mode == FileTranslationEngine.API) app.translationApiService.engine(localEngine) else localEngine
            val reviewerReady = mode == FileTranslationEngine.GEMMA &&
                app.gemmaTranslationProvider.modelManager.status.value.readiness == GemmaModelReadiness.READY &&
                !app.gemmaTranslationProvider.isAutomaticRetryBlocked() &&
                GemmaBroadcastCapability.detect(app).supported
            if (mode == FileTranslationEngine.GEMMA && !reviewerReady) {
                allReviewsCompleted = false
                notes += "AI 검토 모델을 사용할 수 없어 Google ML Kit 번역을 보존했습니다. 설정에서 모델을 준비·점검하세요."
            }
            var reviewAvailable = reviewerReady
            indexes.forEach { index ->
                currentCoroutineContext().ensureActive()
                if (!app.isPreparationCurrent(owner)) throw CancellationException("File translation superseded")
                val original = entry.segments[index].text
                // Long recognizer segments are translated in bounded, word-aware pieces. Source
                // text and its word timing remain untouched in the saved transcript.
                val chunks = fileTranslationChunks(original)
                val translated = chunks.map { chunk ->
                    val lab = app.developerLabSettings.state.value
                    val style = if (app.uiDisplaySettings.developerInfo.value && lab.paraphraseEnabled)
                        TranslationStyleContext(TranslationStyle.valueOf(lab.translationRegister.name))
                    else TranslationStyleContext(app.translationApiSettings.state.value.tone)
                    withContext(style) {
                    val draft = withTimeout(20_000) { draftEngine.translate(chunk, source, target) }
                    if (mode == FileTranslationEngine.API && app.translationApiService.states.value[target] != TranslationApiState.READY) allApiCompleted = false
                    check(draft.isNotBlank()) { "번역 결과가 비어 있습니다." }
                    val localResult = if (!reviewAvailable || !TranslationReviewContext.canRepresent(chunk, draft)) {
                        if (mode == FileTranslationEngine.GEMMA) allReviewsCompleted = false
                        draft
                    }
                    else {
                        val reviewed = try {
                            withTimeout(30_000) {
                                app.withProcessNativeColdLoadLease(ProcessNativeColdLoadKeys.GEMMA_MODEL, true) {
                                    withContext(TranslationReviewContext(chunk, draft, source, target,
                                        setOf(SelectiveRefinementReason.LONG_COMPLETE_SENTENCE))) {
                                        app.gemmaTranslationProvider.engineFor(target).translate(chunk, source, target)
                                    }
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            currentCoroutineContext().ensureActive()
                            reviewAvailable = false
                            allReviewsCompleted = false
                            notes += "일부 AI 검토가 시간 안에 끝나지 않아 ML Kit 초안을 보존했습니다."
                            null
                        } catch (_: Exception) {
                            reviewAvailable = false
                            allReviewsCompleted = false
                            notes += "일부 AI 검토가 실패해 ML Kit 초안을 보존했습니다."
                            null
                        }
                        if (reviewed.isNullOrBlank() || reviewed.length > draft.length * 4 + 120 ||
                            translationNumbersNeedReview(chunk, reviewed)) {
                            allReviewsCompleted = false
                            if (reviewed != null) notes += "일부 AI 검토 결과가 자동 검사에서 확인을 요구해 ML Kit 초안을 보존했습니다."
                            draft
                        } else reviewed
                    }
                    val refined = if (allowCloudReview) app.cloudTranslationReviewer.refine(source, target, chunk, localResult) else localResult
                    if (refined != localResult) notes += "문장 사전 또는 사용자가 활성화한 API 검토 보정을 반영했습니다."
                    if (target.equals("zh-TW", true)) convertToTraditionalChinese(refined) else refined
                    }
                }.joinToString(" ")
                if (translationNumbersNeedReview(original, translated)) notes += "숫자 표현이 원문과 다른 번역이 있습니다. 원음과 대조하세요."
                results[index] = translated
                onProgress(++completed, results.size)
            }
        } finally { app.endPreparation(owner) }
    }
    if (mode != FileTranslationEngine.API) notes += if (!allReviewsCompleted) "Google ML Kit 번역 · 자동 검사는 의미 정확성을 보증하지 않습니다."
        else "Google ML Kit 번역 + 준비된 AI 모델 검토 · 최종 내용은 원음과 대조하세요."
    if (mode == FileTranslationEngine.API) notes += "설정한 API 경로 · " + (app.translationApiService.states.value[target]?.label ?: "기기 내 번역")
    if (mode == FileTranslationEngine.API && !allApiCompleted) notes += "일부 구간은 기기 내 번역으로 대체했습니다."
    FileScriptTranslation(results, notes.toList(), if (allApiCompleted) FileTranslationEngine.API else if (allReviewsCompleted) FileTranslationEngine.GEMMA else FileTranslationEngine.MLKIT)
}

internal fun fileTranslationChunks(text: String, maximum: Int = 500): List<String> {
    require(maximum >= 2)
    val result = mutableListOf<String>()
    var from = 0
    while (from < text.length) {
        var end = minOf(from + maximum, text.length)
        if (end < text.length) {
            val boundary = (end - 1 downTo from + maximum / 2).firstOrNull { text[it].isWhitespace() || text[it] in ".!?。！？" }
            if (boundary != null) end = boundary + 1
            if (Character.isHighSurrogate(text[end - 1])) end--
        }
        text.substring(from, end).trim().takeIf(String::isNotEmpty)?.let(result::add)
        from = end
    }
    return result
}

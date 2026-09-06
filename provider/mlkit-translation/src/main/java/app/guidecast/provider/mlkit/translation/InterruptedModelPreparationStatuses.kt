package app.guidecast.provider.mlkit.translation

import app.guidecast.core.translation.LanguageModelStatus
import app.guidecast.core.translation.ModelReadiness

/** The observer ended, not necessarily Google's non-cancellable file task. Never claim READY. */
internal fun interruptedModelPreparationStatuses(
    statuses: List<LanguageModelStatus>,
    languageTags: Set<String>,
): List<LanguageModelStatus> = statuses.map { status ->
    if (status.languageTag in languageTags && status.readiness in
        setOf(ModelReadiness.DOWNLOADING, ModelReadiness.VERIFYING)
    ) status.copy(
        readiness = ModelReadiness.FAILED,
        errorMessage = "준비 확인이 중단되었습니다. 다시 준비하면 설치 상태를 확인합니다.",
    ) else status
}

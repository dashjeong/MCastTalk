package app.guidecast.transmitter

import app.guidecast.core.stream.MAX_SIMULTANEOUS_TRANSLATED_CHANNELS
import app.guidecast.provider.gemma.translation.GemmaTranslationProvider

internal data class TranslationProviderPresentation(
    val providerLabel: String,
    val notice: String?,
)

/** Human-readable disclosure for the exact provider route used by each selected language. */
internal fun translationProviderPresentation(
    translationLanguages: List<String>,
    gemmaActive: Boolean,
    mlKitReady: Boolean,
    displayName: (String) -> String,
): TranslationProviderPresentation {
    require(translationLanguages.isNotEmpty() && translationLanguages.size <= MAX_SIMULTANEOUS_TRANSLATED_CHANNELS)
    require(translationLanguages.distinct().size == translationLanguages.size)

    if (!gemmaActive) return TranslationProviderPresentation("ML Kit", null)
    if (translationLanguages.size == 1) {
        return TranslationProviderPresentation(
            providerLabel = "Gemma",
            notice = if (mlKitReady) {
                null
            } else {
                "ML Kit 대체 모델 미준비 · Gemma 오류 시 이 통역 채널이 중단될 수 있습니다."
            },
        )
    }

    val gemmaNames = translationLanguages.filter(GemmaTranslationProvider::supportsTargetLanguage)
        .joinToString("/") { displayName(it).substringBefore(" ·") }
    val independentNames = translationLanguages.filterNot(GemmaTranslationProvider::supportsTargetLanguage)
        .joinToString("/") { displayName(it).substringBefore(" ·") }
    return TranslationProviderPresentation(
        providerLabel = "공유 Gemma · $gemmaNames" + if (independentNames.isNotEmpty()) " / $independentNames ML Kit 독립 경로" else "",
        notice = buildString {
            append("$gemmaNames: Gemma 1개 공정 순환 처리")
            append(if (mlKitReady) "(실패 시 ML Kit)" else "(대체 모델 준비 확인 필요)")
            if (independentNames.isNotEmpty()) append(" · $independentNames: Gemma와 분리된 ML Kit 경로")
        },
    )
}

internal fun preparingTranslationProviderLabel(
    translationLanguages: List<String>,
    useGemma: Boolean,
    displayName: (String) -> String,
): String {
    require(translationLanguages.isNotEmpty() && translationLanguages.size <= MAX_SIMULTANEOUS_TRANSLATED_CHANNELS)
    return when {
        !useGemma -> "ML Kit 준비 중"
        translationLanguages.size == 1 -> "Gemma 준비 중"
        else -> {
            "공유 Gemma 준비 중 · 언어별 준비 상태 확인"
        }
    }
}

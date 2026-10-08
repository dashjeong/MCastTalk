package app.guidecast.transmitter

/** Terminal output does not prove audio delivery or source/translation alignment. */
internal fun nativeMissingCaptionLabel(isNative: Boolean, outputState: String?, hasTranslation: Boolean): String? {
    if (!isNative || hasTranslation) return null
    return when (outputState) {
        "GENERATED" -> "통역 처리 완료 · 번역 자막 없음"
        "CANCELLED" -> "통역 취소됨 · 번역 자막 없음"
        "INCOMPLETE" -> "통역 미완료 · 번역 자막 없음"
        else -> null
    }
}

internal fun TranslationTranscriptLine.nativeMissingCaptionLabel(languageTag: String? = liveSegmentLanguage): String? {
    val tag = languageTag ?: return null
    if (liveSegmentLanguage.isNullOrBlank() || tag != liveSegmentLanguage) return null
    return nativeMissingCaptionLabel(true, liveOutputState?.name, !translations[tag].isNullOrBlank())
}

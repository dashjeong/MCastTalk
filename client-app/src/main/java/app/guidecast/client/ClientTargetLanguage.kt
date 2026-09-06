package app.guidecast.client

import android.icu.text.Transliterator

enum class ClientTargetLanguage(
    val languageTag: String,
    val translationTag: String,
    val ttsTag: String,
    val displayName: String,
    val detail: String,
) {
    JAPANESE("ja-JP", "ja", "ja", "일본어", "日本語 · Moonshine Kokoro"),
    CHINESE("zh-CN", "zh", "zh", "중국어", "简体中文 · Moonshine Kokoro"),
    DUTCH("nl-NL", "nl", "nl", "네덜란드어", "Nederlands · Moonshine Piper"),
    HONG_KONG(
        "zh-Hant-HK",
        "zh",
        "zh",
        "홍콩 번체 중국어",
        "繁體中文 표기 · 중국어 Moonshine 음성",
    );

    fun postProcess(text: String): String = when (this) {
        HONG_KONG -> runCatching {
            Transliterator.getInstance("Simplified-Traditional").transliterate(text)
        }.getOrDefault(text)
        else -> text
    }

    companion object {
        fun fromId(id: String?): ClientTargetLanguage = entries.firstOrNull {
            it.name.equals(id, ignoreCase = true) ||
                it.languageTag.equals(id, ignoreCase = true)
        } ?: JAPANESE
    }
}

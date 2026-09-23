package app.guidecast.provider.mlkit.translation

/** Translation input support only; this catalog must not imply microphone/STT support. */
internal val ML_KIT_SOURCE_WARMUP_TEXT = mapOf(
    "ar" to "مرحبا",
    "de" to "Hallo",
    "en" to "Hello",
    "es" to "Hola",
    "fr" to "Bonjour",
    "ja" to "こんにちは",
    "ko" to "안녕하세요",
    "zh" to "你好",
)

internal fun mlKitWarmupSourceText(sourceLanguageTag: String): String = requireNotNull(
    ML_KIT_SOURCE_WARMUP_TEXT[sourceLanguageTag.toMlKitLanguage()],
) { "ML Kit warm-up source is unavailable for $sourceLanguageTag" }

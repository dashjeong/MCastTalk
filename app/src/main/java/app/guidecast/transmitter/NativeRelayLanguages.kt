package app.guidecast.transmitter

import java.util.Locale

/** Relay audio choices are independent of installed offline recognizers and translators. */
internal val NATIVE_RELAY_TRANSLATION_LANGUAGE_OPTIONS: List<TranslationLanguageOption> =
    TRANSLATION_LANGUAGE_OPTIONS + listOf(
        TranslationLanguageOption("af", nativeRelayLocaleLabel("af")),
        TranslationLanguageOption("ak", "아칸어 · Akan"),
        TranslationLanguageOption("sq", nativeRelayLocaleLabel("sq")),
        TranslationLanguageOption("am", nativeRelayLocaleLabel("am")),
        TranslationLanguageOption("hy", nativeRelayLocaleLabel("hy")),
        TranslationLanguageOption("az", nativeRelayLocaleLabel("az")),
        TranslationLanguageOption("eu", nativeRelayLocaleLabel("eu")),
        TranslationLanguageOption("be", nativeRelayLocaleLabel("be")),
        TranslationLanguageOption("bn", nativeRelayLocaleLabel("bn")),
        TranslationLanguageOption("bg", nativeRelayLocaleLabel("bg")),
        TranslationLanguageOption("my", nativeRelayLocaleLabel("my")),
        TranslationLanguageOption("ca", nativeRelayLocaleLabel("ca")),
        TranslationLanguageOption("hr", nativeRelayLocaleLabel("hr")),
        TranslationLanguageOption("cs", nativeRelayLocaleLabel("cs")),
        TranslationLanguageOption("da", nativeRelayLocaleLabel("da")),
        TranslationLanguageOption("et", nativeRelayLocaleLabel("et")),
        TranslationLanguageOption("fi", nativeRelayLocaleLabel("fi")),
        TranslationLanguageOption("fr", "프랑스어 · Français"),
        TranslationLanguageOption("gl", nativeRelayLocaleLabel("gl")),
        TranslationLanguageOption("ka", nativeRelayLocaleLabel("ka")),
        TranslationLanguageOption("de", "독일어 · Deutsch"),
        TranslationLanguageOption("el", nativeRelayLocaleLabel("el")),
        TranslationLanguageOption("gu", nativeRelayLocaleLabel("gu")),
        TranslationLanguageOption("ha", nativeRelayLocaleLabel("ha")),
        TranslationLanguageOption("he", nativeRelayLocaleLabel("he")),
        TranslationLanguageOption("hi", nativeRelayLocaleLabel("hi")),
        TranslationLanguageOption("hu", nativeRelayLocaleLabel("hu")),
        TranslationLanguageOption("is", nativeRelayLocaleLabel("is")),
        TranslationLanguageOption("id", nativeRelayLocaleLabel("id")),
        TranslationLanguageOption("it", nativeRelayLocaleLabel("it")),
        TranslationLanguageOption("kn", nativeRelayLocaleLabel("kn")),
        TranslationLanguageOption("kk", nativeRelayLocaleLabel("kk")),
        TranslationLanguageOption("km", nativeRelayLocaleLabel("km")),
        TranslationLanguageOption("rw", "키냐르완다어 · Kinyarwanda"),
        TranslationLanguageOption("lo", nativeRelayLocaleLabel("lo")),
        TranslationLanguageOption("lv", nativeRelayLocaleLabel("lv")),
        TranslationLanguageOption("lt", nativeRelayLocaleLabel("lt")),
        TranslationLanguageOption("mk", nativeRelayLocaleLabel("mk")),
        TranslationLanguageOption("ms", nativeRelayLocaleLabel("ms")),
        TranslationLanguageOption("ml", nativeRelayLocaleLabel("ml")),
        TranslationLanguageOption("mr", nativeRelayLocaleLabel("mr")),
        TranslationLanguageOption("mn", nativeRelayLocaleLabel("mn")),
        TranslationLanguageOption("ne", nativeRelayLocaleLabel("ne")),
        TranslationLanguageOption("nb", "노르웨이어 · Norsk bokmål"),
        TranslationLanguageOption("fa", nativeRelayLocaleLabel("fa")),
        TranslationLanguageOption("pl", nativeRelayLocaleLabel("pl")),
        TranslationLanguageOption("pt-BR", "포르투갈어(브라질) · Português (Brasil)"),
        TranslationLanguageOption("pt-PT", "포르투갈어(포르투갈) · Português (Portugal)"),
        TranslationLanguageOption("pa", nativeRelayLocaleLabel("pa")),
        TranslationLanguageOption("ro", nativeRelayLocaleLabel("ro")),
        TranslationLanguageOption("sr", nativeRelayLocaleLabel("sr")),
        TranslationLanguageOption("sd", nativeRelayLocaleLabel("sd")),
        TranslationLanguageOption("si", nativeRelayLocaleLabel("si")),
        TranslationLanguageOption("sk", nativeRelayLocaleLabel("sk")),
        TranslationLanguageOption("sl", nativeRelayLocaleLabel("sl")),
        TranslationLanguageOption("sw", nativeRelayLocaleLabel("sw")),
        TranslationLanguageOption("sv", nativeRelayLocaleLabel("sv")),
        TranslationLanguageOption("ta", nativeRelayLocaleLabel("ta")),
        TranslationLanguageOption("te", nativeRelayLocaleLabel("te")),
        TranslationLanguageOption("th", "태국어 · ไทย"),
        TranslationLanguageOption("tr", nativeRelayLocaleLabel("tr")),
        TranslationLanguageOption("uk", nativeRelayLocaleLabel("uk")),
        TranslationLanguageOption("ur", nativeRelayLocaleLabel("ur")),
        TranslationLanguageOption("uz", nativeRelayLocaleLabel("uz")),
        TranslationLanguageOption("zu", nativeRelayLocaleLabel("zu")),
    )

private fun nativeRelayLocaleLabel(tag: String): String {
    val language = Locale.forLanguageTag(tag)
    return "${language.getDisplayLanguage(Locale.KOREAN)} · ${language.getDisplayLanguage(language)}"
}

private val nativeSourceRegions = mapOf(
    "fr" to "fr-FR",
    "de" to "de-DE",
    "th" to "th-TH",
    "ru" to "ru-RU",
    "vi" to "vi-VN",
    "nl" to "nl-NL",
    "it" to "it-IT",
 )

internal val NATIVE_RELAY_SOURCE_LANGUAGE_OPTIONS: List<SourceLanguageOption> =
    SOURCE_LANGUAGE_OPTIONS + NATIVE_RELAY_TRANSLATION_LANGUAGE_OPTIONS
        .filter { option -> SOURCE_LANGUAGE_OPTIONS.none {
            normalizeSourceLanguage(it.languageTag) == normalizeSourceLanguage(option.languageTag)
        } || option.languageTag == "zh-TW" }
        .map { SourceLanguageOption(nativeSourceRegions[it.languageTag] ?: it.languageTag, it.label) }

internal val NATIVE_RELAY_LANGUAGE_NAMES: Map<String, String> =
    NATIVE_RELAY_TRANSLATION_LANGUAGE_OPTIONS.associate { it.languageTag to it.label.substringBefore(" · ") }

internal fun nativeRelayTargetLanguageOptions(sourceLanguageTag: String): List<TranslationLanguageOption> {
    require(NATIVE_RELAY_SOURCE_LANGUAGE_OPTIONS.any { it.languageTag == sourceLanguageTag }) {
        "지원하지 않는 중계 발화 언어입니다."
    }
    val source = normalizeSourceLanguage(sourceLanguageTag)
    return NATIVE_RELAY_TRANSLATION_LANGUAGE_OPTIONS.filterNot { normalizeSourceLanguage(it.languageTag) == source }
}

internal fun requireNativeRelayLanguageSelection(source: String, targets: List<String>) {
    val available = nativeRelayTargetLanguageOptions(source).map { it.languageTag }.toSet()
    require(targets.size in 1..MAX_RELAY_LANGUAGES && targets.distinct().size == targets.size && targets.all { it in available }) {
        "발화 언어와 다른 중계 통역 언어를 1~5개 선택하세요."
    }
}

internal fun nativeRelayLanguageName(tag: String): String = requireNotNull(NATIVE_RELAY_LANGUAGE_NAMES[tag])

internal fun recommendedNativeRelayLanguages(source: String): Set<String> {
    val available = nativeRelayTargetLanguageOptions(source)
    val ordered = PRIMARY_TRANSLATION_LANGUAGE_TAGS + available.map { it.languageTag }
    return ordered.distinct().filter { tag -> available.any { it.languageTag == tag } }
        .take(MAX_RELAY_LANGUAGES).toCollection(linkedSetOf())
}

internal fun toggleNativeRelayLanguageSelection(current: Set<String>, tag: String, source: String): TranslationLanguageSelectionResult {
    require(nativeRelayTargetLanguageOptions(source).any { it.languageTag == tag })
    val ordered = current.toCollection(linkedSetOf())
    if (ordered.remove(tag)) return TranslationLanguageSelectionResult(ordered)
    if (ordered.size >= MAX_RELAY_LANGUAGES) return TranslationLanguageSelectionResult(ordered,
        "동시 통역은 최대 ${MAX_RELAY_LANGUAGES}개 언어까지 선택할 수 있습니다.")
    ordered.add(tag)
    return TranslationLanguageSelectionResult(ordered)
}

private fun nativeRelayLanguageMatches(tag: String, label: String, query: String): Boolean {
    val terms = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
    val searchable = "$tag $label".lowercase(Locale.ROOT)
    return terms.all { it in searchable }
}

/** Keep selected choices visible when the compact default list is shown. */
internal fun visibleNativeRelayTargetLanguages(source: String, query: String, showAll: Boolean,
    selected: Collection<String>): List<TranslationLanguageOption> {
    val available = nativeRelayTargetLanguageOptions(source)
    val primary = recommendedNativeRelayLanguages(source)
    return if (query.isNotBlank()) available.filter { nativeRelayLanguageMatches(it.languageTag, it.label, query) }
    else if (showAll) available else available.filter {
        it.languageTag in primary || it.languageTag in selected
    }
}

internal fun visibleNativeRelaySourceLanguages(query: String, showAll: Boolean,
    selected: String): List<SourceLanguageOption> =
    if (query.isNotBlank()) NATIVE_RELAY_SOURCE_LANGUAGE_OPTIONS.filter { nativeRelayLanguageMatches(it.languageTag, it.label, query) }
    else if (showAll) NATIVE_RELAY_SOURCE_LANGUAGE_OPTIONS else NATIVE_RELAY_SOURCE_LANGUAGE_OPTIONS.filter {
        it.languageTag in SOURCE_LANGUAGE_OPTIONS.take(5).map { option -> option.languageTag } || it.languageTag == selected
    }

internal fun nativeRelayLanguageSupportNotice(provider: TranslationApiProvider): String =
    if (provider == TranslationApiProvider.OPENAI_REALTIME)
        "선택한 언어로 통역하도록 요청합니다. 언어별 음성·자막 품질과 지역 발음은 사용 전에 확인해 주세요."
    else "언어·발화 환경에 따라 통역 품질이 달라집니다. 중요한 방송 전에는 음성·자막을 확인해 주세요."

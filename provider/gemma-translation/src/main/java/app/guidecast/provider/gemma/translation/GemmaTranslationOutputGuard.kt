package app.guidecast.provider.gemma.translation

import java.text.Normalizer
import java.util.Locale

/**
 * Reject only a narrow, observable failure: a Korean sentence copied into a non-Korean target.
 * This is not language identification or a semantic quality score. Names, short labels, numbers,
 * same-script pairs and translated sentences retaining Korean names/quotations remain eligible.
 */
internal fun requireGemmaTranslationIsNotCopiedSource(
    source: String,
    translated: String,
    sourceLanguageTag: String,
    targetLanguageTag: String,
) {
    val sourceLanguage = sourceLanguageTag.substringBefore('-').lowercase(Locale.ROOT)
    val targetLanguage = targetLanguageTag.substringBefore('-').lowercase(Locale.ROOT)
    if (sourceLanguage != "ko" || targetLanguage !in setOf("en", "es", "ar", "ja", "zh")) return
    if (!KOREAN_SENTENCE_END.containsMatchIn(source) || source.trim().split(WORD_BOUNDARY).size < 4) return
    val original = normalizedCharacters(source)
    val output = normalizedCharacters(translated)
    val originalLetters = original.filter(Char::isLetter)
    val outputLetters = output.filter(Char::isLetter)
    if (originalLetters.count(::isHangul) < 12 || outputLetters.count(::isHangul) < 12) return
    if (originalLetters.count(::isHangul) * 10 < originalLetters.length * 8 ||
        outputLetters.count(::isHangul) * 10 < outputLetters.length * 9) return
    // An actual target-language clause or attribution can legitimately surround a source quote.
    if (outputLetters.count { isTargetScript(it, targetLanguage) } >= 3) return
    val maximumChanges = (original.length / 5).coerceAtLeast(2)
    check(!withinEditDistance(original, output, maximumChanges)) { "GEMMA_UNTRANSLATED_SOURCE_COPY" }
}

private fun normalizedCharacters(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
    .filter(Char::isLetterOrDigit).lowercase(Locale.ROOT)

private fun isHangul(character: Char): Boolean = Character.UnicodeScript.of(character.code) == Character.UnicodeScript.HANGUL

private fun isTargetScript(character: Char, target: String): Boolean {
    val script = Character.UnicodeScript.of(character.code)
    return when (target) {
        "en", "es" -> script == Character.UnicodeScript.LATIN
        "ar" -> script == Character.UnicodeScript.ARABIC
        "zh" -> script == Character.UnicodeScript.HAN
        "ja" -> script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA ||
            script == Character.UnicodeScript.KATAKANA
        else -> false
    }
}

private fun withinEditDistance(left: String, right: String, maximum: Int): Boolean {
    if (kotlin.math.abs(left.length - right.length) > maximum) return false
    var previous = IntArray(right.length + 1) { it }
    var current = IntArray(right.length + 1)
    for (leftIndex in left.indices) {
        current[0] = leftIndex + 1
        var smallest = current[0]
        for (rightIndex in right.indices) {
            current[rightIndex + 1] = minOf(current[rightIndex] + 1, previous[rightIndex + 1] + 1,
                previous[rightIndex] + if (left[leftIndex] == right[rightIndex]) 0 else 1)
            smallest = minOf(smallest, current[rightIndex + 1])
        }
        if (smallest > maximum) return false
        val recycled = previous
        previous = current
        current = recycled
    }
    return previous[right.length] <= maximum
}

private val KOREAN_SENTENCE_END = Regex("[다요][.!?。！？…\\s]*$")
private val WORD_BOUNDARY = Regex("\\s+")

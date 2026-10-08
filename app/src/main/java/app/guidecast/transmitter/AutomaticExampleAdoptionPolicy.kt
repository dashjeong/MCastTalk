package app.guidecast.transmitter

import java.text.Normalizer
import java.util.Locale

internal const val AUTOMATIC_EXAMPLE_ADOPTION_POLICY_VERSION = 2

/** A finite equivalence boundary, not a general translation-quality or source-meaning verifier.
 * Changed words, ordering, clauses and unknown languages require human review. In particular,
 * a teacher that fixes a genuinely wrong offline draft cannot authorize its own automatic adoption.
 */
internal fun automaticExampleEquivalentRefinement(offline: String, teacher: String, target: String): Boolean {
    if (offline.length !in 1..500 || teacher.length !in 1..500 ||
        !automaticExampleTargetScript(offline, target) || !automaticExampleTargetScript(teacher, target)) return false
    return refinementTokens(offline, target) == refinementTokens(teacher, target)
}

/** Unknown or mixed scripts fail closed for automatic use; a reviewed example has its separate path. */
internal fun automaticExampleTargetScript(text: String, target: String): Boolean {
    if (text.length !in 1..500) return false
    val scripts = when (target.trim().lowercase(Locale.ROOT).substringBefore('-')) {
        "en", "fr", "de", "es", "vi", "nl", "pt", "it", "tr", "id", "ro", "pl", "cs", "sv", "da", "fi", "no" -> setOf(Character.UnicodeScript.LATIN)
        "ko" -> setOf(Character.UnicodeScript.HANGUL)
        "ja" -> setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)
        "zh" -> setOf(Character.UnicodeScript.HAN)
        "ru", "uk", "bg" -> setOf(Character.UnicodeScript.CYRILLIC)
        "ar" -> setOf(Character.UnicodeScript.ARABIC)
        "hi" -> setOf(Character.UnicodeScript.DEVANAGARI)
        else -> return false
    }
    val letters = text.codePoints().toArray().filter(Character::isLetter)
    return letters.isNotEmpty() && letters.all { Character.UnicodeScript.of(it) in scripts }
}

private val refinementToken = Regex("[\\p{L}\\p{M}]+(?:'[\\p{L}\\p{M}]+)*|[0-9]+(?:[.,:/-][0-9]+)*|[^\\s\\p{Z}]")
private val englishContractions = mapOf(
    "i'm" to listOf("i", "am"),
    "you're" to listOf("you", "are"), "we're" to listOf("we", "are"), "they're" to listOf("they", "are"),
    "i've" to listOf("i", "have"), "you've" to listOf("you", "have"), "we've" to listOf("we", "have"), "they've" to listOf("they", "have"),
    "i'll" to listOf("i", "will"), "you'll" to listOf("you", "will"), "he'll" to listOf("he", "will"),
    "she'll" to listOf("she", "will"), "it'll" to listOf("it", "will"), "we'll" to listOf("we", "will"), "they'll" to listOf("they", "will"),
    "isn't" to listOf("is", "not"), "aren't" to listOf("are", "not"), "wasn't" to listOf("was", "not"), "weren't" to listOf("were", "not"),
    "haven't" to listOf("have", "not"), "hasn't" to listOf("has", "not"), "hadn't" to listOf("had", "not"),
    "don't" to listOf("do", "not"), "doesn't" to listOf("does", "not"), "didn't" to listOf("did", "not"),
    "can't" to listOf("can", "not"), "couldn't" to listOf("could", "not"), "won't" to listOf("will", "not"),
    "wouldn't" to listOf("would", "not"), "shouldn't" to listOf("should", "not"), "mustn't" to listOf("must", "not"),
)

private fun refinementTokens(text: String, target: String): List<String> {
    val canonical = Normalizer.normalize(text.trim(), Normalizer.Form.NFC)
        .replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
    val tokens = refinementToken.findAll(canonical).map { it.value }.toMutableList()
    // An optional terminal stop/emphasis changes presentation only in this finite policy.
    // Question marks, internal punctuation, number order and clause boundaries are never removed.
    while (tokens.isNotEmpty() && tokens.last() in setOf(".", "!", "。", "！")) tokens.removeAt(tokens.lastIndex)
    if (!target.substringBefore('-').equals("en", ignoreCase = true)) return tokens
    return tokens.flatMap { token ->
        val lower = token.lowercase(Locale.ROOT)
        val expanded = englishContractions[lower]
        // Do not treat arbitrary capitalized names or ambiguous 's/'d as grammar.
        if (expanded == null || (token != lower && token != lower.replaceFirstChar { it.uppercaseChar() } && token != "I'm" && token != "I've" && token != "I'll")) listOf(token)
        else expanded.mapIndexed { index, word ->
            if (word == "i") "I" else if (index == 0 && token.first().isUpperCase()) word.replaceFirstChar { it.uppercaseChar() } else word
        }
    }
}

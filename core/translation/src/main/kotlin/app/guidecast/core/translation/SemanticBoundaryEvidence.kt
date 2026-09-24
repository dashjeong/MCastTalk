package app.guidecast.core.translation

import java.util.Locale

/**
 * Small offline vetoes, not a parser or a claim to understand arbitrary sentence meaning.
 *
 * The recognizer's punctuation and acoustic pauses are fallible. These rules retain common
 * dependencies until their complement arrives. Unsupported/ambiguous constructions still need
 * speech-corpus evaluation; this layer must never invent a missing word or change a negation.
 */
internal fun List<String>.hasIncompleteEnglishMeaning(): Boolean {
    if (isEmpty()) return true
    // Evaluate the candidate's final sentence. An earlier complete sentence must not hide a later
    // "If ..." dependency, and the caller can still release that earlier safe boundary separately.
    val precedingBoundary = dropLast(1).indexOfLast { token ->
        val normalized = token.trimEnd('"', '\'', '”', '’', ')', ']', '}')
        normalized.lastOrNull() in setOf('.', '!', '?') &&
            !normalized.matches(ENGLISH_ABBREVIATION) &&
            !normalized.matches(ENGLISH_DECIMAL)
    }
    if (precedingBoundary >= 0) return drop(precedingBoundary + 1).hasIncompleteEnglishMeaning()
    val words = map(String::englishBoundaryWord)
    val last = words.last()
    if (words.size == 1 && last in ENGLISH_COMPLETE_REPLIES) return false
    // A pronoun can be an object ("I heard you") or the subject at the end of an inverted
    // question ("Who are they?"). It is not, by itself, evidence of a missing predicate.
    // Retain only the clearly unfinished subject/auxiliary and clause-introducer forms.
    if (last in ENGLISH_SUBJECTS && (
        words.size == 1 ||
            (words.size == 2 && words.first() in ENGLISH_QUESTION_AUXILIARIES) ||
            words.getOrNull(words.lastIndex - 1) in ENGLISH_CLAUSE_INTRODUCERS
        )) return true
    if (last in ENGLISH_DEPENDENT_TAILS) return true
    if (last == "point" && words.getOrNull(words.lastIndex - 1).isSpokenNumber()) return true
    if (last == "minus" || last == "plus") return true
    if (last().trimEnd('"', '\'', '”', '’', ')', ']', '}').matches(ENGLISH_ABBREVIATION)) return true

    val notOnly = words.indices.lastOrNull { index ->
        words[index] == "not" && words.getOrNull(index + 1) in setOf("only", "just")
    }
    if (notOnly != null && words.drop(notOnly + 2).none { it == "but" }) return true

    val opener = words.first()
    if (opener !in ENGLISH_DEPENDENT_OPENERS) return false
    // An explicit clause separator needs a usable continuation, not merely a comma after "if".
    val comma = indexOfFirst { token -> token.endsWith(',') || token.endsWith(';') }
    if (comma >= 2 && size - comma - 1 >= 2) return false
    val then = words.indexOf("then")
    if (then >= 3 && words.size - then - 1 >= 2) return false
    val please = words.indexOf("please")
    if (please >= 3 && words.size - please - 1 >= 2) return false
    // Common unpunctuated condition -> consequence: "if it rains we will wait inside". Requiring
    // a later subject plus a future/modal auxiliary and its verb is narrower than a token count.
    return words.indices.none { index ->
        index >= 3 && index + 2 < words.size &&
            words[index] in ENGLISH_SUBJECTS &&
            words[index + 1] in setOf("will", "shall", "would") &&
            words[index + 2] !in ENGLISH_DEPENDENT_TAILS
    }
}

/** A detached reported-speech suffix belongs to the apparent sentence on its left. */
internal fun hasKoreanDependentRightContext(tokens: List<String>, leftWord: String): Boolean =
    tokens.firstOrNull()?.trimStart('"', '\'', '“', '‘')?.let { token ->
        KOREAN_REPORTED_SPEECH_PREFIXES.any(token::startsWith) ||
            // "좋지 못해서" belongs together, but "기량이 떨어져. 못 쉬어서" starts
            // a postposed explanation. A negative word in a new clause must not hold every
            // preceding finite sentence forever. Keep auxiliary dependencies on -지/-고.
            ((leftWord.endsWith("지") || leftWord.endsWith("고")) &&
                KOREAN_AUXILIARY_PREFIXES.any(token::startsWith))
    } == true

/** Never publish the interior of an open reported-speech quote as an independent claim. */
internal fun List<String>.hasUnclosedSpeechQuote(): Boolean {
    val text = joinToString(" ")
    var straightQuoteOpen = false
    var singleQuoteOpen = false
    var curvedQuoteDepth = 0
    for ((index, character) in text.withIndex()) {
        when (character) {
            '"' -> straightQuoteOpen = !straightQuoteOpen
            '\'' -> if (!(text.getOrNull(index - 1)?.isLetter() == true &&
                    text.getOrNull(index + 1)?.isLetter() == true)) {
                singleQuoteOpen = !singleQuoteOpen
            }
            '“', '‘' -> curvedQuoteDepth++
            '”', '’' -> curvedQuoteDepth = (curvedQuoteDepth - 1).coerceAtLeast(0)
        }
    }
    return straightQuoteOpen || singleQuoteOpen || curvedQuoteDepth > 0
}

internal fun String.withoutSpeculativeIncompleteEnglishPunctuation(): String {
    val text = trim()
    if (!text.split(Regex("\\s+")).hasIncompleteEnglishMeaning()) return text
    // Retain abbreviation periods: joining "Dr." to a following name must not erase its spelling.
    val lastToken = text.substringAfterLast(' ')
    if (lastToken.matches(ENGLISH_ABBREVIATION)) return text
    return text.trimEnd('.', '!', '?', ',', ';', ':')
}

private fun String.englishBoundaryWord(): String =
    trim('"', '\'', '“', '”', '‘', '’', '(', ')', '[', ']', '{', '}', '.', '!', '?', ',', ';', ':')
        .lowercase(Locale.ROOT)

private fun String?.isSpokenNumber(): Boolean = this != null &&
    (any(Char::isDigit) || this in ENGLISH_NUMBER_WORDS)

private val ENGLISH_DEPENDENT_OPENERS = setOf(
    "if", "unless", "although", "though", "because", "whereas", "whenever", "until",
)
private val ENGLISH_DEPENDENT_TAILS = setOf(
    "am", "is", "are", "was", "were", "be",
    "a", "an", "the", "my", "your", "his", "our", "their", "its",
    "and", "or", "but", "because", "if", "unless", "although", "than", "as",
    "to", "from", "with", "without", "for", "of", "at", "by", "into", "onto", "between",
    "not", "don't", "doesn't", "didn't", "isn't", "aren't", "wasn't", "weren't",
    "cannot", "can't", "won't", "wouldn't", "shouldn't", "mustn't", "couldn't",
)
private val ENGLISH_COMPLETE_REPLIES = setOf("yes", "no", "okay", "ok", "thanks", "hello", "goodbye")
private val ENGLISH_SUBJECTS = setOf("i", "you", "he", "she", "it", "we", "they")
private val ENGLISH_QUESTION_AUXILIARIES = setOf(
    "am", "is", "are", "was", "were", "do", "does", "did", "can", "could", "will", "would",
    "shall", "should", "must", "have", "has", "had",
)
private val ENGLISH_CLAUSE_INTRODUCERS = ENGLISH_DEPENDENT_OPENERS + setOf("that", "whether")
private val ENGLISH_NUMBER_WORDS = setOf(
    "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
    "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
    "nineteen", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
    "hundred", "thousand", "million", "billion",
)
private val ENGLISH_ABBREVIATION = Regex("(?i)(?:(?:[a-z]\\.){2,}|(?:dr|mr|mrs|ms|no|vs|etc)\\.)")
private val ENGLISH_DECIMAL = Regex("[+-]?\\d+\\.\\d+%?\\.?")
private val KOREAN_REPORTED_SPEECH_PREFIXES = setOf(
    "라고", "라는", "라며", "라던", "라니",
)
private val KOREAN_AUXILIARY_PREFIXES = setOf(
    // “좋지 않은/못한 …” is one dependent meaning, not an affirmative “좋지”.
    "않", "못", "말아", "말고", "싶",
)

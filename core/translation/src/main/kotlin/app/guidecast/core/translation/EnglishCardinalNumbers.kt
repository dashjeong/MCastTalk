package app.guidecast.core.translation

import java.util.Locale

/**
 * Comparison notation only, never a translation or a replacement source.
 * Supports unambiguous English cardinal integers 0..999999. Unsupported numeric wording
 * rejects the comparison instead of consuming only its convenient prefix (one million != 1).
 */
internal object EnglishCardinalNumbers {
    private val small = listOf("zero", "one", "two", "three", "four", "five", "six", "seven",
        "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
        "sixteen", "seventeen", "eighteen", "nineteen").withIndex().associate { it.value to it.index }
    private val tens = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
    private val signs = mapOf("minus" to "-", "negative" to "-", "plus" to "+", "positive" to "+")
    private val unsupported = setOf("million", "millions", "billion", "billions", "trillion", "trillions",
        "quadrillion", "quadrillions", "half", "halves", "quarter", "quarters", "dozen", "dozens",
        "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth",
        "tenth", "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth",
        "seventeenth", "eighteenth", "nineteenth", "twentieth", "thirtieth", "fortieth",
        "fiftieth", "sixtieth", "seventieth", "eightieth", "ninetieth", "hundredth", "thousandth")
    private val unsupportedScaleOrFraction = setOf("million", "millions", "billion", "billions",
        "trillion", "trillions", "quadrillion", "quadrillions", "half", "halves",
        "quarter", "quarters", "dozen", "dozens")
    private val words = Regex("[\\p{L}\\p{N}_]+(?:['’][\\p{L}\\p{N}_]+)*")
    private val digitOrdinal = Regex("[0-9]+(?:st|nd|rd|th)")
    private val plainGap = Regex("[ \\t]+")
    private val hyphenGap = Regex("[ \\t]*[-\\u2011][ \\t]*")
    // Broader only for rejecting unsupported numeric affixes; never interpret a range dash
    // as a tens/units join while accepting a cardinal value.
    private val numericAffixGap = Regex("[ \\t]*[\\p{Pd}\\u2212][ \\t]*")

    fun normalizeForComparison(text: String): String? {
        val tokens = words.findAll(text).toList()
        val output = StringBuilder(text.length)
        var cursor = 0
        var index = 0
        while (index < tokens.size) {
            val first = tokens[index]
            val initial = first.value.lowercase(Locale.ROOT)
            if (digitOrdinal.matches(initial)) return null
            if (initial in unsupportedScaleOrFraction && followsNumericPhrase(tokens, index, text)) return null
            if (!isNumberWord(initial) && initial !in signs) { index++; continue }
            if (initial in signs) {
                val next = tokens.getOrNull(index + 1)
                val adjacent = next != null &&
                    plainGap.matches(text.substring(first.range.last + 1, next.range.first))
                val nextWord = next?.value?.lowercase(Locale.ROOT)
                if (adjacent && nextWord != null && nextWord in signs) return null
                if (adjacent && next != null && next.value.all(Char::isDigit)) {
                    // Retain the complete numeric suffix (.5, :30, %, etc.) in the original
                    // string; only its explicit English sign and intervening space change.
                    output.append(text, cursor, first.range.first).append(signs.getValue(initial)).append(next.value)
                    cursor = next.range.last + 1
                    index += 2
                    continue
                }
                if (!adjacent || nextWord == null || !isNumberWord(nextWord)) {
                    // "negative results" and "positive outcome" are ordinary adjectives.
                    index++
                    continue
                }
            }
            val phrase = mutableListOf(initial)
            var last = index
            while (last + 1 < tokens.size) {
                val next = tokens[last + 1]
                val gap = text.substring(tokens[last].range.last + 1, next.range.first)
                if (!plainGap.matches(gap) && !hyphenGap.matches(gap)) break
                val word = next.value.lowercase(Locale.ROOT)
                if (word == "and" && plainGap.matches(gap) &&
                    phrase.last() != "hundred" && phrase.last() != "thousand" &&
                    beginsSeparateCardinal(tokens, last + 2, text) &&
                    parse(if (phrase.first() in signs) phrase.drop(1) else phrase) != null) {
                    // Three and five are two quantities; one hundred and five is one.
                    // Leave the conjunction untouched and parse the next item independently.
                    break
                }
                // "one second" is a duration quantity, not the ordinal twenty-second.
                // Other unsupported numeric words next to a cardinal may not be consumed partially.
                if (word == "second" && phrase == listOf("one") && plainGap.matches(gap)) break
                if (word in unsupported || word == "point") return null
                if (!isNumberWord(word) && word != "and") {
                    // Do not read one-off/one's/someone as a standalone quantity.
                    if (hyphenGap.matches(gap)) return null
                    break
                }
                if (hyphenGap.matches(gap) &&
                    (phrase.last() !in tens || (small[word] ?: -1) !in 1..9)) return null
                phrase += word
                last++
            }
            val sign = signs[phrase.first()].orEmpty()
            val cardinal = if (sign.isNotEmpty()) phrase.drop(1) else phrase
            val value = parse(cardinal) ?: return null
            output.append(text, cursor, first.range.first).append(sign).append(value)
            cursor = tokens[last].range.last + 1
            index = last + 1
        }
        return output.append(text, cursor, text.length).toString()
    }

    private fun followsNumericPhrase(tokens: List<MatchResult>, index: Int, text: String): Boolean {
        var right = index
        repeat(5) {
            val left = right - 1
            if (left < 0) return false
            val gap = text.substring(tokens[left].range.last + 1, tokens[right].range.first)
            if (!plainGap.matches(gap) && !numericAffixGap.matches(gap)) return false
            val word = tokens[left].value.lowercase(Locale.ROOT)
            if (isNumberWord(word) || word.all(Char::isDigit)) return true
            if (word !in setOf("a", "an", "and", "of", "in", "out", "per")) return false
            right = left
        }
        return false
    }

    private fun beginsSeparateCardinal(tokens: List<MatchResult>, index: Int, text: String): Boolean {
        val next = tokens.getOrNull(index) ?: return false
        if (!plainGap.matches(text.substring(tokens[index - 1].range.last + 1, next.range.first))) return false
        val word = next.value.lowercase(Locale.ROOT)
        return isNumberWord(word) || word in signs || word.all(Char::isDigit)
    }

    private fun isNumberWord(word: String): Boolean = word in small || word in tens ||
        word == "hundred" || word == "thousand"

    private fun parse(words: List<String>): Int? {
        if (words.isEmpty()) return null
        val thousand = words.indexOf("thousand")
        if (thousand < 0) return belowThousand(words)
        if (words.lastIndexOf("thousand") != thousand) return null
        val upper = belowThousand(words.take(thousand))?.takeIf { it in 1..999 } ?: return null
        var lowerWords = words.drop(thousand + 1)
        if (lowerWords.isEmpty()) return upper * 1_000
        if (lowerWords.first() == "and") lowerWords = lowerWords.drop(1)
        val lower = belowThousand(lowerWords)?.takeIf { it in 1..999 } ?: return null
        return upper * 1_000 + lower
    }

    private fun belowThousand(words: List<String>): Int? {
        val hundred = words.indexOf("hundred")
        if (hundred < 0) return belowHundred(words)
        if (hundred != 1 || words.lastIndexOf("hundred") != hundred) return null
        val upper = small[words.first()]?.takeIf { it in 1..9 } ?: return null
        var lowerWords = words.drop(2)
        if (lowerWords.isEmpty()) return upper * 100
        if (lowerWords.first() == "and") lowerWords = lowerWords.drop(1)
        val lower = belowHundred(lowerWords)?.takeIf { it in 1..99 } ?: return null
        return upper * 100 + lower
    }

    private fun belowHundred(words: List<String>): Int? = when (words.size) {
        1 -> small[words[0]] ?: tens[words[0]]
        2 -> tens[words[0]]?.let { upper -> small[words[1]]?.takeIf { it in 1..9 }?.let { upper + it } }
        else -> null
    }
}

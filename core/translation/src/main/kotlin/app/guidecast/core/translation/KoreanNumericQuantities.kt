package app.guidecast.core.translation

/**
 * Conservative translation-input notation, not an ASR correction or a Korean semantic parser.
 * Only Sino-Korean integer quantities with a supported counter are rewritten. The caller must
 * retain the original transcript. Ambiguous counters (분/일/대), native numerals, fractions,
 * decimals, zero and unsupported/oversized amounts are deliberately left unchanged.
 */
object KoreanNumericQuantities {
    private const val NUMERALS = "일이삼사오육칠팔구십백천만억조경영공"
    private const val MAX_VALUE = 999_999_999_999L
    private val particles = "(?:째(?:입니다|예요|이고|인|로|는|도)?|동안|간|부터|까지|입니다|예요|이고|이며|이라도|이라|이라는|으로|에서|에게|보다|이나|은|는|이|가|을|를|의|에|로|도|만)?"
    private val quantity = Regex(
        "(?<![\\p{L}\\p{N}])([$NUMERALS]+(?:[ \\t]+[$NUMERALS]+)*)([ \\t]*)" +
            "(킬로미터|킬로그램|퍼센트|미터|그램|개월|년|명|개|원|회|배)" +
            "(?=$|[\\s\\p{P}]|$particles(?=$|[\\s\\p{P}]))",
    )

    fun normalizeForTranslation(
        text: String,
        sourceLanguageTag: String,
        maximumOutputLength: Int = Int.MAX_VALUE,
    ): String {
        require(maximumOutputLength >= 0)
        if (!sourceLanguageTag.substringBefore('-').equals("ko", ignoreCase = true)) return text
        val normalized = quantity.replace(text) { match ->
            val rawNumber = match.groupValues[1]
            val numeral = rawNumber.filterNot(Char::isWhitespace)
            // A single attached syllable can be a word (천명: destiny, 백원: a name).
            // With no explicit spacing, require a compound numeric expression.
            if (numeral.length == 1 && match.groupValues[2].isEmpty()) return@replace match.value
            val value = parseInteger(numeral) ?: return@replace match.value
            // Single spoken digits are especially ambiguous (이 분 / 일명); do not infer them.
            if (value < 10L) return@replace match.value
            "$value${match.groupValues[3]}"
        }
        // Expanding 억 into digits can exceed the provider's validated input budget. Preserve
        // the complete original instead of truncating it or turning a valid request into failure.
        return normalized.takeIf { it.length <= maximumOutputLength } ?: text
    }

    private fun parseInteger(numeral: String): Long? {
        if (numeral.isEmpty() || numeral.length > 40) return null
        var total = 0L
        var section = 0L
        var digit: Int? = null
        var previousSmall = 10_000L
        var previousLarge = Long.MAX_VALUE
        for (character in numeral) {
            val value = "일이삼사오육칠팔구".indexOf(character) + 1
            if (value > 0) {
                if (digit != null) return null // e.g. 삼삼: not a cardinal integer grammar.
                digit = value
                continue
            }
            val unit = when (character) {
                '십' -> 10L; '백' -> 100L; '천' -> 1_000L
                '만' -> 10_000L; '억' -> 100_000_000L
                else -> return null
            }
            if (unit < 10_000L) {
                if (unit >= previousSmall) return null
                section += (digit ?: 1) * unit
                previousSmall = unit
            } else {
                if (unit >= previousLarge) return null
                val coefficient = (section + (digit ?: 0)).takeIf { it > 0 } ?: 1L
                if (coefficient > MAX_VALUE / unit || total > MAX_VALUE - coefficient * unit) return null
                total += coefficient * unit
                previousLarge = unit
                section = 0L
                previousSmall = 10_000L
            }
            digit = null
        }
        val remainder = section + (digit ?: 0)
        if (total > MAX_VALUE - remainder) return null
        return total + remainder
    }
}

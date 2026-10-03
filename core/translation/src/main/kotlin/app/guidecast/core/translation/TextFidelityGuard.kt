package app.guidecast.core.translation

import java.math.BigDecimal

/**
 * Repairs only uniquely aligned surface assets. This is not a semantic validator:
 * ambiguity, role attribution, amounts and approved exact translations are not guessed.
 * No source, history, corpus or user correction is modified.
 */
object TextFidelityGuard {
    private const val MAX_TEXT_LENGTH = 16_384
    private val number = Regex("[0-9][0-9,]*(?:\\.[0-9]+)?")
    private val validNumber = Regex("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?")
    // Only known standalone currency particles/endings. In particular, 원색,
    // 원자 and 원인 are not money, even when preceded by a digit.
    private const val WON_END = "(?=$|[\\s.,!?;:)]|(?:은|는|이|가|을|를|에|으로|과|와|도|만|의|부터|까지|보다|입니다|이다)(?=$|[\\s.,!?;:)]))"
    private val wonAmount = Regex("(?<![A-Za-z0-9,.천만억조])([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(천|만|억|조)?\\s*원$WON_END")
    private val wonLabel = Regex("(?:[0-9]|[일이삼사오육칠팔구십백천만억조])\\s*원$WON_END")
    private val yenAmount = Regex("(?<![A-Za-z0-9,.千万億兆])([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(千|万|億|兆)?\\s*(円)")
    private val signedNumber = Regex("[+＋−–－-]\\s*[0-9]")
    private val foreignOrExchangeSource = Regex("엔|円|환율|환산|환전|달러|위안|유로|파운드|JPY|USD|CNY|EUR|GBP|[\$¥￥€£]", RegexOption.IGNORE_CASE)
    private val otherCurrencyOrExchangeTarget = Regex("ウォン|元|ドル|ユーロ|ポンド|為替|換算|両替|レート|JPY|USD|CNY|EUR|GBP|KRW|[\$¥￥€£₩]", RegexOption.IGNORE_CASE)
    private val misspelling = Regex("오기|잘못\\s*(?:적|쓰|쓴|기재|표기)")
    private val verbatim = Regex("원문(?:을|은)?\\s*그대로")
    private val quoteAction = Regex("인용|기재|옮겨|적었|적어")
    private val quoteNegationOrCorrection = Regex("않|아니|말고|말라|금지|고쳐|수정|정정|바로잡|삭제")
    private val scale = mapOf("" to "", "천" to "千", "만" to "万", "억" to "億", "조" to "兆")

    fun repair(source: String, translated: String, sourceLanguageTag: String, targetLanguageTag: String): String {
        if (!sourceLanguageTag.substringBefore('-').equals("ko", ignoreCase = true)) return translated
        if (source.length > MAX_TEXT_LENGTH || translated.length > MAX_TEXT_LENGTH) return translated
        val quoted = restoreExplicitVerbatimTypo(source, translated)
        return if (targetLanguageTag.substringBefore('-').equals("ja", ignoreCase = true)) {
            restoreSingleWonLabel(source, repairAlignedJapaneseMoneyScript(source, quoted))
        } else quoted
    }

    private fun restoreSingleWonLabel(source: String, translated: String): String {
        if (foreignOrExchangeSource.containsMatchIn(source) || otherCurrencyOrExchangeTarget.containsMatchIn(translated)) return translated
        if (signedNumber.containsMatchIn(source) || signedNumber.containsMatchIn(translated)) return translated
        // Do not edit linguistic quotations, even when they look like money.
        if (source.any(::isQuoteCharacter) || translated.any(::isQuoteCharacter)) return translated
        if (wonLabel.findAll(source).count() != 1) return translated
        val sourceMoney = wonAmount.findAll(source).toList().singleOrNull() ?: return translated
        val targetMoney = yenAmount.findAll(translated).toList().singleOrNull() ?: return translated
        // An additional currency label may belong to a spelled-out or omitted amount.
        if (translated.count { it == '円' } != 1) return translated
        val sourceNumber = decimal(sourceMoney.groupValues[1]) ?: return translated
        val targetNumber = decimal(targetMoney.groupValues[1]) ?: return translated
        if (sourceNumber.compareTo(targetNumber) != 0) return translated
        if (scale[sourceMoney.groupValues[2]] != targetMoney.groupValues[2]) return translated
        // Keep every other numeric asset unchanged too; reordered or spelled-out
        // values are deliberately outside this narrow repair, not failures to guess.
        val sourceNumbers = numbers(source) ?: return translated
        val targetNumbers = numbers(translated) ?: return translated
        if (sourceNumbers.size != targetNumbers.size || sourceNumbers.zip(targetNumbers).any { (a, b) -> a.compareTo(b) != 0 }) return translated
        val label = targetMoney.groups[3] ?: return translated
        return translated.replaceRange(label.range, "ウォン")
    }

    private fun decimal(value: String): BigDecimal? =
        if (validNumber.matches(value)) value.replace(",", "").toBigDecimalOrNull() else null

    private fun numbers(text: String): List<BigDecimal>? {
        val values = mutableListOf<BigDecimal>()
        for (match in number.findAll(text)) values += decimal(match.value) ?: return null
        return values
    }

    private data class Quoted(val contentStart: Int, val contentEnd: Int)

    private fun restoreExplicitVerbatimTypo(source: String, translated: String): String {
        val originalQuote = singleQuote(source) ?: return translated
        val outsideQuote = source.removeRange(originalQuote.contentStart, originalQuote.contentEnd)
        if (!misspelling.containsMatchIn(outsideQuote) || !verbatim.containsMatchIn(outsideQuote) || !quoteAction.containsMatchIn(outsideQuote)) return translated
        if (quoteNegationOrCorrection.containsMatchIn(outsideQuote)) return translated
        val targetQuote = singleQuote(translated) ?: return translated
        val original = source.substring(originalQuote.contentStart, originalQuote.contentEnd)
        if (original.isBlank() || original.length > 256 || original.any { it == '\n' || it == '\r' }) return translated
        return translated.replaceRange(targetQuote.contentStart, targetQuote.contentEnd, original)
    }

    /** One balanced, unnested span. Contraction apostrophes do not open a quote. */
    private fun singleQuote(text: String): Quoted? {
        var closing: Char? = null
        var start = -1
        var result: Quoted? = null
        for (index in text.indices) {
            val ch = text[index]
            if ((ch == '\'' || ch == '’') && index > 0 && index + 1 < text.length && isLatinLetter(text[index - 1]) && isLatinLetter(text[index + 1])) continue
            if (!isQuoteCharacter(ch)) continue
            if (closing != null) {
                if (ch != closing) return null
                result = Quoted(start, index)
                closing = null
            } else {
                if (result != null) return null
                closing = when (ch) {
                    '\'' -> '\''
                    '"' -> '"'
                    '‘' -> '’'
                    '“' -> '”'
                    '「' -> '」'
                    '『' -> '』'
                    else -> return null
                }
                start = index + 1
            }
        }
        return if (closing == null) result else null
    }

    private fun isQuoteCharacter(ch: Char): Boolean = ch in "\"'‘’“”「」『』"
    private fun isLatinLetter(ch: Char): Boolean = ch in 'a'..'z' || ch in 'A'..'Z'
}

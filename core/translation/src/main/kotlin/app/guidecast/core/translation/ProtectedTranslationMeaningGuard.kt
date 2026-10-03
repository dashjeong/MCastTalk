package app.guidecast.core.translation

import java.math.BigDecimal

/** Observable asset checks, not semantic scoring. Never rewrite ambiguous model output. */
fun requireProtectedTranslationMeaning(source: String, output: String, sourceTag: String, targetTag: String) {
    if (!sourceTag.substringBefore('-').equals("ko", true) || source.length > 16_384 || output.length > 16_384) return
    val sourceQuotes = protectedQuotes(source) ?: return
    val outputQuotes = protectedQuotes(output) ?: return
    val plainSource = withoutQuotes(source, sourceQuotes)
    val plainOutput = withoutQuotes(output, outputQuotes)
    if (targetTag.substringBefore('-').equals("zh", true) &&
        !plainSource.contains('?') && !plainSource.contains('？') &&
        NEGATIVE_DECLARATIVE.containsMatchIn(plainSource) &&
        !DOUBLE_NEGATIVE.containsMatchIn(plainSource) &&
        Regex("[?？]\\s*$").containsMatchIn(plainOutput)) {
        check(false) { "GEMMA_SENTENCE_TYPE_REVIEW_REQUIRED" }
    }
    // Only an explicit instruction applying to BOTH/ALL quoted phrases establishes scope.
    // A generic 'quote the original typo' does not protect the normal comparison phrase.
    val quoteActionNegated = NEGATED_QUOTE_ACTION.containsMatchIn(plainSource)
    if (!quoteActionNegated && sourceQuotes.size >= 2 && ALL_QUOTES_VERBATIM.containsMatchIn(plainSource)) {
        val originals = sourceQuotes.map { source.substring(it) }.groupingBy { it }.eachCount()
        val actual = outputQuotes.map { output.substring(it) }.groupingBy { it }.eachCount()
        check(originals.all { (text, count) -> (actual[text] ?: 0) >= count }) {
            "GEMMA_VERBATIM_QUOTE_REVIEW_REQUIRED"
        }
    }
    // Only a uniquely attached 'B라고 오기...' plus explicit verbatim citation protects B.
    // The normal A phrase may be translated; this does not validate the A/B relation itself.
    if (!quoteActionNegated && Regex("원문(?:\\s*표기)?\\s*그대로\\s*인용").containsMatchIn(plainSource)) {
        val typo = sourceQuotes.filter { span ->
            Regex("^\\s*(?:이라고|라고|으로)\\s*오기").containsMatchIn(source.substring(span.last + 2))
        }.singleOrNull()
        if (typo != null) check(outputQuotes.any { output.substring(it) == source.substring(typo) }) {
            "GEMMA_VERBATIM_QUOTE_REVIEW_REQUIRED"
        }
    }
    if (targetTag.substringBefore('-').equals("ja", true) && sourceQuotes.isEmpty() &&
        outputQuotes.isEmpty() && !EXCHANGE.containsMatchIn(source)) {
        val originals = moneyAssets(source, korean = true) ?: return
        if (originals.size < 2 || originals.map { it.currency }.toSet().size < 2) return
        val actual = moneyAssets(output, korean = false)
        if (actual != null) {
            check(originals.groupingBy { it }.eachCount() == actual.groupingBy { it }.eachCount()) {
                "GEMMA_CURRENCY_ASSET_REVIEW_REQUIRED"
            }
        } else if (!Regex("KRW|JPY|[₩¥￥]", RegexOption.IGNORE_CASE).containsMatchIn(output)) {
            // Unknown number notation is not a value mismatch. Independently compare only
            // readable currency labels when ALL labels remain observable (including kanji).
            val labels = JA_LABEL.findAll(output).map { if (it.groupValues[1] == "ウォン") "KRW" else "JPY" }.toList()
            if (labels.size == originals.size) check(originals.map { it.currency }.groupingBy { it }.eachCount() == labels.groupingBy { it }.eachCount()) {
                "GEMMA_CURRENCY_ASSET_REVIEW_REQUIRED"
            }
        }
    }
}

private val NEGATIVE_DECLARATIVE = Regex("(?:아니에요|아닙니다|아니다|않았습니다|않습니다|않았어요|않아요)[.!。！\\s]*$")
private val DOUBLE_NEGATIVE = Regex("않.{0,16}않|아니.{0,16}않|않.{0,16}아니|아니.{0,16}아니|없.{0,16}않")
private val ALL_QUOTES_VERBATIM = Regex("(?:두\\s*(?:문구|인용문|표현)|양쪽\\s*(?:문구|인용문|표현)|모든\\s*인용문)(?:를|을|은|는)?\\s*(?:모두\\s*)?원문\\s*그대로\\s*(?:인용|보존|기재)")
private val NEGATED_QUOTE_ACTION = Regex("(?:인용|보존|기재)\\s*(?:하지(?:는)?\\s*(?:말|않|마)|해서는\\s*안|하면\\s*안)")
private val EXCHANGE = Regex("환율|환산|환전|변환|달러|위안|유로|파운드|JPY|USD|CNY|EUR|GBP|[\u0024¥￥€£]", RegexOption.IGNORE_CASE)
private data class MoneyAsset(val currency: String, val value: BigDecimal)
private val DIGITS = "(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?"
private const val KO_CURRENCY_END = "(?=$|[\\s.,!?;:)]|(?:은|는|이|가|을|를|에|으로|로|과|와|도|만|의|이고|이며|입니다|이다|이에요|예요|이야|이네요|이죠|이었습니다|였습니다)(?=$|[\\s.,!?;:)]))"
private val KO_MONEY = Regex("(?<![0-9,.천만억조])((?:(?>$DIGITS)\\s*(?:천만|[천만억조])?\\s*)+)(원|엔)$KO_CURRENCY_END")
private val JA_MONEY = Regex("(?<![0-9,.千万億兆])((?:(?>$DIGITS)\\s*(?:千万|[千万億兆])?\\s*)+)(ウォン|エン|円)")
private val KO_LABEL = Regex("[0-9천만억조]\\s*(?:원|엔)$KO_CURRENCY_END")
private val JA_LABEL = Regex("[0-9千万億兆천만억조一二三四五六七八九十百零〇]\\s*(ウォン|エン|円)")

/** Only explicitly scoped verbatim citations; ordinary speech quotations remain translatable. */
fun protectedVerbatimCitationAssets(source: String): List<String> {
    if (source.length > 16_384) return emptyList()
    val spans = protectedQuotes(source) ?: return emptyList()
    val plain = withoutQuotes(source, spans)
    if (NEGATED_QUOTE_ACTION.containsMatchIn(plain)) return emptyList()
    if (spans.size >= 2 && ALL_QUOTES_VERBATIM.containsMatchIn(plain)) return spans.map { source.substring(it) }
    if (!Regex("원문(?:\\s*표기)?\\s*그대로\\s*인용").containsMatchIn(plain)) return emptyList()
    return spans.filter { span ->
        Regex("^\\s*(?:이라고|라고|으로)\\s*오기").containsMatchIn(source.substring(span.last + 2))
    }.singleOrNull()?.let { listOf(source.substring(it)) }.orEmpty()
}

/** Complete source-only currency evidence, never a suggested translated sentence. */
fun protectedSourceMoneyEvidence(source: String): String {
    if (source.length > 16_384 || EXCHANGE.containsMatchIn(source) || protectedQuotes(source)?.isEmpty() != true) return ""
    val assets = moneyAssets(source, korean = true)?.takeIf { it.isNotEmpty() && it.size <= 4 } ?: return ""
    val matches = KO_MONEY.findAll(source).toList()
    return matches.zip(assets).joinToString("; ") { (span, asset) ->
        "${span.value.trim()} = ${asset.value.toPlainString()} ${asset.currency}"
    }
}

/** Null means incomplete/unsupported parsing. Comparing a partial bag would be unsafe. */
private fun moneyAssets(text: String, korean: Boolean): List<MoneyAsset>? {
    if (Regex("[+＋−–－-]\\s*[0-9]").containsMatchIn(text)) return null
    val matches = (if (korean) KO_MONEY else JA_MONEY).findAll(text).toList()
    if (korean && Regex("[일이삼사오육칠팔구십백천만억조]+\\s*(?:원|엔)(?=$|[\\s.,]|입니다|이고|이며)").containsMatchIn(KO_MONEY.replace(text, ""))) return null
    val labels = (if (korean) KO_LABEL else JA_LABEL).findAll(text).count()
    if (matches.size != labels) return null
    if (matches.isEmpty()) return if (Regex("KRW|JPY|ウォン|円|[₩¥￥]", RegexOption.IGNORE_CASE).containsMatchIn(text)) null else emptyList()
    val amounts = mutableListOf<MoneyAsset>()
    for (match in matches) {
        val expression = match.groupValues[1].filterNot(Char::isWhitespace)
        val parts = Regex("($DIGITS)((?:천만|千万|[천만억조千万億兆])?)").findAll(expression).toList()
        if (parts.joinToString("") { it.value } != expression) return null
        var previousScale = Long.MAX_VALUE
        var sum = BigDecimal.ZERO
        for (part in parts) {
            val scale = when (part.groupValues[2]) {
                "천", "千" -> 1_000L
                "만", "万" -> 10_000L
                "천만", "千万" -> 10_000_000L
                "억", "億" -> 100_000_000L
                "조", "兆" -> 1_000_000_000_000L
                else -> 1L
            }
            if (scale >= previousScale) return null
            previousScale = scale
            sum += part.groupValues[1].replace(",", "").toBigDecimal() * BigDecimal.valueOf(scale)
        }
        val currency = if (match.groupValues[2] in setOf("원", "ウォン")) "KRW" else "JPY"
        amounts += MoneyAsset(currency, sum.stripTrailingZeros())
    }
    return amounts
}

/** Script-only repair. Values and currency identities must already agree with the source. */
internal fun repairAlignedJapaneseMoneyScript(source: String, output: String): String {
    if (source.length > 16_384 || output.length > 16_384 || EXCHANGE.containsMatchIn(source)) return output
    if (protectedQuotes(source)?.isEmpty() != true || protectedQuotes(output)?.isEmpty() != true) return output
    val original = moneyAssets(source, korean = true)?.takeIf { it.isNotEmpty() } ?: return output
    val mixedMoney = Regex("(?<![0-9,.천만억조千万億兆])((?:(?>$DIGITS)\\s*(?:[천千][만万]|[천만억조千万億兆])?\\s*)+)(ウォン|エン|円|원|엔)(?![가-힣])")
    var changed = false
    val candidate = mixedMoney.replace(output) { match ->
        val amount = match.groupValues[1].map { ch ->
            when (ch) { '천' -> '千'; '만' -> '万'; '억' -> '億'; '조' -> '兆'; else -> ch }
        }.joinToString("")
        val currency = when (match.groupValues[2]) { "원" -> "ウォン"; "엔", "エン" -> "円"; else -> match.groupValues[2] }
        (amount + currency).also { if (it != match.value) changed = true }
    }
    if (!changed) return output
    // A remaining Korean money label means the candidate was only partially readable.
    if (KO_LABEL.containsMatchIn(candidate)) return output
    val normalized = moneyAssets(candidate, korean = false) ?: return output
    return if (original.groupingBy { it }.eachCount() == normalized.groupingBy { it }.eachCount()) candidate else output
}

private fun withoutQuotes(text: String, spans: List<IntRange>): String = buildString {
    for (index in text.indices) append(if (spans.any { index in it }) ' ' else text[index])
}

/** Balanced, unnested quotes only; apostrophes in Latin contractions are ordinary letters. */
private fun protectedQuotes(text: String): List<IntRange>? {
    val spans = mutableListOf<IntRange>()
    var closing: Char? = null
    var start = 0
    for (index in text.indices) {
        val ch = text[index]
        if (ch in "'’" && index > 0 && index + 1 < text.length &&
            latin(text[index - 1]) && latin(text[index + 1])) continue
        if (ch !in "\"'‘’“”「」『』") continue
        if (closing != null) {
            if (closing != ch) return null
            spans += start until index
            closing = null
        } else {
            closing = when (ch) { '\'' -> '\''; '"' -> '"'; '‘' -> '’'; '“' -> '”'; '「' -> '」'; '『' -> '』'; else -> return null }
            start = index + 1
        }
    }
    return if (closing == null) spans else null
}

private fun latin(ch: Char): Boolean = ch in 'a'..'z' || ch in 'A'..'Z'

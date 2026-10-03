package app.guidecast.provider.gemma.translation

import app.guidecast.core.translation.protectedVerbatimCitationAssets
import app.guidecast.core.translation.protectedSourceMoneyEvidence

/** Source-derived lexical evidence, not replacement speech or memorized translations. */
internal object E4bSourceEvidence {
    private val quantity = Regex("(?<![0-9.,])([+−-]?[0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(만원|억원|시간|분|명|개|회|원|일|년|대|%|km|kg)?\\s*(이상|이하|초과|미만)")
    private val ministry = Regex("(?<![가-힣])(?:중소벤처기업부|중기부)")

    fun extract(sourceLanguage: String, targetLanguage: String, source: String): String {
        if (sourceLanguage != "Korean") return ""
        val hints = mutableListOf<String>()
        quantity.findAll(source).take(4).forEach { match ->
            val meaning = when (match.groupValues[3]) {
                "이상" -> "at least, including the boundary"
                "이하" -> "at most, including the boundary"
                "초과" -> "more than, excluding the boundary"
                else -> "less than, excluding the boundary"
            }
            hints += "${match.value}: $meaning; retain the surrounding negation or condition."
        }
        if (ministry.containsMatchIn(source)) {
            // Government usage: docs/E4B_TRANSLATION_REVIEW.md records the primary sources.
            val name = when {
                targetLanguage.startsWith("English") -> "Ministry of SMEs and Startups (MSS)"
                targetLanguage.startsWith("Japanese") -> "中小ベンチャー企業部"
                targetLanguage.startsWith("Simplified Chinese") -> "中小风险企业部"
                else -> ""
            }
            if (name.isNotEmpty()) hints += "중소벤처기업부 / 중기부 = $name (Korean ministry)."
        }
        if (Regex("(?<![0-9])5\\s*극\\s*3\\s*특").containsMatchIn(source)) {
            // A public policy term, not an article-specific translation or invented region list.
            val term = when {
                targetLanguage.startsWith("English") -> "five regional hubs and three special self-governing provinces"
                targetLanguage.startsWith("Japanese") -> "5極3特"
                targetLanguage.startsWith("Simplified Chinese") -> "五极三特"
                else -> ""
            }
            if (term.isNotEmpty()) hints += "5극 3특 = $term; retain both regional counts."
        }
        if (Regex("투자\\s*유치").containsMatchIn(source)) {
            hints += "투자 유치 means securing funding from investors, not making an investment."
        }
        if (Regex("(?:밝혔다|발표했다|전했다)").containsMatchIn(source)) {
            hints += "Separate the reported event from the announced plan: a completed event must not inherit a future tense from the plan. Preserve explicit future dates and 예정."
        }
        if (source.contains("연결") && (source.contains("와 ") || source.contains("과 "))) {
            hints += "When linking parties, keep each description with its own party. Prefer 'connects B with A who ...' when only A has that description, not 'A and B who ...'."
        }
        if (source.contains("적합한") || source.contains("적합하")) {
            hints += "~에 적합한 means suited to a criterion, not owning that criterion; preserve whose attribute it is."
        }
        hints += fidelityHints(sourceLanguage, source, targetLanguage)
        return hints.filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** Optional source constraints shared with E2B; no institution or context guesses. */
    fun fidelityHints(sourceLanguage: String, source: String, targetLanguage: String = ""): String {
        if (sourceLanguage != "Korean") return ""
        val hints = mutableListOf<String>()
        val money = protectedSourceMoneyEvidence(source)
        if (money.isNotEmpty() && targetLanguage.startsWith("Japanese")) {
            hints += "Source currency assets: $money. Keep each with its original budget item, without conversion. In Japanese use ウォン for KRW and 円 for JPY; render numeric units in Japanese, not mixed Hangul."
        }
        if (Regex("(?<![가-힣])항공료(?=$|[\\s.,]|는|가|를|와|도|은|로)").containsMatchIn(source)) {
            val term = when {
                targetLanguage.startsWith("Japanese") -> "航空券代"
                targetLanguage.startsWith("Simplified Chinese") -> "机票费用"
                else -> "airfare"
            }
            hints += "항공료 = $term (a ticket cost), not an airline or travel company."
        }
        if (Regex("(?<![가-힣])보증금(?=$|[\\s.,]|은|이|을|과|도|으로)").containsMatchIn(source)) {
            hints += "보증금 is money lodged as security (a deposit or bond), never a contribution or donation."
        }
        if (Regex("(?:[가-힣]+라고|해\\s*달라고)").containsMatchIn(source) &&
            Regex("[가-힣]+겠다고").containsMatchIn(source) &&
            Regex("아니|않").containsMatchIn(source)) {
            hints += "Keep the reported request and denial of a personal promise as distinct clauses with their original actors. Do not turn a declarative denial into a question or replace named roles with the reader."
        }
        if (Regex("(?:과장|부장|실장|팀장|대리)님(?:이|께서|은|는)(?=$|[\\s.,])").containsMatchIn(source)) {
            hints += "A job title explicitly marked as a clause subject remains that actor; do not replace it with the reader or invent gender."
            if (targetLanguage.startsWith("Simplified Chinese") && Regex("팀장님(?:이|께서|은|는)(?=$|[\\s.,])").containsMatchIn(source))
                hints += "팀장 = 组长 (team leader)."
            if (targetLanguage.startsWith("English")) hints +=
                "If CURRENT and CONTEXT do not specify this actor's gender, use singular they/them/their instead of he/she/his/her."
        }
        if (targetLanguage.startsWith("Simplified Chinese") && Regex("뜻\\s*아닌가요\\s*[?？]").containsMatchIn(source)) {
            hints += "뜻 아닌가요? asks for confirmation of an interpretation: retain the meaning relation (意思), named actor and question, rather than an assertion or a bare future-action question."
        }
        val citations = protectedVerbatimCitationAssets(source).filter { it.length <= 256 }.take(4)
        if (citations.isNotEmpty()) {
            hints += "Explicit verbatim citation assets (retain each exact source spelling inside a quote, translate the surrounding explanation): " +
                citations.joinToString(", ") { it.jsonQuoted() }
        }
        return hints.joinToString(" ")
    }
}

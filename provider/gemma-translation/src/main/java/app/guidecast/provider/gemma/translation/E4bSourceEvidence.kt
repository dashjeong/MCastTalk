package app.guidecast.provider.gemma.translation

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
        return hints.joinToString(" ")
    }
}

package app.guidecast.transmitter

import app.guidecast.core.translation.GlossaryTerm

/** A packaged dictionary sense is optional evidence, not an operator-approved correction. */
internal object PublicGlossaryHintPolicy {
    // Only the observed, pinned Korean/English public entry is restricted. Never guess
    // another translation or modify the dictionary, original transcript or user override.
    fun permits(text: String, term: GlossaryTerm, edited: Boolean): Boolean {
        if (edited || term.sourceLanguage != "ko" || term.targetLanguage != "en" ||
            term.sourceTerm != "정전" || term.preferredTerm != "power outage" ||
            term.replacement.isNotBlank()) return true
        if (Regex("정전").findAll(text).count() != 1 || text.any { it in QUOTES }) return false
        // With no bounded current electrical cue, omit this ambiguous sense. In
        // particular, earlier military context must not override a current refrigerator
        // outage, and a mixed/metalinguistic statement must not receive one forced sense.
        return ELECTRICAL.containsMatchIn(text) && !MILITARY_OR_CONTRAST.containsMatchIn(text)
    }

    private val QUOTES = setOf('"', '\'', '‘', '’', '“', '”', '「', '」', '`')
    // Bare 전원 (everyone), 전기 (biography), 전력 (effort/history), and 조명
    // (attention) are themselves ambiguous and must not select this dictionary sense.
    private val ELECTRICAL = Regex(
        "신호등|냉장고|전등|단전|발전소|송전|배전|" +
            "(?:전기|전력)\\s*공급|전기\\s*(?:설비|시설)|" +
            "전원\\s*(?:공급|장치|스위치|차단|연결)",
    )
    private val MILITARY_OR_CONTRAST = Regex("전쟁|휴전|군사|평화|한반도|남북|협정|아니|뜻|의미")
}

package app.guidecast.core.translation

import java.util.Locale

/** Source-only grammatical evidence; no rewritten source, prior-topic guess or translation. */
enum class SourceSemanticCue {
    KOREAN_HONORIFIC_PERSON_SUBJECT,
}

/** Ranges refer to the exact caller-supplied source, including its original spacing. */
data class HumanSubjectEvidence(
    val subjectRange: IntRange,
    val classifierRange: IntRange,
    val quantifier: String,
    val particle: String,
)

object SourceSemanticEvidence {
    private const val MAX_SOURCE_CHARACTERS = 600
    private val quotedMaterial = setOf('"', '\'', '‘', '’', '“', '”', '「', '」', '『', '』', '\u0060')
    private val personSubject = Regex(
        "(?<![\\p{L}\\p{N}])(?<quantifier>몇|여러|한|두|세|네|다섯|여섯|일곱|여덟|아홉|열|스무)" +
            "[ \\t]*(?<classifier>분)(?<particle>이|께서)[ \\t]+",
    )
    private val token = Regex("^[가-힣]+")
    // Exact supported forms, not 계시 + arbitrary Hangul: 계시록/계시판 are nouns.
    // Unknown inflections abstain; a hint must not infer a predicate from a shared prefix.
    private val honorificExistence = setOf(
        "계시다", "계시고", "계시며", "계시면", "계시니", "계시니까", "계시네요",
        "계시는데", "계시지만", "계시죠", "계시지요",
        "계신", "계신가", "계신가요", "계신지", "계신데", "계신데요", "계신다", "계신다고", "계신다면",
        "계실", "계십니다", "계십니까", "계십시오",
        "계셔", "계셔요", "계셔서", "계셔도", "계셔야", "계셔가지고", "계셔갖고",
        "계셨다", "계셨고", "계셨던", "계셨지만", "계셨습니다", "계셨습니까", "계셨어요",
        "계셨으면", "계셨으니", "계셨으니까", "계셨는데", "계셨더라고요",
    )
    private val localModifiers = setOf(
        "계속", "아직", "지금", "현재", "여기", "저기", "거기", "함께", "그대로", "가만히", "잠시",
        "서", "앉아", "누워", "남아",
    )
    private val location = Regex("[가-힣]+(?:에|에서)")
    private val elapsedTime = Regex("(?:지나|흘러|흐르|걸리|걸려|소요|경과)")

    fun classify(sourceLanguage: String, sourceText: String): Set<SourceSemanticCue> =
        if (humanSubject(sourceLanguage, sourceText) != null) {
            setOf(SourceSemanticCue.KOREAN_HONORIFIC_PERSON_SUBJECT)
        } else emptySet()

    fun humanSubject(sourceLanguage: String, sourceText: String): HumanSubjectEvidence? {
        val language = sourceLanguage.trim().replace('_', '-').lowercase(Locale.ROOT)
        if (language != "korean" && language.substringBefore('-') != "ko") return null
        if (sourceText.length !in 1..MAX_SOURCE_CHARACTERS || sourceText.any { it in quotedMaterial }) return null
        // A global hint must not relabel a second temporal 분, including compound numerals.
        // Literal counting deliberately abstains even on unrelated words containing 분.
        if (sourceText.count { it == '분' } != 1 || elapsedTime.containsMatchIn(sourceText)) return null
        for (subject in personSubject.findAll(sourceText)) {
            // Fused 여러분 is an audience pronoun. Do not render it as a count hint or
            // use it to request review, even though its suffix resembles the classifier.
            if (subject.value.startsWith("여러분")) continue
            if (hasLocalHonorificPredicate(sourceText, subject.range.last + 1)) {
                val quantifier = requireNotNull(subject.groups["quantifier"])
                val classifier = requireNotNull(subject.groups["classifier"])
                val particle = requireNotNull(subject.groups["particle"])
                return HumanSubjectEvidence(
                    subjectRange = quantifier.range.first..particle.range.last,
                    classifierRange = classifier.range,
                    quantifier = quantifier.value,
                    particle = particle.value,
                )
            }
        }
        return null
    }

    private fun hasLocalHonorificPredicate(source: String, start: Int): Boolean {
        var cursor = start
        // At most four simple modifiers before the predicate; punctuation, a new clause or
        // another subject cannot bridge this local window.
        repeat(5) {
            val word = token.find(source.substring(cursor))?.value ?: return false
            if (word in honorificExistence) return true
            if (elapsedTime.containsMatchIn(word) ||
                word !in localModifiers && !location.matches(word)) return false
            cursor += word.length
            if (cursor >= source.length || source[cursor] !in " \t") return false
            while (cursor < source.length && source[cursor] in " \t") cursor++
            if (cursor >= source.length) return false
        }
        return false
    }
}

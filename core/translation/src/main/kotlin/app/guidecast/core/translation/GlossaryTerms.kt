package app.guidecast.core.translation

import java.util.Locale

data class GlossaryTerm(
    val sourceLanguage: String,
    val targetLanguage: String,
    val sourceTerm: String,
    val preferredTerm: String,
    val replacement: String = "",
    val category: String = "",
    val origin: String = "",
    val enabled: Boolean = true,
)

class GlossaryExpansionException : IllegalArgumentException("용어 교정 결과가 8,000자를 초과합니다. 긴 치환어를 확인하세요.")

object GlossaryTerms {
    private const val MAX_TERMS = 6
    private const val MAX_TOTAL_PREFERRED_CHARS = 600
    private const val MAX_HINTS_JSON_CHARS = 900

    private val KOREAN_PARTICLES = listOf(
        // 4-syllable
        "이야말로", "이라는것", "이라면서",
        // 3-syllable
        "에서는", "으로는", "에게는", "에게도", "에서도", "에게서", "이라도", "야말로", "으로만",
        "입니다", "입니까", "이라고", "이라는", "이라서", "이지만", "이나마", "보다도", "치고는",
        // 2-syllable
        "에서", "에게", "께서", "으로", "까지", "부터", "마다", "보다", "처럼",
        "이나", "이랑", "이며", "이든", "라도", "조차", "마저", "이야", "치고",
        "에는", "에도", "에만", "로만", "과의", "와의", "이다", "이고", "이면", "이니",
        "이라", "에의", "로의", "만의",
        // 1-syllable
        "은", "는", "이", "가", "을", "를", "의", "에", "게", "께", "로",
        "와", "과", "도", "만", "요", "나", "랑", "며", "든", "서"
    )

    fun select(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        candidates: List<GlossaryTerm>,
    ): List<GlossaryTerm> {
        if (text.isBlank() || candidates.isEmpty()) {
            return emptyList()
        }

        val normSource = normalizeLanguage(sourceLanguage)
        val normTarget = normalizeLanguage(targetLanguage)
        val isKoreanSource = normSource == "ko"
        val isCjkSource = normSource == "ja" || normSource.startsWith("zh")

        // 1. Filter candidates by normalized language pair, enabled flag, and non-blank terms.
        val validCandidates = candidates.filter { term ->
            term.enabled &&
                term.sourceTerm.isNotBlank() &&
                term.preferredTerm.isNotBlank() &&
                normalizeLanguage(term.sourceLanguage) == normSource &&
                normalizeLanguage(term.targetLanguage) == normTarget
        }

        if (validCandidates.isEmpty()) {
            return emptyList()
        }

        // 2. Find all matches in text with boundaries for each candidate.
        data class CandidateMatch(
            val term: GlossaryTerm,
            val spans: List<IntRange>,
            val firstIndex: Int,
        )

        val matches = mutableListOf<CandidateMatch>()
        for (term in validCandidates) {
            val spans = findValidOccurrences(text, term.sourceTerm, isKoreanSource, isCjkSource)
            if (spans.isNotEmpty()) {
                matches.add(CandidateMatch(term, spans, spans.first().first))
            }
        }

        if (matches.isEmpty()) {
            return emptyList()
        }

        // 3. Sort candidates: longer sourceTerm first, then earlier occurrence, then alphabetical.
        matches.sortWith(
            compareByDescending<CandidateMatch> { it.term.sourceTerm.length }
                .thenBy { it.firstIndex }
                .thenBy { it.term.sourceTerm }
        )

        // 4. Greedily select non-overlapping terms respecting limits.
        val selected = mutableListOf<GlossaryTerm>()
        val occupiedSpans = mutableListOf<IntRange>()
        var totalPreferredChars = 0

        for (match in matches) {
            if (selected.size >= MAX_TERMS) break

            val term = match.term

            // Deduplicate if already selected identical sourceTerm
            if (selected.any { it.sourceTerm.equals(term.sourceTerm, ignoreCase = true) }) {
                continue
            }

            // Check that at least one match span does not overlap with previously occupied spans
            val nonOverlappingSpan = match.spans.firstOrNull { span ->
                occupiedSpans.none { occupied -> overlaps(span, occupied) }
            }

            if (nonOverlappingSpan != null) {
                if (totalPreferredChars + term.preferredTerm.length <= MAX_TOTAL_PREFERRED_CHARS) {
                    selected.add(term)
                    totalPreferredChars += term.preferredTerm.length
                    occupiedSpans.addAll(match.spans)
                }
            }
        }

        return selected
    }

    fun correct(
        text: String,
        terms: List<GlossaryTerm>,
    ): String {
        if (text.isEmpty() || terms.isEmpty()) {
            return text
        }

        val validTerms = terms.filter {
            it.enabled && it.replacement.isNotBlank() && it.preferredTerm.isNotBlank()
        }
        if (validTerms.isEmpty()) {
            return text
        }

        data class MatchSpan(
            val start: Int,
            val end: Int,
            val replacementLength: Int,
            val preferredTerm: String,
        )

        val allMatches = mutableListOf<MatchSpan>()

        for (term in validTerms) {
            val rep = term.replacement
            var searchStart = 0
            while (searchStart < text.length) {
                val index = text.indexOf(rep, searchStart, ignoreCase = true)
                if (index < 0) break

                val endIndex = index + rep.length
                if (isValidReplacementBoundary(text, index, endIndex)) {
                    allMatches.add(MatchSpan(index, endIndex, rep.length, term.preferredTerm))
                }
                searchStart = index + 1
            }
        }

        if (allMatches.isEmpty()) {
            return text
        }

        // Sort: longer replacement first to prioritize specific replacements, then by start position
        allMatches.sortWith(
            compareByDescending<MatchSpan> { it.replacementLength }
                .thenBy { it.start }
        )

        // Select non-overlapping match intervals
        val acceptedSpans = mutableListOf<MatchSpan>()
        for (match in allMatches) {
            val conflicts = acceptedSpans.any { accepted ->
                !(match.end <= accepted.start || match.start >= accepted.end)
            }
            if (!conflicts) {
                acceptedSpans.add(match)
            }
        }

        // Sort accepted spans by start index ascending for single-pass reconstruction
        acceptedSpans.sortBy { it.start }

        val correctedLength = text.length.toLong() + acceptedSpans.sumOf {
            it.preferredTerm.length.toLong() - it.replacementLength
        }
        if (correctedLength > 8_000) throw GlossaryExpansionException()

        // Construct new string in a single pass without re-evaluating replaced regions
        val sb = StringBuilder(text.length)
        var cursor = 0
        for (span in acceptedSpans) {
            if (span.start > cursor) {
                sb.append(text, cursor, span.start)
            }
            sb.append(span.preferredTerm)
            cursor = span.end
        }
        if (cursor < text.length) {
            sb.append(text, cursor, text.length)
        }

        return sb.toString()
    }

    fun hints(terms: List<GlossaryTerm>): String {
        val validTerms = terms.filter {
            it.enabled && it.sourceTerm.isNotBlank() && it.preferredTerm.isNotBlank()
        }
        if (validTerms.isEmpty()) {
            return ""
        }

        val seen = mutableSetOf<String>()
        val distinctTerms = mutableListOf<GlossaryTerm>()
        for (t in validTerms) {
            if (seen.add(t.sourceTerm.lowercase(Locale.ROOT))) {
                distinctTerms.add(t)
                if (distinctTerms.size >= MAX_TERMS) break
            }
        }

        if (distinctTerms.isEmpty()) {
            return ""
        }

        val sb = StringBuilder("{")
        var addedCount = 0

        for (term in distinctTerms) {
            val keyEscaped = escapeJson(term.sourceTerm)
            val valEscaped = escapeJson(term.preferredTerm)
            val prefix = if (addedCount > 0) "," else ""
            val entry = "$prefix\"$keyEscaped\":\"$valEscaped\""

            // +1 for closing '}'
            if (sb.length + entry.length + 1 > MAX_HINTS_JSON_CHARS) {
                break
            }

            sb.append(entry)
            addedCount++
        }

        if (addedCount == 0) {
            return ""
        }

        sb.append("}")
        val result = sb.toString()
        return if (result.length <= MAX_HINTS_JSON_CHARS) result else ""
    }

    private fun normalizeLanguage(lang: String): String {
        val clean = lang.trim().replace('_', '-').lowercase(Locale.ROOT)
        return when (clean) {
            "ko", "ko-kr" -> "ko"
            "en", "en-us" -> "en"
            "ja", "ja-jp" -> "ja"
            "zh", "zh-cn", "zh-hans", "zh-hans-cn" -> "zh"
            "zh-tw", "zh-hant", "zh-hant-tw", "zh-hk", "zh-mo" -> "zh-hant"
            "es", "es-es" -> "es"
            "ar", "ar-sa" -> "ar"
            else -> {
                if (clean.startsWith("zh-")) {
                    clean
                } else {
                    clean.substringBefore('-')
                }
            }
        }
    }

    private fun overlaps(a: IntRange, b: IntRange): Boolean {
        return a.first <= b.last && b.first <= a.last
    }

    private fun isLatinWordChar(c: Char): Boolean {
        return (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '_'
    }

    private fun isKoreanChar(c: Char): Boolean {
        return (c in '\uAC00'..'\uD7A3') || (c in '\u1100'..'\u11FF') || (c in '\u3130'..'\u318F')
    }

    private fun isCjkChar(c: Char): Boolean {
        return (c in '\u4E00'..'\u9FFF') ||
            (c in '\u3400'..'\u4DBF') ||
            (c in '\u3040'..'\u309F') ||
            (c in '\u30A0'..'\u30FF') ||
            (c in '\u3100'..'\u312F') ||
            (c in '\uF900'..'\uFAFF')
    }

    private fun findValidOccurrences(
        text: String,
        target: String,
        isKoreanSource: Boolean,
        isCjkSource: Boolean,
    ): List<IntRange> {
        val spans = mutableListOf<IntRange>()
        var searchStart = 0
        val firstTargetChar = target.first()
        val lastTargetChar = target.last()

        while (searchStart < text.length) {
            val index = text.indexOf(target, searchStart, ignoreCase = true)
            if (index < 0) break

            val endIndex = index + target.length

            val validLeading = if (index == 0) {
                true
            } else {
                val prevChar = text[index - 1]
                if (isLatinWordChar(firstTargetChar) && isLatinWordChar(prevChar)) {
                    false
                } else if (isKoreanChar(firstTargetChar) && isKoreanChar(prevChar)) {
                    false
                } else {
                    true
                }
            }

            if (validLeading) {
                val validTrailing = if (endIndex == text.length) {
                    true
                } else {
                    val nextChar = text[endIndex]
                    if (isLatinWordChar(lastTargetChar)) {
                        !isLatinWordChar(nextChar)
                    } else if (isKoreanSource && isKoreanChar(lastTargetChar)) {
                        if (!isKoreanChar(nextChar) && !Character.isLetterOrDigit(nextChar)) {
                            true
                        } else {
                            val remaining = text.substring(endIndex)
                            val matchedParticle = KOREAN_PARTICLES.firstOrNull { remaining.startsWith(it) }
                            if (matchedParticle != null) {
                                val particleEnd = endIndex + matchedParticle.length
                                particleEnd == text.length || !isKoreanChar(text[particleEnd])
                            } else {
                                false
                            }
                        }
                    } else if (isCjkSource && isCjkChar(lastTargetChar)) {
                        true
                    } else {
                        !Character.isLetterOrDigit(nextChar)
                    }
                }

                if (validTrailing) {
                    spans.add(index until endIndex)
                }
            }

            searchStart = index + 1
        }
        return spans
    }

    private fun isValidReplacementBoundary(text: String, start: Int, end: Int): Boolean {
        val firstChar = text[start]
        val lastChar = text[end - 1]

        // 1. Check leading boundary
        if (start > 0) {
            val prevChar = text[start - 1]
            if (isLatinWordChar(firstChar) && isLatinWordChar(prevChar)) {
                return false
            }
            if (isKoreanChar(firstChar) && isKoreanChar(prevChar)) {
                return false
            }
        }

        // 2. Check trailing boundary
        if (end < text.length) {
            val nextChar = text[end]
            if (isLatinWordChar(lastChar) && isLatinWordChar(nextChar)) {
                return false
            }
            if (isKoreanChar(lastChar) && isKoreanChar(nextChar)) {
                val remaining = text.substring(end)
                val matchedParticle = KOREAN_PARTICLES.firstOrNull { remaining.startsWith(it) }
                if (matchedParticle != null) {
                    val particleEnd = end + matchedParticle.length
                    if (particleEnd < text.length && isKoreanChar(text[particleEnd])) {
                        return false
                    }
                } else {
                    return false
                }
            }
            // CJK characters (Japanese / Chinese) do not require word boundaries.
        }

        return true
    }

    private fun escapeJson(value: String): String {
        val sb = StringBuilder()
        for (c in value) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c < ' ') {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        return sb.toString()
    }
}

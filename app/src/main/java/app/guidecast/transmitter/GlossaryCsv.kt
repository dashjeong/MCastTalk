package app.guidecast.transmitter

import app.guidecast.core.translation.GlossaryTerm
import java.io.Reader
import java.text.Normalizer
import java.util.Locale

/** Small, bounded RFC4180-style data format. No Excel engine, scripts, formulas or regex input. */
object GlossaryCsv {
    /** Sign-layout whitespace is not part of a spoken translation; raw provenance stays intact. */
    fun spokenCandidate(value: String): String = value.trim().replace(Regex("\\s+"), " ")

    const val header = "source_language,target_language,source_term,preferred_term,replace_translation,category,origin,enabled"
    private const val MAX_CHARS = 16 * 1024 * 1024
    private const val MAX_ROWS = 60_000
    fun line(term: GlossaryTerm): String = listOf(term.sourceLanguage, term.targetLanguage, term.sourceTerm,
        term.preferredTerm, term.replacement, term.category, term.origin, if (term.enabled) "1" else "0")
        .joinToString(",") { value ->
            // Reversible Excel formula neutralisation, including literal apostrophes.
            val safe = if (value.firstOrNull() in listOf('=', '+', '-', '@', '\'')) "'$value" else value
            "\"${safe.replace("\"", "\"\"")}\""
        }

    fun read(reader: Reader): List<GlossaryTerm> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closedQuote = false
        var count = 0
        var afterCr = false
        fun finishField() { row.add(field.toString()); field.setLength(0); closedQuote = false }
        fun finishRow() {
            finishField()
            if (row.any { it.isNotEmpty() }) rows.add(row)
            require(rows.size <= MAX_ROWS + 1) { "최대 60,000행까지 가져올 수 있습니다" }
            row = mutableListOf()
        }
        while (true) {
            val n = reader.read()
            if (n < 0) break
            require(++count <= MAX_CHARS) { "CSV 파일은 16Mi 문자 이하여야 합니다" }
            val c = n.toChar()
            if (count == 1 && c == '\uFEFF') continue
            if (afterCr && c == '\n') { afterCr = false; continue }
            afterCr = false
            when {
                quoted && c == '"' -> { quoted = false; closedQuote = true }
                quoted -> field.append(c)
                closedQuote && c == '"' -> { field.append('"'); quoted = true; closedQuote = false }
                c == ',' -> finishField()
                c == '\n' || c == '\r' -> { finishRow(); afterCr = c == '\r' }
                c == '"' && field.isEmpty() && !closedQuote -> quoted = true
                else -> {
                    require(!closedQuote && c != '"') { "CSV 따옴표 형식이 잘못되었습니다" }
                    field.append(c)
                }
            }
            require(field.length <= 4_096 && row.size <= 8) { "CSV 열/용어 길이 한도 초과" }
        }
        require(!quoted) { "CSV 따옴표가 닫히지 않았습니다" }
        if (field.isNotEmpty() || row.isNotEmpty() || closedQuote) finishRow()
        require(rows.firstOrNull()?.joinToString(",") == header) { "양식의 8개 열 이름을 유지하세요 (UTF-8 CSV)" }
        fun decode(value: String): String = if (value.startsWith("'") && value.getOrNull(1) in listOf('=', '+', '-', '@', '\'')) value.drop(1) else value
        return validate(rows.drop(1).mapIndexed { index, cells ->
            require(cells.size == 8 && cells[7] in listOf("0", "1")) { "${index + 2}행: 8개 열과 enabled 0/1을 확인하세요" }
            val c = cells.map(::decode)
            GlossaryTerm(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7] == "1")
        })
    }

    fun validate(terms: List<GlossaryTerm>): List<GlossaryTerm> {
        require(terms.size <= MAX_ROWS) { "최대 60,000행까지 등록 가능합니다" }
        fun clean(s: String, max: Int): String {
            val text = Normalizer.normalize(s.trim(), Normalizer.Form.NFC)
            require(text.length <= max && text.none { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }) { "용어 길이 또는 제어문자를 확인하세요" }
            return text
        }
        val validated = terms.map { t ->
            val src = clean(t.sourceLanguage, 20).lowercase(Locale.ROOT)
            val dst = clean(t.targetLanguage, 20).lowercase(Locale.ROOT)
            require(Regex("[a-z]{2,3}(-[a-z0-9]{2,8})*").matches(src) && Regex("[a-z]{2,3}(-[a-z0-9]{2,8})*").matches(dst) && src != dst) { "서로 다른 입력/번역 언어 코드를 지정하세요" }
            t.copy(sourceLanguage = src, targetLanguage = dst, sourceTerm = clean(t.sourceTerm, 160),
                preferredTerm = clean(t.preferredTerm, 512), replacement = clean(t.replacement, 512),
                category = clean(t.category, 2_048), origin = clean(t.origin, 1_024)).also {
                require(it.sourceTerm.isNotBlank() && it.preferredTerm.isNotBlank()) { "원어와 권장 번역어는 필수입니다" }
            }
        }
        val keys = validated.groupBy { Triple(it.sourceLanguage, it.targetLanguage, it.sourceTerm) }
        require(keys.values.none { variants -> variants.distinct().size > 1 }) { "같은 언어·원어의 수정값이 중복됩니다. 한 행만 남겨 주세요" }
        return keys.values.map { it.first() }
    }
}

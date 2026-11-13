package app.guidecast.core.translation

import java.text.Normalizer

/** 행사 용어와 사용자가 저장한 원문 용어만 참조한다. 문장 안 부분 문자열은 바꾸지 않는다. */
object KoreanRecognitionTerms {
    fun correct(text: String, language: String, eventTerms: List<String>, userTerms: List<String> = emptyList()): String {
        if (!language.substringBefore('-').equals("ko", true)) return text
        val event = eventTerms.flatMap { word.findAll(it).map { found -> found.value }.toList() }.filter(::validTerm).toSet()
        val user = userTerms.flatMap { word.findAll(it).map { found -> found.value }.toList() }.filter(::validTerm)
        val terms = (event + user).take(128)
        if (terms.isEmpty()) return text
        return word.replace(text) { match ->
            val token = match.value
            if (token in terms) return@replace token
            val splits = listOf(token to "") + particles.filter { token.endsWith(it) && token.length - it.length in 2..5 }
                .map { token.dropLast(it.length) to it }
            val candidates = splits.flatMap { (stem, particle) ->
                if (stem.length !in 2..5 || stem in terms) emptyList() else terms.mapNotNull { term ->
                    // 흔한 낱말·실제 지명은 그대로 둔다. 문맥 판단은 번역 지시문의 용어 규칙에 맡긴다.
                    if (stem.length != term.length || stem in commonWords) return@mapNotNull null
                    val distance = distance(jamo(stem), jamo(term))
                    // 두 음절 지명은 행사에 명시되고 첫 초성·끝 음절이 같을 때만 2까지 허용한다.
                    val limit = if (stem.length == 2 && term in event &&
                        closeOnset(jamo(stem).first(), jamo(term).first()) && stem.last() == term.last()) 2 else 1
                    if (distance !in 1..limit) null else Triple(term + particle, distance, stem)
                }
            }
            val best = candidates.minOfOrNull { it.second }
            val winners = candidates.filter { it.second == best }.map { it.first }.distinct()
            winners.singleOrNull() ?: token
        }
    }

    private fun validTerm(term: String) = term.length in 2..5 && term.all { it in '가'..'힣' }
    private fun jamo(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD)
    private fun closeOnset(a: Char, b: Char) = a == b || setOf(a, b) == setOf('\u110C', '\u110E')
    private fun distance(a: String, b: String): Int {
        var row = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val next = IntArray(b.length + 1); next[0] = i + 1
            for (j in b.indices) next[j + 1] = minOf(next[j] + 1, row[j + 1] + 1, row[j] + if (a[i] == b[j]) 0 else 1)
            row = next
        }
        return row.last()
    }
    private val word = Regex("[가-힣]+")
    private val particles = listOf("으로부터", "에서는", "으로는", "에서", "에게", "까지", "부터", "으로", "처럼", "보다", "하고", "이랑", "은", "는", "이", "가", "을", "를", "에", "의", "와", "과", "도", "만", "로")
    // 짧은 일반 낱말·실제 지명의 보호 사전. 행사 용어의 명시 없이 유사 발음만으로 바꾸지 않는다.
    private val commonWords = setOf("창원", "전원", "차원", "정원", "수원", "강원", "대전", "서울", "부산", "인천", "대구", "광주", "울산", "제주", "진주", "춘천", "원주", "청주", "충주", "여수", "순천", "포항", "안동", "경주", "전주", "목포", "군산", "익산", "김해", "양산", "취업", "신청", "소리", "시간", "사람", "오늘", "내일", "어제", "여기", "우리", "학교", "회사", "안내", "행사", "문화", "역사", "평화", "세계", "사진", "입구", "출구", "도로", "건물", "공원", "공연", "시장", "식사", "안전", "학생", "선생", "이름", "나라", "지역", "마을", "도시", "가족", "친구")
}

package app.guidecast.provider.gemma.translation

import org.junit.Assert.*
import org.junit.Test

class E4bSourceEvidenceTest {
    @Test fun regionalPolicyTermPreservesBothCountsOnlyWhenPresent() {
        assertTrue(E4bSourceEvidence.extract("Korean", "English", "5극3특의 발전을 지원합니다.").contains("five regional hubs and three special self-governing provinces"))
        assertTrue(E4bSourceEvidence.extract("Korean", "Simplified Chinese", "5극 3특 권역에 적용합니다.").contains("五极三特"))
        assertTrue(E4bSourceEvidence.extract("Korean", "Japanese", "5극 3특 권역입니다.").contains("5極3特"))
        assertEquals("", E4bSourceEvidence.extract("Korean", "English", "5개 권역과 4개 지역입니다."))
        assertEquals("", E4bSourceEvidence.extract("Korean", "English", "15극3특이라는 문자열입니다."))
    }
    @Test fun negativeAndDecimalThresholdsKeepTheirSigns() {
        val hints = E4bSourceEvidence.extract("Korean", "English", "-3.5 이상이며 −2 이하입니다.")
        assertTrue(hints.contains("-3.5 이상:"))
        assertTrue(hints.contains("−2 이하:"))
    }
    @Test fun preservesBothInclusiveAndExclusiveComparatorsWithoutRewritingNegation() {
        val source = "10명 이상이 아니라 5명 이하이며, 3분 초과 또는 2시간 미만은 안 됩니다."
        val result = E4bSourceEvidence.extract("Korean", "English", source)
        for (bound in listOf("at least", "at most", "more than", "less than")) assertTrue(result.contains(bound))
        // Native Chinese output copied the parenthesized comparator from an earlier hint.
        assertFalse(result.contains('>'))
        assertFalse(result.contains('<'))
        assertTrue(result.contains("surrounding negation or condition"))
        assertFalse(E4bSourceEvidence.extract("Korean", "English", "이상한 설명이네요.").contains("boundary"))
    }
    @Test fun institutionIsResolvedOnlyFromCurrentKoreanSpeechAndTarget() {
        assertTrue(E4bSourceEvidence.extract("Korean", "Japanese", "중기부와 협의합니다.").contains("中小ベンチャー企業部"))
        assertTrue(E4bSourceEvidence.extract("Korean", "Simplified Chinese", "중소벤처기업부는 발표했다.").contains("中小风险企业部"))
        assertEquals("", E4bSourceEvidence.extract("Japanese", "English", "중기부"))
        assertEquals("", E4bSourceEvidence.extract("Korean", "English", "일본 중소기업청은 발표했습니다."))
    }
    @Test fun numericHintsAreBoundedAndOriginalSourceIsUntouched() {
        val source = (1..20).joinToString(" ") { "${it}개 이상" }
        val evidence = E4bSourceEvidence.extract("Korean", "English", source)
        assertEquals(4, Regex("boundary").findAll(evidence).count())
        val prompt = GemmaTranslationPrompt.build("Korean", "English", "", source, variant = GemmaModelVariant.E4B_IT)
        assertTrue(prompt.endsWith("CURRENT: \"$source\""))
    }
}

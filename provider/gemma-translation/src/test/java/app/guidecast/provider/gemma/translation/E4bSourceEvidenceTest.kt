package app.guidecast.provider.gemma.translation

import org.junit.Assert.*
import org.junit.Test

class E4bSourceEvidenceTest {
    @Test fun chineseInterpretationQuestionKeepsMeaningAndActorWithoutSupplyingAnAnswer() {
        val source = "팀장님이 직접 승인하시겠다는 뜻 아닌가요?"
        val hints = E4bSourceEvidence.fidelityHints("Korean", source, "Simplified Chinese")
        assertTrue(hints.contains("팀장 = 组长"))
        assertTrue(hints.contains("meaning relation (意思)"))
        assertFalse(hints.contains("批准"))
        assertFalse(E4bSourceEvidence.fidelityHints("Korean", "팀장님이 직접 승인하시겠어요?", "Simplified Chinese").contains("meaning relation"))
        assertFalse(E4bSourceEvidence.fidelityHints("Korean", source, "Japanese").contains("meaning relation"))
    }
    @Test fun reportedRequestAndNamedSubjectConstraintsApplyToBothModelsWithoutChangingSource() {
        val source = "팀장님이 나보고 보고서를 보내라고 하셨지 직접 보내겠다고 하신 건 아니에요."
        val hints = E4bSourceEvidence.fidelityHints("Korean", source)
        assertTrue(hints.contains("distinct clauses"))
        assertTrue(hints.contains("remains that actor"))
        assertTrue(E4bSourceEvidence.fidelityHints("Korean", source, "English").contains("singular they/them/their"))
        assertFalse(E4bSourceEvidence.fidelityHints("Korean", source, "Japanese").contains("singular they/them/their"))
        for (variant in listOf(GemmaModelVariant.STANDARD, GemmaModelVariant.E4B_IT)) {
            val prompt = GemmaTranslationPrompt.build("Korean", "Simplified Chinese", "", source, variant = variant)
            assertTrue(prompt.contains("distinct clauses"))
            assertTrue(prompt.contains("OUTPUT_LANGUAGE: \"Simplified Chinese\""))
            assertTrue(prompt.contains("Simplified Chinese translation of CURRENT only"))
            assertTrue(prompt.endsWith("CURRENT: \"$source\""))
        }
        assertFalse(E4bSourceEvidence.fidelityHints("Korean", "팀장님, 보내 주실래요?").contains("clause subject"))
        assertFalse(E4bSourceEvidence.fidelityHints("Korean", "팀장님이 내일 보내겠다고 하셨어요.").contains("distinct clauses"))
    }
    @Test fun businessLexemesAreSourceBoundAndDoNotAddMissingFacts() {
        val hints = E4bSourceEvidence.extract("Korean", "Japanese", "출장 항공료는 40만 원이고 사무실 보증금은 300만 원입니다.")
        assertTrue(hints.contains("航空券代"))
        assertTrue(hints.contains("deposit or bond"))
        assertEquals("", E4bSourceEvidence.extract("Korean", "Japanese", "출장자는 내일 사무실에서 만납니다."))
        assertEquals("", E4bSourceEvidence.extract("English", "Japanese", "항공료 보증금"))
        assertEquals("", E4bSourceEvidence.extract("Korean", "English", "항공료율과 보증금액을 검토합니다."))
    }
    @Test fun onlyExplicitlyScopedCitationSpellingIsAnchored() {
        val source = "원문은 ‘출고 대기’인데 기록에는 ‘출고 완료’라고 오기되어 있습니다. 두 문구를 원문 그대로 인용해 주세요."
        val hints = E4bSourceEvidence.extract("Korean", "English", source)
        assertTrue(hints.contains("\"출고 대기\""))
        assertTrue(hints.contains("\"출고 완료\""))
        assertFalse(E4bSourceEvidence.extract("Korean", "English", "그가 ‘출고 대기’라고 말했습니다.").contains("citation assets"))
        assertFalse(E4bSourceEvidence.extract("Korean", "English", source.replace("인용해 주세요", "인용하지 마세요")).contains("citation assets"))
        assertFalse(E4bSourceEvidence.extract("Korean", "English", "그는 ‘원문 그대로 인용’이라는 문구를 설명합니다.").contains("citation assets"))
    }
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

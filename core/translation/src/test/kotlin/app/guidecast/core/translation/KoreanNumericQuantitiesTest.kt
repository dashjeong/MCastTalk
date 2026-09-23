package app.guidecast.core.translation

import org.junit.Assert.assertEquals
import org.junit.Test

class KoreanNumericQuantitiesTest {
    @Test fun compoundQuantitiesPreserveCountersParticlesAndSurroundingWords() {
        val cases = mapOf(
            "칠십 삼 년이 흘렀습니다." to "73년이 흘렀습니다.",
            "오십육년째 방송합니다." to "56년째 방송합니다.",
            "계약은 십팔 개월입니다." to "계약은 18개월입니다.",
            "참가자는 이백삼십오 명이고 비용은 삼만 이천 원입니다." to "참가자는 235명이고 비용은 32000원입니다.",
            "십 명이 십이 개를 받았습니다." to "10명이 12개를 받았습니다.",
            "이천삼백 킬로미터를 이동했습니다." to "2300킬로미터를 이동했습니다.",
            "총액은 이억 삼천만 오백 원입니다." to "총액은 230000500원입니다.",
        )
        cases.forEach { (source, expected) ->
            assertEquals(source, expected, KoreanNumericQuantities.normalizeForTranslation(source, "ko-KR"))
            assertEquals(expected, KoreanNumericQuantities.normalizeForTranslation(expected, "ko"))
        }
    }

    @Test fun homonymsUnsupportedGrammarAndWordBoundariesAreNotGuessed() {
        listOf("이 분은 선생님입니다.", "몇 분이 서 있습니다.", "일명 천명을 따른다.",
            "십분 이해했습니다.", "일본에서 충분히 쉬었습니다.", "칠십삼년생입니다.",
            "오십육년차입니다.", "제칠십삼 년", "abc칠십삼 년", "1칠십삼 년", "삼 점 오 년",
            "열두 년", "십백 년", "삼삼 년", "일억억 원", "영십 년", "사만억 원",
            "구천구백구십구조 구천구백구십구 원", "백".repeat(100) + " 원")
            .forEach { source -> assertEquals(source, source, KoreanNumericQuantities.normalizeForTranslation(source, "ko")) }
    }

    @Test fun unsupportedSourceLanguagesAndExistingNumbersRemainUntouched() {
        assertEquals("칠십 삼 년", KoreanNumericQuantities.normalizeForTranslation("칠십 삼 년", "ja"))
        val source = "계약은 18개월이며 금액은 -1.5%, 1,200원입니다."
        assertEquals(source, KoreanNumericQuantities.normalizeForTranslation(source, "ko"))
    }

    @Test fun amountExpansionBeyondProviderBudgetPreservesTheCompleteOriginal() {
        val source = "일억 원 ".repeat(200)
        assertEquals(1_000, source.length)
        val expanded = KoreanNumericQuantities.normalizeForTranslation(source, "ko")
        assertEquals("100000000원 ".repeat(200), expanded)
        assertEquals(2_200, expanded.length)
        assertEquals(source, KoreanNumericQuantities.normalizeForTranslation(source, "ko", 2_000))
    }

    @Test fun amountExpansionAtTheExactProviderBoundaryIsRetained() {
        val source = "일억 원 ".repeat(180) + "가".repeat(20)
        val expected = "100000000원 ".repeat(180) + "가".repeat(20)
        assertEquals(2_000, expected.length)
        assertEquals(expected, KoreanNumericQuantities.normalizeForTranslation(source, "ko", 2_000))
        assertEquals(source, KoreanNumericQuantities.normalizeForTranslation(source, "ko", 1_999))
    }
}

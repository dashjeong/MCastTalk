package app.guidecast.provider.gemma.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaDomainTerminologyAnchorTest {

    @Test
    fun extractsEnglishDmzTermsFromKoreanSource() {
        val text = "이번에 방문할 곳은 제3땅굴과 도라전망대입니다."
        val hints = GemmaDomainTerminologyAnchor.extractGlossaryHints(text, "en-US")

        assertNotNull(hints)
        assertTrue(hints!!.contains("\"제3땅굴\": \"the 3rd Infiltration Tunnel\""))
        assertTrue(hints.contains("\"도라전망대\": \"Dora Observatory\""))
    }

    @Test
    fun extractsJapaneseAndChineseTermsCorrectly() {
        val text = "임진각 평화누리 공원에서 출발합니다."
        val jaHints = GemmaDomainTerminologyAnchor.extractGlossaryHints(text, "ja-JP")
        val zhHints = GemmaDomainTerminologyAnchor.extractGlossaryHints(text, "zh-CN")

        assertNotNull(jaHints)
        assertTrue(jaHints!!.contains("\"임진각\": \"臨津閣\""))
        assertTrue(jaHints.contains("\"평화누리\": \"平和ヌリ公園\""))

        assertNotNull(zhHints)
        assertTrue(zhHints!!.contains("\"임진각\": \"临津阁\""))
        assertTrue(zhHints.contains("\"평화누리\": \"和平世界公园\""))
    }

    @Test
    fun returnsNullWhenNoSpecializedTermsExist() {
        val text = "안녕하세요. 오늘 날씨가 아주 맑고 좋습니다."
        val hints = GemmaDomainTerminologyAnchor.extractGlossaryHints(text, "en")

        assertNull("Ordinary sentences must not inject unnecessary glossary hints", hints)
    }

    @Test
    fun capsAtMaximumThreeTermsPerPrompt() {
        // Contains 5 terms: 제3땅굴, 도라전망대, 도라산역, 임진각, 비무장지대
        val text = "제3땅굴, 도라전망대, 도라산역, 임진각, 비무장지대를 차례로 견학합니다."
        val hints = GemmaDomainTerminologyAnchor.extractGlossaryHints(text, "en")

        assertNotNull(hints)
        val termCount = hints!!.split(", ").size
        assertEquals(3, termCount)
    }
}

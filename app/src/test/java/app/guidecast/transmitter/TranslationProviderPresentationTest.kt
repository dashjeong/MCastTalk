package app.guidecast.transmitter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationProviderPresentationTest {
    private val names = mapOf(
        "en" to "영어 · English",
        "ja" to "일본어 · 日本語",
        "zh" to "중국어 · 中文",
        "nl" to "네덜란드어 · Nederlands",
        "es" to "스페인어 · Español",
    )

    @Test
    fun fiveLanguageGemmaSelectionDisclosesTheMixedRuntimeRoute() {
        val result = translationProviderPresentation(
            translationLanguages = listOf("en", "ja", "zh", "nl", "es"),
            gemmaActive = true,
            mlKitReady = true,
            displayName = names::getValue,
        )

        assertEquals("공유 Gemma · 영어/일본어/중국어/스페인어 / 네덜란드어 ML Kit 독립 경로", result.providerLabel)
        assertTrue(result.notice.orEmpty().contains("Gemma 1개 공정 순환 처리"))
        assertTrue(
            result.notice.orEmpty()
                .contains("네덜란드어: Gemma와 분리된 ML Kit 경로"),
        )
    }

    @Test
    fun singleLanguageKeepsGemmaWithPreparedMlKitFailoverWithoutFalseNotice() {
        val result = translationProviderPresentation(
            translationLanguages = listOf("en"),
            gemmaActive = true,
            mlKitReady = true,
            displayName = names::getValue,
        )

        assertEquals("Gemma", result.providerLabel)
        assertNull(result.notice)
    }

    @Test
    fun missingFallbackDoesNotBlockOtherMultilingualChannels() {
        val result = translationProviderPresentation(
            translationLanguages = listOf("en", "ja", "zh", "nl", "es"),
            gemmaActive = true,
            mlKitReady = false,
            displayName = names::getValue,
        )

        assertTrue(result.notice.orEmpty().contains("대체 모델 준비 확인 필요"))
        assertTrue(result.providerLabel.contains("독립 경로"))
    }

    @Test
    fun preparingSummaryDoesNotClaimFiveParallelGemmaInvocations() {
        assertEquals(
            "공유 Gemma 준비 중 · 언어별 준비 상태 확인",
            preparingTranslationProviderLabel(
                translationLanguages = listOf("en", "ja", "zh", "nl", "es"),
                useGemma = true,
                displayName = names::getValue,
            ),
        )
    }
}

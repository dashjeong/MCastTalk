package app.guidecast.transmitter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import app.guidecast.core.translation.TranslationStyle

class ReviewedDomainComparisonGuardTest {

    private fun sampleComparison(
        original: String = "3번 게이트로 이동하세요.",
        offline: String = "Please go to gate 3.",
        source: String = "ko",
        target: String = "en",
        online: String = "Please move to gate 3.",
    ): ShadowComparison = ShadowComparison(
        original = original,
        contextBefore = null,
        source = source,
        target = target,
        corpusRevision = 1L,
        online = online,
        offline = offline,
        style = TranslationStyle.CONVERSATIONAL,
    )

    @Test
    fun unchangedValidNumericTranslationPasses(): Unit {
        val comparison = sampleComparison()
        val corrected = "Please proceed to gate 3."
        val pair = reviewedDomainComparisonPair(comparison, corrected, humanReviewed = true)
        assertEquals(corrected, pair.corrected)
        assertEquals("3번 게이트로 이동하세요.", pair.original)
    }

    private fun nativeComparison() = sampleComparison().copy(nativeIdentity = NativeComparisonIdentity(
        1, "fixture-input", "fixture-response", 1, 1, TranslationApiProvider.OPENAI_REALTIME,
        "gpt-realtime-2.1-mini", 1))

    @Test fun nativeReviewedCorrectionCannotIntroduceAnAttachedCredentialShape() {
        val original = "회의 보고 내용을 검토하고 관련 부서와 준비 사항을 정리해 주세요."
        val offline = "please review the meeting report and organize preparations with related departments."
        val comparison = nativeComparison().copy(original = original, offline = offline, online = offline)
        for (key in listOf("xsk-" + "a".repeat(20), "xAIza" + "a".repeat(30))) {
            val corrected = "$offline $key"
            assertFalse(containsCredentialLikeText(corrected))
            assertTrue(containsNativeContextCredentialLikeText(corrected))
            assertTrue(conservativeReviewAccepted(original, offline, corrected, "en"))
            assertEquals(corrected, reviewedDomainComparisonPair(comparison.copy(nativeIdentity = null), corrected, true).corrected)
            assertThrows(IllegalArgumentException::class.java) {
                reviewedDomainComparisonPair(comparison, corrected, true)
            }
            assertThrows(IllegalArgumentException::class.java) {
                reviewedDomainComparisonPair(comparison.copy(original = "$original $key"), offline, true)
            }
        }
    }

    @Test fun nativeReviewRejectsMalformedUnicodeAndKeepsValidOrdinaryCorrections() {
        val offline = "please review the meeting report and organize preparations with related departments."
        val comparison = nativeComparison().copy(original = "회의 보고 내용을 검토하고 관련 부서와 준비 사항을 정리해 주세요.",
            offline = offline, online = offline)
        for (suffix in listOf("\uD800", "\uDC00")) {
            assertTrue(conservativeReviewAccepted(comparison.original, offline, "$offline $suffix", "en"))
            assertThrows(IllegalArgumentException::class.java) {
                reviewedDomainComparisonPair(comparison, "$offline $suffix", true)
            }
        }
        assertEquals("Please proceed to gate 3.",
            reviewedDomainComparisonPair(nativeComparison(), "Please proceed to gate 3.", true).corrected)
    }

    @Test
    fun changedNumericValueRejects(): Unit {
        val comparison = sampleComparison()
        val corrected = "Please proceed to gate 4."
        assertThrows(IllegalArgumentException::class.java) {
            reviewedDomainComparisonPair(comparison, corrected, humanReviewed = true)
        }
    }

    @Test
    fun negativeChineseDeclarativeCannotBecomeQuestion(): Unit {
        val comparison = sampleComparison(
            original = "이것은 사실이 아닙니다.",
            offline = "这不是事实。",
            source = "ko",
            target = "zh",
            online = "这不是事实。",
        )
        val candidateQuestion = "这不是事实吗？"
        assertThrows(IllegalStateException::class.java) {
            reviewedDomainComparisonPair(comparison, candidateQuestion, humanReviewed = true)
        }
    }

    @Test
    fun preservedVerbatimQuotesPasses(): Unit {
        val comparison = sampleComparison(
            original = "\"Alpha\"와 \"Beta\" 두 표현을 원문 그대로 인용",
            offline = "Please quote \"Alpha\" and \"Beta\" verbatim",
            source = "ko",
            target = "en",
            online = "Please quote \"Alpha\" and \"Beta\" verbatim",
        )
        val corrected = "Please quote \"Alpha\" and \"Beta\" verbatim"
        val pair = reviewedDomainComparisonPair(comparison, corrected, humanReviewed = true)
        assertEquals(corrected, pair.corrected)
    }

    @Test
    fun missingHumanReviewRejects(): Unit {
        val comparison = sampleComparison()
        assertThrows(IllegalArgumentException::class.java) {
            reviewedDomainComparisonPair(comparison, "Please proceed to gate 3.", humanReviewed = false)
        }
    }

    @Test
    fun blankOfflineEvidenceRejects(): Unit {
        val comparison = sampleComparison(offline = "   ")
        assertThrows(IllegalArgumentException::class.java) {
            reviewedDomainComparisonPair(comparison, "Please proceed to gate 3.", humanReviewed = true)
        }
    }

    @Test
    fun invalidSameSourceTargetRejects(): Unit {
        val comparison = sampleComparison(source = "ko", target = "ko-KR")
        assertThrows(IllegalArgumentException::class.java) {
            reviewedDomainComparisonPair(comparison, "Please proceed to gate 3.", humanReviewed = true)
        }
    }
}

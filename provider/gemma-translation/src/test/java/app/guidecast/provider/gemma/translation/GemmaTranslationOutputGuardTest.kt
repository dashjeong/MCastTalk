package app.guidecast.provider.gemma.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class GemmaTranslationOutputGuardTest {
    private val original = "담당자는 회의 일정이 바뀌었다고 안내했습니다."

    @Test fun copiedKoreanSentenceIsNotASuccessfulDifferentScriptTranslation() {
        for (target in listOf("en", "es", "ar", "ja", "zh", "zh-TW")) {
            rejected(original, original, target)
        }
    }

    @Test fun spacingPunctuationAndSmallSourceRewritesCannotConcealCopying() {
        rejected(original, "담당자는 회의일정이 바뀌었다고 안내했습니다!", "en")
        rejected(original, "담당자는 회의 일정이 변경되었다고 안내했습니다.", "en")
    }

    @Test fun actualTranslationsCanRetainOriginalNamesAndQuotedKorean() {
        requireGemmaTranslationIsNotCopiedSource(original, "The coordinator said the meeting schedule had changed.", "ko", "en")
        requireGemmaTranslationIsNotCopiedSource(original, "김민수 said the meeting schedule had changed.", "ko", "en")
        requireGemmaTranslationIsNotCopiedSource(original, "He said: 담당자는 회의 일정이 바뀌었다고 안내했습니다.", "ko", "en")
    }

    @Test fun namesNumbersLabelsAndSameScriptPairsAreNotRejected() {
        for (name in listOf("김민수", "123.45", "대한민국 서울특별시 종로구 세종대로", "경복궁 국립고궁박물관")) {
            requireGemmaTranslationIsNotCopiedSource(name, name, "ko", "en")
        }
        requireGemmaTranslationIsNotCopiedSource("No", "No", "en", "es")
        requireGemmaTranslationIsNotCopiedSource("東京", "東京", "ja", "zh")
        requireGemmaTranslationIsNotCopiedSource(original, original, "ko", "ko")
    }

    @Test fun unrelatedWrongLanguageOutputIsNotMisreportedAsProvenSourceCopy() {
        requireGemmaTranslationIsNotCopiedSource(original, "오늘은 날씨가 맑고 산책하기에 좋은 하루입니다.", "ko", "en")
    }

    private fun rejected(source: String, output: String, target: String) {
        try {
            requireGemmaTranslationIsNotCopiedSource(source, output, "ko", target)
            fail("A copied sentence must not enter the translated output")
        } catch (error: IllegalStateException) {
            assertEquals("GEMMA_UNTRANSLATED_SOURCE_COPY", error.message)
        }
    }
}

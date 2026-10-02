package app.guidecast.provider.gemma.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class GemmaTranslationOutputGuardTest {
    private val original = "담당자는 회의 일정이 바뀌었다고 안내했습니다."

    @Test fun fullEnglishSentenceCannotSilentlySucceedAsJapaneseOrChinese() {
        for (target in listOf("ja", "zh", "zh-TW")) {
            try {
                requireGemmaTargetScript(original, "The coordinator announced that the meeting schedule had changed today.", target)
                fail("English sentence must not enter a Japanese/Chinese output channel")
            } catch (error: IllegalStateException) {
                assertEquals("GEMMA_TARGET_SCRIPT_MISMATCH", error.message)
            }
        }
    }

    @Test fun scriptGuardAllowsActualTranslationsAndShortNamesOrAcronyms() {
        requireGemmaTargetScript(original, "担当者は会議の日程が変更されたと案内しました。", "ja")
        requireGemmaTargetScript(original, "负责人通知会议日程已更改。", "zh")
        requireGemmaTargetScript(original, "MSS / TIPS / IR", "ja")
        requireGemmaTargetScript("브랜드 이름은 Open AI Test Company LLC 입니다.", "Open AI Test Company LLC", "zh")
    }

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

    @Test fun sharedOutputContractRejectsWrongTargetWithoutRepairingOrChangingSource() {
        val source = "상사가 직원에게 제안서를 수정하라고 말했지만 자신이 수정하겠다고 약속하지 않았습니다."
        val output = "The supervisor told the employee to revise the proposal but did not promise to do it personally."
        try {
            validateAndRepairGemmaTranslation(source, output, "ko", "zh")
            fail("Every model path must isolate a wrong-script sentence")
        } catch (error: IllegalStateException) {
            assertEquals("GEMMA_TARGET_SCRIPT_MISMATCH", error.message)
            assertEquals("상사가 직원에게 제안서를 수정하라고 말했지만 자신이 수정하겠다고 약속하지 않았습니다.", source)
        }
    }

    @Test fun sharedOutputContractRepairsOnlyUniquelyAlignedWonLabel() {
        assertEquals("売上は350億ウォンで、8.5%増えました。", validateAndRepairGemmaTranslation(
            "매출은 350억 원으로 8.5% 증가했습니다.", "売上は350億円で、8.5%増えました。", "ko", "ja"))
        val mixed = "韓国費用は350億円、日本費用は2億円です。"
        assertEquals(mixed, validateAndRepairGemmaTranslation(
            "한국 비용은 350억 원이고 일본 비용은 2억 엔입니다.", mixed, "ko", "ja"))
    }

    @Test fun sharedOutputContractPreservesSingleExplicitVerbatimTypoWithoutGuessingMultipleQuotes() {
        assertEquals("The report quoted ‘배포 지연됌’ verbatim.", validateAndRepairGemmaTranslation(
            "보고서에 ‘배포 지연됌’이라고 오기되었지만 원문을 그대로 인용했습니다.",
            "The report quoted ‘deployment delayed’ verbatim.", "ko", "en"))
        val multiple = "The original said ‘topic’ rather than ‘task’."
        assertEquals(multiple, validateAndRepairGemmaTranslation(
            "‘과제’가 아닌 ‘화제’라고 오기되어 원문 그대로 인용하여 정정 요청하세요.",
            multiple, "ko", "en"))
    }

    @Test fun sharedOutputContractAllowsOrdinarySpokenQuotationAndKeepsCopyRejection() {
        val spoken = "The manager said, ‘Please finish today.’"
        assertEquals(spoken, validateAndRepairGemmaTranslation(
            "부장님이 ‘오늘 마무리해 주세요’라고 말씀했습니다.", spoken, "ko", "en"))
        try {
            validateAndRepairGemmaTranslation(original, original, "ko", "en")
            fail("Shared validation must retain copied-source rejection")
        } catch (error: IllegalStateException) {
            assertEquals("GEMMA_UNTRANSLATED_SOURCE_COPY", error.message)
        }
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

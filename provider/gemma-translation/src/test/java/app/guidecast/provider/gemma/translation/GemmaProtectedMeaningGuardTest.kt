package app.guidecast.provider.gemma.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class GemmaProtectedMeaningGuardTest {
    @Test(timeout = 1000) fun longNonMoneyDigitRunsDoNotPartitionExponentially() {
        for (length in listOf(36, 128, 512)) {
            accepts("문서 번호 ${"1".repeat(length)}개입니다.", "文書番号です。", "ja")
        }
    }
    @Test fun negatedDeclarationCannotBecomeAQuestion() {
        rejects("지시한 것은 맞지만 직접 보내겠다는 뜻은 아니에요.", "让我发送，但他不是说自己会发送吗？", "zh", "GEMMA_SENTENCE_TYPE_REVIEW_REQUIRED")
    }
    @Test fun directRhetoricalAndQuotedQuestionsAreNotDeclarations() {
        accepts("직접 보내겠다는 뜻은 아니에요?", "不是自己发吗？", "zh")
        accepts("‘직접 보내나요?’라고 물은 것은 아니에요.", "不是问‘自己发吗？’。", "zh")
        accepts("보내지 않은 것은 아니에요.", "不是没发吗？", "zh")
        accepts("직접 보내겠다는 뜻은 아니에요.", "他不是说自己会发送。", "zh")
    }
    @Test fun currenciesCompareValuesWithScalesAndWithoutOrder() {
        accepts("한국 비용은 1,500억 원이고 일본 비용은 3억2천만 엔입니다.", "日本の費用は3億2千万円で、韓国の費用は1500億ウォンです。", "ja")
        rejects("한국 비용은 350억 원이고 일본 비용은 2억 엔입니다.", "韓国費用は350億円、日本費用は2億円です。", "ja", "GEMMA_CURRENCY_ASSET_REVIEW_REQUIRED")
        rejects("한국 비용은 350억 원이고 일본 비용은 2억 엔입니다.", "韓国費用は350億ウォンです。", "ja", "GEMMA_CURRENCY_ASSET_REVIEW_REQUIRED")
    }
    @Test fun completeExtractionAndLabelCountsShareCurrencyParticleBoundaries() {
        val source = "본사 비용은 720억 원이고 지점 비용은 2억 3천만 엔으로 정했습니다."
        accepts(source, "本社の費用は720億ウォン、支店の費用は2億3千万円に決まりました。", "ja")
        rejects(source, "本社の費用は720億円、支店の費用は2億3천万円に決まりました。", "ja", "GEMMA_CURRENCY_ASSET_REVIEW_REQUIRED")
    }
    @Test fun exchangedQuotedAndNonCurrencyWordsRemainOutsideAssetContract() {
        accepts("한국 비용 350억 원을 2억 엔으로 환산합니다.", "費用は2億円です。", "ja")
        accepts("‘350억 원’과 ‘2억 엔’이라는 표현을 설명합니다.", "金額表現を説明します。", "ja")
        accepts("지원 2건의 원인과 엔진 3대입니다.", "支援2件の原因とエンジン3台です。", "ja")
    }
    @Test fun explicitlyBothVerbatimQuotesAreProtectedIncludingDuplicates() {
        val source = "‘승인 대기’와 ‘승인 데기’ 두 문구를 원문 그대로 인용하세요."
        accepts(source, "Quote ‘승인 대기’ and ‘승인 데기’ verbatim.", "en")
        rejects(source, "Quote ‘approval pending’ and ‘승인 데기’ verbatim.", "en", "GEMMA_VERBATIM_QUOTE_REVIEW_REQUIRED")
        rejects("‘대기’와 ‘대기’ 두 문구를 원문 그대로 인용하세요.", "Quote ‘대기’.", "en", "GEMMA_VERBATIM_QUOTE_REVIEW_REQUIRED")
    }
    @Test fun typoOnlyScopeDoesNotForceNormalComparisonPhraseToStayKorean() {
        accepts("정상 ‘승인 대기’ 대신 오기 ‘승인 데기’가 적혔으니 원문 그대로 인용하세요.", "It says ‘승인 데기’ instead of ‘approval pending’; quote the typo verbatim.", "en")
        accepts("‘왜 안 되나요?’라고 물은 것은 아니에요.", "They did not ask ‘Why doesn't it work?’.", "en")
        accepts("‘표결’이 아닌 ‘표골’이라고 오기되었으니 원문 표기 그대로 인용하세요.", "Quote the typo ‘표골’ rather than ‘vote’ verbatim.", "en")
        rejects("‘표결’이 아닌 ‘표골’이라고 오기되었으니 원문 표기 그대로 인용하세요.", "Quote ‘vote’ and ‘typo’.", "en", "GEMMA_VERBATIM_QUOTE_REVIEW_REQUIRED")
    }
    @Test fun unsupportedCorrectMoneyNotationIsNotAProvenValueFailure() {
        val source = "한국 비용은 350억 원이고 일본 비용은 2억 엔입니다."
        accepts(source, "費用は三百五十億ウォンと二億円です。", "ja")
        accepts(source, "費用は35,000,000,000 KRWと200,000,000 JPYです。", "ja")
        rejects(source, "費用は三百五十億円と二億円です。", "ja", "GEMMA_CURRENCY_ASSET_REVIEW_REQUIRED")
        accepts("한국 비용은 350억 원이고 일본 비용은 2억 엔이며 추가 비용은 오억 원입니다.", "費用は350億円と2億円です。", "ja")
    }
    @Test fun explicitNegationOfQuoteActionDoesNotRequireVerbatimOutput() {
        for (action in listOf("인용하지 말고", "보존하지 마세요", "기재하지 않고", "인용해서는 안 됩니다")) {
            accepts("‘완료 대기’와 ‘완료 데기’ 두 문구를 원문 그대로 $action 자연스럽게 설명하세요.",
                "Explain the waiting and misspelled status naturally.", "en")
        }
        accepts("‘문서 배포’가 아닌 ‘문서 배표’라고 오기되었지만 원문 표기 그대로 인용하지 말고 뜻을 설명하세요.",
            "Explain the intended document distribution meaning.", "en")
    }
    @Test fun contrastAndNegationInsideAQuoteDoNotDisablePositiveVerbatimScope() {
        rejects("‘확정 아님’과 ‘확정 안임’ 두 문구를 원문 그대로 인용하세요.",
            "Quote ‘not confirmed’ and ‘not verified’.", "en", "GEMMA_VERBATIM_QUOTE_REVIEW_REQUIRED")
        rejects("‘배포’가 아닌 ‘배표’라고 오기되었으니 원문 그대로 인용하세요.",
            "Quote ‘distribution’.", "en", "GEMMA_VERBATIM_QUOTE_REVIEW_REQUIRED")
        rejects("‘인용하지 말라’와 ‘인용하지 마세요’ 두 문구를 원문 그대로 인용하세요.",
            "Quote ‘Do not quote’ twice.", "en", "GEMMA_VERBATIM_QUOTE_REVIEW_REQUIRED")
    }
    @Test fun quoteActionNegationDoesNotDisableIndependentMoneyOrSentenceChecks() {
        rejects("한국 비용은 350억 원이고 일본 비용은 2억 엔입니다. 원문 그대로 기재하지 말고 설명하세요.",
            "韓国費用は350億円、日本費用は2億円です。", "ja", "GEMMA_CURRENCY_ASSET_REVIEW_REQUIRED")
        rejects("‘검토’와 ‘완료’ 두 문구를 원문 그대로 인용하지 말고 요약하세요. 내가 확정한 것은 아니에요.",
            "不是我确认的吗？", "zh", "GEMMA_SENTENCE_TYPE_REVIEW_REQUIRED")
    }
    @Test fun sharedContractRetainsSingleRepairButRejectsMultipleAssetMismatch() {
        assertEquals("費用は350億ウォンです。", validateAndRepairGemmaTranslation("비용은 350억 원입니다.", "費用は350億円です。", "ko", "ja"))
        rejects("한국 비용은 350억 원이고 일본 비용은 2억 엔입니다.", "韓国費用は350億円、日本費用は2億円です。", "ja", "GEMMA_CURRENCY_ASSET_REVIEW_REQUIRED")
    }
    private fun accepts(source: String, output: String, target: String) = requireGemmaProtectedMeaning(source, output, "ko", target)
    private fun rejects(source: String, output: String, target: String, code: String) {
        try { requireGemmaProtectedMeaning(source, output, "ko", target); fail("Expected review-required rejection") }
        catch (error: IllegalStateException) { assertEquals(code, error.message) }
    }
}

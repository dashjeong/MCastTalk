package app.guidecast.transmitter

import app.guidecast.core.translation.BoundedQueuedTranslationEngine
import app.guidecast.core.translation.TranslationStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class TextFidelityGuardTest {
    @Test fun repairsOnlyEquivalentSingleWonCurrencyLabel() {
        val source = "올해 2분기 매출은 1,240억 원으로 작년보다 8.2% 줄었습니다."
        val translated = "今年第2四半期の売上は1,240億円で、前年より8.2%減少しました。"
        assertEquals(translated.replace("円", "ウォン"), TextFidelityGuard.repair(source, translated, "ko-KR", "ja-JP"))
    }

    @Test fun supportsUnscaledAndOtherAlignedScalesWithoutFixedAmounts() {
        for ((sourceAmount, targetAmount) in listOf("650 원" to "650円", "17만 원" to "17万円", "2.6조 원" to "2.6兆円")) {
            assertEquals("金額は ${targetAmount.replace("円", "ウォン")}です。", TextFidelityGuard.repair("금액은 $sourceAmount 입니다.", "金額は ${targetAmount}です。", "ko", "ja"))
        }
    }

    @Test fun refusesCurrencyAmbiguityMultipleAmountsExchangeAndNumberChanges() {
        val candidates = listOf(
            "원화 620만 원과 일본 엔화 45만 엔을 별도로 처리했습니다." to "620万円と45万円を別々に処理しました。",
            "예산 18만 원과 비용 7만 원입니다." to "予算は 18万円、費用は 7万円です。",
            "환율에 따라 18만 원을 환산했습니다." to "18万円に換算しました。",
            "지원금은 18만 원입니다." to "支援金は 19万円です。",
            "지원금은 18만 원입니다." to "支援金は 18億円です。",
            "직원 4명의 지원금은 18만 원입니다." to "職員5人の支援金は 18万円です。",
            "지원금은 18만 원입니다." to "支援金は 18万円と追加分は円建てです。",
            "반지름 2센티미터인 원을 세 개 그렸습니다." to "半径2センチの円を3つ描きました。",
            "지원금은 12,34만 원입니다." to "支援金は 12,34万円です。",
            "변동액은 -18만 원입니다." to "変動額は18万円です。",
            "십만 원과 18만 원을 더했습니다." to "18万円を足しました。",
            "빛의 3원색을 비교합니다." to "光の3円を比較します。",
            "이것은 2원자 분자입니다." to "これは2円の分子です。",
            "장애의 4원인을 설명합니다." to "障害の4円を説明します。",
        )
        for ((source, target) in candidates) assertEquals(target, TextFidelityGuard.repair(source, target, "ko", "ja"))
    }

    @Test fun restoresOnlyTheSingleExplicitlyVerbatimMisspelling() {
        val source = "회의록에는 부서명이 ‘운여지원팀’으로 오기되어 있지만 원문 그대로 인용합니다."
        val outputs = mapOf(
            "en" to "The department's name was misspelled as 'Operations Support Team', but quoted verbatim.",
            "ja" to "部署名は「運営支援チーム」と誤記されていますが、原文どおり引用しています。",
            "zh" to "部门名称误写为“运营支援组”，但按原文照录。",
        )
        for ((language, target) in outputs) {
            val wrong = when (language) { "en" -> "Operations Support Team"; "ja" -> "運営支援チーム"; else -> "运营支援组" }
            assertEquals(target.replace(wrong, "운여지원팀"), TextFidelityGuard.repair(source, target, "ko", language))
        }
    }

    @Test fun leavesOrdinaryTranslatedSpeechAndAmbiguousQuotesUnchanged() {
        val cases = listOf(
            "정 과장이 ‘작업을 멈추세요’라고 전했습니다." to "Manager Jeong said, 'Stop the work.'",
            "명칭 ‘운여지원팀’이 오기되었습니다." to "The name 'Operations Support Team' was misspelled.",
            "명칭 ‘운여지원팀’을 원문 그대로 인용합니다." to "The name 'Operations Support Team' is quoted verbatim.",
            "‘운여지원팀’과 ‘고겍팀’이 오기되어 원문 그대로 인용합니다." to "The names 'Operations Support Team' and 'Customer Team' are quoted.",
            "명칭 ‘운여지원팀’이 오기되어 원문 그대로 인용합니다." to "The name 'Operations' or 'Support Team' is quoted.",
            "명칭 ‘운여지원팀’이 오기되어 원문 그대로 인용합니다." to "The name 'Operations Support Team is quoted.",
            "명칭 ‘운여'지원'팀’이 오기되어 원문 그대로 인용합니다." to "The name 'Operations Support Team' is quoted.",
            "그는 ‘원문 그대로 인용한 오기’라는 표현을 번역했습니다." to "He translated 'a verbatim quoted typo'.",
            "명칭 ‘운여지원팀’이 오기되어 원문 그대로 인용하지 말고 고쳐 주세요." to "Use the corrected name 'Operations Support Team'.",
        )
        for ((source, target) in cases) assertEquals(target, TextFidelityGuard.repair(source, target, "ko", "en"))
    }

    @Test fun neverGuessesReportedOrderOrPersonalPromiseRoles() {
        val source = "부장이 대리에게 계약서를 확인하라고 했지, 자신이 확인하겠다고 약속한 것은 아닙니다."
        val target = "部長は代理に契約書を確認するよう言ったが、直接確認するよう言ったわけではない。"
        assertEquals(target, TextFidelityGuard.repair(source, target, "ko", "ja"))
    }

    @Test fun domainOffAndApprovedExactOutputsAreUntouched() = runBlocking {
        val source = "지원금은 37만 원입니다."
        val output = "支援金は 37万円です。"
        assertEquals(output, engine(DomainCorpusMatch(null, ""), output).translate(source, "ko", "ja"))
        assertEquals(output, engine(DomainCorpusMatch(output, "domain hints"), "unused").translate(source, "ko", "ja"))
        assertEquals(output.replace("円", "ウォン"), engine(DomainCorpusMatch(null, "domain hints"), output).translate(source, "ko", "ja"))
    }

    @Test fun domainCancellationAndWatchdogBudgetRemainUnchanged() = runBlocking {
        val engine = engine(DomainCorpusMatch(null, "domain hints"), "", cancel = true)
        assertEquals(4_200L, engine.maximumCallDurationMillis)
        try {
            engine.translate("지원금은 37만 원입니다.", "ko", "ja")
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            // Cancellation is not converted to text or a successful repair.
        }
    }

    private fun engine(match: DomainCorpusMatch, output: String, cancel: Boolean = false): DomainCorpusTranslationEngine {
        val repository = object : DomainCorpusRepository(null) {
            override suspend fun match(text: String, source: String, target: String, style: TranslationStyle) = match
        }
        val delegate = object : BoundedQueuedTranslationEngine {
            override val maximumCallDurationMillis = 4_000L
            override suspend fun translateWithContext(text: String, contextBefore: String?, sourceLanguageTag: String, targetLanguageTag: String): String {
                if (cancel) throw CancellationException("test cancellation")
                return output
            }
        }
        return DomainCorpusTranslationEngine(delegate, repository)
    }
}

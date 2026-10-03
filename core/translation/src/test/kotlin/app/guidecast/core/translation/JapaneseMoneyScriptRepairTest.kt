package app.guidecast.core.translation

import org.junit.Assert.assertEquals
import org.junit.Test

class JapaneseMoneyScriptRepairTest {
    @Test fun conversationalEndingsKeepBothCurrencyIdentitiesObservable() {
        val source = "출장 항공료는 230만 원이고 숙박비는 42만 엔이에요."
        assertEquals("230만 원 = 2300000 KRW; 42만 엔 = 420000 JPY", protectedSourceMoneyEvidence(source))
        val nativeAlternate = "航空券代は230万ウォン、宿泊費は42万エンです。"
        requireProtectedTranslationMeaning(source, nativeAlternate, "ko", "ja")
        assertEquals("航空券代は230万ウォン、宿泊費は42万円です。", TextFidelityGuard.repair(source, nativeAlternate, "ko", "ja"))
        assertEquals("", protectedSourceMoneyEvidence("환율로 230만 원을 환산합니다."))
        assertEquals("", protectedSourceMoneyEvidence("금액 ‘230만 원’을 원문 그대로 인용합니다."))
        assertEquals("", protectedSourceMoneyEvidence("-230만 원입니다."))
        try {
            requireProtectedTranslationMeaning(source, "航空券は230万円、宿泊費は42万円です。", "ko", "ja")
            org.junit.Assert.fail("KRW cannot silently become JPY with a conversational ending")
        } catch (expected: IllegalStateException) {
            assertEquals("GEMMA_CURRENCY_ASSET_REVIEW_REQUIRED", expected.message)
        }
    }
    @Test fun repairsMixedGlyphsWithoutChangingValuesOrCurrencies() {
        val source = "개발비는 1,500억 원, 사용료는 3억 2천만 엔입니다."
        val output = "開発費は1,500億ウォン、使用料は3億2천万円です。"
        assertEquals("開発費は1,500億ウォン、使用料は3億2千万円です。",
            TextFidelityGuard.repair(source, output, "ko-KR", "ja-JP"))
        assertEquals("設備費は2億4千万ウォン、上限は9千万 円です。",
            TextFidelityGuard.repair("설비비는 2억 4천만 원, 상한은 9천만 엔입니다.",
                "設備費は2億4천만ウォン、上限は9천만 엔です。", "ko", "ja"))
    }

    @Test fun doesNotGuessAnIncorrectAmountCurrencyOrIncompleteMoneyBag() {
        val source = "설비비는 2억 4천만 원, 상한은 9천만 엔입니다."
        for (output in listOf("設備費は2億5천만ウォン、上限は9千万 円です。",
            "設備費は2億4천만円、上限は9千万 円です。", "設備費は2億4천만ウォンです。",
            "設備費は2億4천만ウォン、上限は九千万円です。",
            "設備費は2億4천만ウォン、上限は9천만 엔입니다。")) {
            assertEquals(output, TextFidelityGuard.repair(source, output, "ko", "ja"))
        }
    }

    @Test fun preservesQuotesExchangeSignedValuesAndUnrelatedHangul() {
        val outputs = listOf(
            "금액 ‘4천만 엔’을 원문 그대로 인용합니다." to "金額「4천만 엔」を引用します。",
            "환율로 4천만 엔을 환산합니다." to "4천万円に換算します。",
            "차이는 -4천만 엔입니다." to "差は4천万円です。",
            "금액은 4천만 엔입니다." to "担当は김천、金額は4천万円です。")
        for ((source, output) in outputs.take(3)) assertEquals(output, TextFidelityGuard.repair(source, output, "ko", "ja"))
        assertEquals("担当は김천、金額は4千万円です。", TextFidelityGuard.repair(outputs.last().first, outputs.last().second, "ko", "ja"))
        assertEquals(outputs.last().second, TextFidelityGuard.repair(outputs.last().first, outputs.last().second, "ko", "zh"))
    }
}

package app.guidecast.core.translation

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishCardinalReviewGuardTest {
    @Test fun correctEnglishSeventyThreeAndNineCanRepairAnIncorrectDraft() {
        assertTrue(reviewResultIsConservative("칠십 삼 년이 흘렀습니다.", "Only 7 years passed.",
            "Seventy-three years have passed."))
        assertTrue(reviewResultIsConservative("만 9년이 지났습니다.", "Over 9 years passed.",
            "Nine years have passed."))
        assertFalse(reviewResultIsConservative("칠십 삼 년이 흘렀습니다.", "Only 7 years passed.",
            "Seventy-four years have passed."))
        assertFalse(reviewResultIsConservative("칠십 삼 년이 흘렀습니다.", "Only 7 years passed.",
            "Years have passed."))
        assertTrue(reviewResultIsConservative("73년입니다.", "One million years.",
            "Seventy-three years."))
    }

    @Test fun strictEnglishCardinalGrammarSupportsTheDeclaredRange() {
        mapOf(
            "zero" to 0, "nineteen" to 19, "thirty five" to 35, "seventy-three" to 73,
            "ninety‑nine" to 99, "one hundred" to 100, "one hundred and six" to 106,
            "nine hundred ninety-nine" to 999, "one thousand and one" to 1_001,
            "twelve thousand three hundred forty-five" to 12_345,
            "nine hundred and ninety-nine thousand nine hundred and ninety-nine" to 999_999,
        ).forEach { (word, value) ->
            assertTrue(word, reviewResultIsConservative("$value 개입니다.", "Draft $value items.", "$word items."))
        }
    }

    @Test fun duplicateValuesAndTheirOrderCannotBeDroppedOrSwapped() {
        assertTrue(reviewResultIsConservative("3명과 의자 3개입니다.", "3 people and 3 chairs.",
            "Three people and three chairs."))
        assertFalse(reviewResultIsConservative("3명과 의자 3개입니다.", "3 people and 3 chairs.",
            "Three people."))
        assertFalse(reviewResultIsConservative("2명과 의자 3개입니다.", "2 people and 3 chairs.",
            "Three people and two chairs."))
    }

    @Test fun conjunctionListsRemainSeparateButInternalHundredAndThousandStayCompound() {
        assertTrue(reviewResultIsConservative("값은 3과 5입니다.", "Values are 3 and 5.", "Values are three and five."))
        assertTrue(reviewResultIsConservative("값은 3과 3입니다.", "Values are 3 and 3.", "Values are three and three."))
        assertTrue(reviewResultIsConservative("값은 3과 5입니다.", "Values are 3 and 5.", "Values are three and 5."))
        assertFalse(reviewResultIsConservative("값은 3과 5입니다.", "Values are 3 and 5.", "Values are five and three."))
        assertFalse(reviewResultIsConservative("값은 3입니다.", "The value is 3.", "Values are three and five."))
        assertTrue(reviewResultIsConservative("값은 105입니다.", "The value is 105.", "The value is one hundred and five."))
        assertTrue(reviewResultIsConservative("값은 1003입니다.", "The value is 1003.", "The value is one thousand and three."))
        assertTrue(reviewResultIsConservative("값은 103과 5입니다.", "Values are 103 and 5.",
            "Values are one hundred and three and five."))
        assertTrue(reviewResultIsConservative("값은 -5와 +9입니다.", "Values are -5 and +9.",
            "Values are minus five and plus nine."))
        assertFalse(reviewResultIsConservative("값은 3입니다.", "The value is 3.", "The value is three and a half."))
    }

    @Test fun digitHyphenScaleAndFractionAffixesCannotMasqueradeAsTheLeadingDigit() {
        listOf("1-million-dollar", "1 - million-dollar", "1‑million‑dollar", "1‐million-dollar",
            "1–million-dollar", "1−million-dollar", "1-billion-dollar", "1-half", "1-and-a-half",
            "1-dozen", "one-million-dollar").forEach { unsupported ->
            assertFalse(unsupported, reviewResultIsConservative("값은 1입니다.", "The value is 1.", "A $unsupported value."))
        }
        assertFalse(reviewResultIsConservative("값은 73입니다.", "The value is 73.", "The range is seventy–three."))
        assertTrue(reviewResultIsConservative("처음에는 73명이 있었습니다.", "At first, 73 people were there.",
            "At first, seventy-three people were there."))
    }

    @Test fun currencyGroupingAndExplicitSignsRetainTheirValues() {
        assertTrue(reviewResultIsConservative("가격은 $1,234입니다.", "The price is $1,234.",
            "The price is one thousand two hundred thirty-four dollars."))
        assertFalse(reviewResultIsConservative("가격은 $1,234입니다.", "The price is $1,234.",
            "The price is one thousand two hundred thirty-five dollars."))
        assertTrue(reviewResultIsConservative("온도는 -5도입니다.", "Temperature: -5 degrees.",
            "The temperature is minus five degrees."))
        assertTrue(reviewResultIsConservative("온도는 -5도입니다.", "Temperature: -5 degrees.",
            "The temperature is negative 5 degrees."))
        assertTrue(reviewResultIsConservative("온도는 +5도입니다.", "Temperature: +5 degrees.",
            "The temperature is plus five degrees."))
        assertFalse(reviewResultIsConservative("온도는 -5도입니다.", "Temperature: -5 degrees.",
            "The temperature is five degrees."))
        assertFalse(reviewResultIsConservative("온도는 5도입니다.", "Temperature: 5 degrees.",
            "The temperature is minus five degrees."))
        assertFalse(reviewResultIsConservative("온도는 5도입니다.", "Temperature: 5 degrees.",
            "The temperature is negative 5 degrees."))
    }

    @Test fun decimalTimePercentAndLeadingZeroNotationAreNotFlattenedIntoIntegers() {
        assertTrue(reviewResultIsConservative("1.5리터와 09:30 그리고 3%입니다.", "1.5 L, 09:30, 3%.",
            "1.5 liters at 09:30 and 3%."))
        assertFalse(reviewResultIsConservative("1.5리터입니다.", "1.5 liters.", "One point five liters."))
        assertFalse(reviewResultIsConservative("1개와 5개입니다.", "1 item and 5 items.", "One point five items."))
        assertFalse(reviewResultIsConservative("09:30입니다.", "09:30.", "Nine thirty."))
        assertFalse(reviewResultIsConservative("3%입니다.", "3%.", "Three items."))
        assertTrue(reviewResultIsConservative("3%입니다.", "3%.", "Three%."))
        assertFalse(reviewResultIsConservative("007번입니다.", "Number 007.", "Number seven."))
    }

    @Test fun unsupportedMagnitudeFractionOrdinalOrMalformedGrammarNeverPartiallyMatches() {
        listOf("one million", "1 million", "one billion", "one and a half", "1 and a half", "one half", "one quarter",
            "one hundred hundred", "one thousand thousand", "one zero", "one-hundred",
            "one hundred and", "first", "1st").forEach { unsupported ->
            assertFalse(unsupported, reviewResultIsConservative("1개입니다.", "1 item.", "$unsupported item."))
        }
        assertFalse(reviewResultIsConservative("4년째입니다.", "Year 4.", "The fourth year."))
        assertFalse(reviewResultIsConservative("4년째입니다.", "Year 4.", "The 4th year."))
        assertFalse(reviewResultIsConservative("100개입니다.", "100 items.", "The hundredth item."))
    }

    @Test fun wholeWordBoundariesAndTargetLanguageControlEnglishParsing() {
        assertFalse(reviewResultIsConservative("1개입니다.", "1 item.", "Someone came."))
        assertFalse(reviewResultIsConservative("1개입니다.", "1 item.", "The stone fell."))
        assertFalse(reviewResultIsConservative("1개입니다.", "1 item.", "One's belongings."))
        assertFalse(reviewResultIsConservative("1개입니다.", "1 item.", "A one-off event."))
        assertTrue(reviewResultIsConservative("73개입니다.", "73 items.", "Seventy-three items.", "ko", "en-GB"))
        assertFalse(reviewResultIsConservative("73개입니다.", "73 items.", "Seventy-three items.", "ko", "ja"))
        // English source word parsing is outside this repair, rather than guessed as Korean.
        assertFalse(reviewResultIsConservative("Seventy-three items.", "73個です。", "73個です。", "en", "ja"))
    }

    @Test fun ordinaryOrdinalWordsOutsideNumericPhrasesAndSecondUnitsRemainUsable() {
        assertTrue(reviewResultIsConservative("1초 기다리세요.", "Wait 1 second.", "Wait 1 second."))
        assertTrue(reviewResultIsConservative("1초 기다리세요.", "Wait 1 second.", "Wait one second."))
        assertTrue(reviewResultIsConservative("처음에는 73명이 있었습니다.", "At first, 73 people were there.",
            "At first, seventy-three people were there."))
        assertTrue(reviewResultIsConservative("2분기에는 73명이 있었습니다.", "In quarter 2 there were 73 people.",
            "In quarter 2 there were seventy-three people."))
        assertTrue(reviewResultIsConservative("73명이 2층에 있었습니다.", "73 people were on floor 2.",
            "Seventy-three people were on floor 2."))
    }

    @Test fun signWordsUsedAsOrdinaryAdjectivesDoNotInvalidateUnchangedNumbers() {
        assertTrue(reviewResultIsConservative("73년 후 부정적인 결과였습니다.", "Negative results after 73 years.",
            "Negative results after seventy-three years."))
        assertTrue(reviewResultIsConservative("9년 후 긍정적인 결과였습니다.", "A positive outcome after 9 years.",
            "A positive outcome after nine years."))
        assertTrue(reviewResultIsConservative("73년 후 부정적인 결과였습니다.", "Negative results after 73 years.",
            "Negative results after 73 years."))
        assertFalse(reviewResultIsConservative("5도입니다.", "5 degrees.", "Negative positive five degrees."))
    }

    @Test fun nativeKoreanCountsAndSourceWithoutParsedNumbersKeepThePreviousGuard() {
        assertTrue(reviewResultIsConservative("네 분이 서 계십니다.", "Four people are standing.",
            "Four people are standing."))
        assertTrue(reviewResultIsConservative("한 분이 서 계십니다.", "One person is standing.",
            "One person is standing."))
        assertTrue(reviewResultIsConservative("안내를 시작합니다.", "We begin.", "We begin."))
        assertFalse(reviewResultIsConservative("안내를 시작합니다.", "There are 3 notices.",
            "There are three notices."))
        assertFalse(reviewResultIsConservative("안내를 시작합니다.", "We begin.", "There are 3 notices."))
    }

    @Test fun acceptedReviewReturnsItsNaturalWordsAndPreservesTheRawSource() = runBlocking {
        val original = "칠십 삼 년이 흘렀습니다."
        val draft = "Only 7 years passed."
        val reviewed = "Seventy-three years have passed."
        val diagnostics = mutableListOf<SelectiveRefinementDiagnostic>()
        val provider = SelectiveRefinementTranslationEngineProvider(
            TranslationEngineProvider { TextTranslationEngine { _, _, _ -> draft } },
            TranslationEngineProvider { TextTranslationEngine { text, _, _ ->
                assertEquals(original, text)
                val context = requireNotNull(currentCoroutineContext()[TranslationReviewContext])
                assertEquals(original, context.originalText)
                assertEquals(draft, context.draftTranslation)
                reviewed
            } },
            onDiagnostic = diagnostics::add,
        )
        assertEquals(reviewed, provider.engineFor("en").translate(original, "ko", "en"))
        assertEquals(SelectiveRefinementOutcome.REVIEW_ACCEPTED, diagnostics.single().outcome)
    }
}

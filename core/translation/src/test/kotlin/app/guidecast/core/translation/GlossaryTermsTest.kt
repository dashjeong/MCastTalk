package app.guidecast.core.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlossaryTermsTest {
    @Test fun correctionExpansionIsRejectedBeforeAllocatingHugeOutput() {
        val term = GlossaryTerm("ko", "ja", "용어", "語".repeat(512), "字")
        org.junit.Assert.assertThrows(GlossaryExpansionException::class.java) {
            GlossaryTerms.correct("字".repeat(100), listOf(term))
        }
    }

    @Test fun simplifiedBaseTagMatchesAppChineseWithoutTraditionalLeak() {
        val term = GlossaryTerm("ko", "zh", "도라산역", "都罗山站")
        assertEquals(listOf(term), GlossaryTerms.select("도라산역입니다.", "ko-KR", "zh-CN", listOf(term)))
        assertTrue(GlossaryTerms.select("도라산역입니다.", "ko", "zh-Hant", listOf(term)).isEmpty())
    }

    @Test
    fun select_matchesNormalizedLanguagePairAndRejectsMismatchedTarget() {
        val candidates = listOf(
            GlossaryTerm(
                sourceLanguage = "ko",
                targetLanguage = "en",
                sourceTerm = "도라산역",
                preferredTerm = "Dorasan Station",
            ),
            GlossaryTerm(
                sourceLanguage = "ko",
                targetLanguage = "ja",
                sourceTerm = "도라산역",
                preferredTerm = "ドラサン駅",
            ),
            GlossaryTerm(
                sourceLanguage = "ko",
                targetLanguage = "zh-CN",
                sourceTerm = "도라산역",
                preferredTerm = "都罗山站",
            ),
        )

        // Matching normalized ko-KR -> en-US should select the en candidate
        val selectedEn = GlossaryTerms.select(
            text = "도라산역에 도착했습니다.",
            sourceLanguage = "ko-KR",
            targetLanguage = "en-US",
            candidates = candidates,
        )
        assertEquals(1, selectedEn.size)
        assertEquals("Dorasan Station", selectedEn.first().preferredTerm)

        // ko -> nl query must strictly reject EN/JA/ZH candidates (NL에 EN 누출 금지)
        val selectedNl = GlossaryTerms.select(
            text = "도라산역에 도착했습니다.",
            sourceLanguage = "ko",
            targetLanguage = "nl",
            candidates = candidates,
        )
        assertTrue("Must not leak English or other target terms into Dutch", selectedNl.isEmpty())
    }

    @Test
    fun select_distinguishesSimplifiedAndTraditionalChinese() {
        val candidates = listOf(
            GlossaryTerm(
                sourceLanguage = "ko",
                targetLanguage = "zh-CN",
                sourceTerm = "도라산역",
                preferredTerm = "都罗山站",
            ),
            GlossaryTerm(
                sourceLanguage = "ko",
                targetLanguage = "zh-Hant",
                sourceTerm = "도라산역",
                preferredTerm = "都羅山站",
            ),
        )

        // zh-CN / zh-Hans query should match Simplified Chinese candidate
        val selectedCn = GlossaryTerms.select(
            text = "도라산역에 도착했습니다.",
            sourceLanguage = "ko",
            targetLanguage = "zh-CN",
            candidates = candidates,
        )
        assertEquals(1, selectedCn.size)
        assertEquals("都罗山站", selectedCn.first().preferredTerm)

        // zh-Hant / zh-TW query should match Traditional Chinese candidate and not be mashed into zh-CN
        val selectedTw = GlossaryTerms.select(
            text = "도라산역에 도착했습니다.",
            sourceLanguage = "ko",
            targetLanguage = "zh-TW",
            candidates = candidates,
        )
        assertEquals(1, selectedTw.size)
        assertEquals("都羅山站", selectedTw.first().preferredTerm)
    }

    @Test
    fun select_filtersDisabledAndBlankTerms() {
        val candidates = listOf(
            GlossaryTerm(
                sourceLanguage = "ko",
                targetLanguage = "en",
                sourceTerm = "도라산역",
                preferredTerm = "Dorasan Station",
                enabled = false,
            ),
            GlossaryTerm(
                sourceLanguage = "ko",
                targetLanguage = "en",
                sourceTerm = "",
                preferredTerm = "Empty Source",
            ),
            GlossaryTerm(
                sourceLanguage = "ko",
                targetLanguage = "en",
                sourceTerm = "임진각",
                preferredTerm = "   ",
            ),
            GlossaryTerm(
                sourceLanguage = "ko",
                targetLanguage = "en",
                sourceTerm = "임진각",
                preferredTerm = "Imjingak",
                enabled = true,
            ),
        )

        val selected = GlossaryTerms.select(
            text = "도라산역과 임진각입니다.",
            sourceLanguage = "ko",
            targetLanguage = "en",
            candidates = candidates,
        )

        assertEquals(1, selected.size)
        assertEquals("Imjingak", selected.first().preferredTerm)
    }

    @Test
    fun select_prefersLongerTermAndExcludesOverlap() {
        val candidates = listOf(
            GlossaryTerm(
                sourceLanguage = "ko",
                targetLanguage = "en",
                sourceTerm = "도라산",
                preferredTerm = "Dorasan",
            ),
            GlossaryTerm(
                sourceLanguage = "ko",
                targetLanguage = "en",
                sourceTerm = "도라산역",
                preferredTerm = "Dorasan Station",
            ),
        )

        val selected = GlossaryTerms.select(
            text = "이번 정차역은 도라산역입니다.",
            sourceLanguage = "ko",
            targetLanguage = "en",
            candidates = candidates,
        )

        assertEquals(1, selected.size)
        assertEquals("Dorasan Station", selected.first().preferredTerm)
    }

    @Test
    fun select_koreanParticleAttachmentAndBoundary() {
        val candidateDorasan = GlossaryTerm(
            sourceLanguage = "ko",
            targetLanguage = "en",
            sourceTerm = "도라산",
            preferredTerm = "Dorasan",
        )
        val candidateDorasanStation = GlossaryTerm(
            sourceLanguage = "ko",
            targetLanguage = "en",
            sourceTerm = "도라산역",
            preferredTerm = "Dorasan Station",
        )

        // 1. Particle attachment on 도라산 ("도라산에")
        val matchParticle1 = GlossaryTerms.select(
            text = "도라산에 갑니다.",
            sourceLanguage = "ko",
            targetLanguage = "en",
            candidates = listOf(candidateDorasan),
        )
        assertEquals(1, matchParticle1.size)
        assertEquals("Dorasan", matchParticle1.first().preferredTerm)

        // 2. Multi-syllable particle on 도라산 ("도라산에서")
        val matchParticle2 = GlossaryTerms.select(
            text = "도라산에서 만나요.",
            sourceLanguage = "ko",
            targetLanguage = "en",
            candidates = listOf(candidateDorasan),
        )
        assertEquals(1, matchParticle2.size)
        assertEquals("Dorasan", matchParticle2.first().preferredTerm)

        // 3. Multi-syllable compound particle on 도라산역 ("도라산역에서는")
        val matchParticle3 = GlossaryTerms.select(
            text = "도라산역에서는 표를 사야 합니다.",
            sourceLanguage = "ko",
            targetLanguage = "en",
            candidates = listOf(candidateDorasanStation),
        )
        assertEquals(1, matchParticle3.size)
        assertEquals("Dorasan Station", matchParticle3.first().preferredTerm)

        // 4. Copula sentence ending on 도라산역 ("도라산역입니다.")
        val matchCopula = GlossaryTerms.select(
            text = "여기는 도라산역입니다.",
            sourceLanguage = "ko",
            targetLanguage = "en",
            candidates = listOf(candidateDorasanStation),
        )
        assertEquals(1, matchCopula.size)
        assertEquals("Dorasan Station", matchCopula.first().preferredTerm)

        // 5. "도라산역" in text must NOT match candidate "도라산" because '역' is NOT a particle!
        val falsePositiveCheck = GlossaryTerms.select(
            text = "여기는 도라산역",
            sourceLanguage = "ko",
            targetLanguage = "en",
            candidates = listOf(candidateDorasan),
        )
        assertTrue(
            "Candidate '도라산' must NOT match '도라산역' substring because '역' is not a particle",
            falsePositiveCheck.isEmpty(),
        )

        // 6. Leading letter prevents match ("원도라산" does not match "도라산")
        val leadingSubCheck = GlossaryTerms.select(
            text = "원도라산에 갑니다.",
            sourceLanguage = "ko",
            targetLanguage = "en",
            candidates = listOf(candidateDorasan),
        )
        assertTrue("Leading word character must prevent substring match", leadingSubCheck.isEmpty())
    }

    @Test
    fun select_latinWordBoundary() {
        val candidate = GlossaryTerm(
            sourceLanguage = "en",
            targetLanguage = "ko",
            sourceTerm = "Dorasan",
            preferredTerm = "도라산",
        )

        // Exact match with space boundary
        val exact = GlossaryTerms.select(
            text = "Welcome to Dorasan Station",
            sourceLanguage = "en",
            targetLanguage = "ko",
            candidates = listOf(candidate),
        )
        assertEquals(1, exact.size)

        // Preceding word char rejected
        val leading = GlossaryTerms.select(
            text = "Welcome to superDorasan Station",
            sourceLanguage = "en",
            targetLanguage = "ko",
            candidates = listOf(candidate),
        )
        assertTrue(leading.isEmpty())

        // Trailing word char rejected
        val trailing = GlossaryTerms.select(
            text = "Welcome to DorasanX Station",
            sourceLanguage = "en",
            targetLanguage = "ko",
            candidates = listOf(candidate),
        )
        assertTrue(trailing.isEmpty())
    }

    @Test
    fun select_enforcesBudgetLimits() {
        val candidates = (1..10).map { i ->
            GlossaryTerm(
                sourceLanguage = "ko",
                targetLanguage = "en",
                sourceTerm = "단어$i",
                preferredTerm = "Word$i",
            )
        }

        val text = (1..10).joinToString(" ") { "단어$it" }
        val selected = GlossaryTerms.select(
            text = text,
            sourceLanguage = "ko",
            targetLanguage = "en",
            candidates = candidates,
        )

        // Max 6 terms limit
        assertEquals(6, selected.size)

        // Cumulative preferredTerm length limit (600 chars)
        val heavyCandidates = listOf(
            GlossaryTerm(
                sourceLanguage = "ko",
                targetLanguage = "en",
                sourceTerm = "용어일",
                preferredTerm = "A".repeat(400),
            ),
            GlossaryTerm(
                sourceLanguage = "ko",
                targetLanguage = "en",
                sourceTerm = "용어이",
                preferredTerm = "B".repeat(250), // 400 + 250 = 650 > 600
            ),
            GlossaryTerm(
                sourceLanguage = "ko",
                targetLanguage = "en",
                sourceTerm = "용어삼",
                preferredTerm = "C".repeat(150), // 400 + 150 = 550 <= 600
            ),
        )

        val selectedHeavy = GlossaryTerms.select(
            text = "용어일 용어이 용어삼",
            sourceLanguage = "ko",
            targetLanguage = "en",
            candidates = heavyCandidates,
        )
        assertEquals(2, selectedHeavy.size)
        assertEquals("용어일", selectedHeavy[0].sourceTerm)
        assertEquals("용어삼", selectedHeavy[1].sourceTerm)
    }

    @Test
    fun correct_singlePassNonChainedReplacement() {
        // Chain scenario: A -> B, and B -> C
        val term1 = GlossaryTerm(
            sourceLanguage = "ko",
            targetLanguage = "en",
            sourceTerm = "첫번째",
            preferredTerm = "B",
            replacement = "A",
        )
        val term2 = GlossaryTerm(
            sourceLanguage = "ko",
            targetLanguage = "en",
            sourceTerm = "두번째",
            preferredTerm = "C",
            replacement = "B",
        )

        // Single pass must replace A with B, and NOT chain B into C
        val result = GlossaryTerms.correct(
            text = "This is A test.",
            terms = listOf(term1, term2),
        )
        assertEquals("This is B test.", result)
    }

    @Test
    fun correct_ignoresBlankReplacementAndDoesNotBlindlyReplaceSourceTerm() {
        val term = GlossaryTerm(
            sourceLanguage = "ko",
            targetLanguage = "en",
            sourceTerm = "도라산역",
            preferredTerm = "Dorasan Station",
            replacement = "", // blank replacement
        )

        // Should not replace anything even if sourceTerm is mentioned
        val input = "Translation mentioning 도라산역 as text"
        val result = GlossaryTerms.correct(
            text = input,
            terms = listOf(term),
        )
        assertEquals(input, result)
    }

    @Test
    fun correct_wordBoundaryEnforcementWithUnderscore() {
        val term = GlossaryTerm(
            sourceLanguage = "ko",
            targetLanguage = "en",
            sourceTerm = "역",
            preferredTerm = "Stn",
            replacement = "Station",
        )

        val input = "Central Station and Substation and super_station and Station_id"
        val result = GlossaryTerms.correct(
            text = input,
            terms = listOf(term),
        )
        // Only standalone "Station" should be replaced. "Substation", "super_station", and "Station_id" are protected by word boundaries.
        assertEquals("Central Stn and Substation and super_station and Station_id", result)
    }

    @Test
    fun correct_cjkWithoutSpaces() {
        // Japanese: 日本語の -> 韓国語の (no spaces between Japanese characters and particle)
        val termJa = GlossaryTerm(
            sourceLanguage = "ko",
            targetLanguage = "ja",
            sourceTerm = "한국어",
            preferredTerm = "韓国語",
            replacement = "日本語",
        )
        val jaInput = "日本語のガイドをお聞きください。"
        val jaOutput = GlossaryTerms.correct(jaInput, listOf(termJa))
        assertEquals("韓国語のガイドをお聞きください。", jaOutput)

        // Chinese: 中文串 -> 中文字符串 (no spaces between Chinese characters)
        val termZh = GlossaryTerm(
            sourceLanguage = "ko",
            targetLanguage = "zh-CN",
            sourceTerm = "문자열",
            preferredTerm = "中文字符串",
            replacement = "中文串",
        )
        val zhInput = "这是中文串测试。"
        val zhOutput = GlossaryTerms.correct(zhInput, listOf(termZh))
        assertEquals("这是中文字符串测试。", zhOutput)
    }

    @Test
    fun correct_handlesSpecialCharsAndNonRegexLiteralReplacement() {
        val term = GlossaryTerm(
            sourceLanguage = "ko",
            targetLanguage = "en",
            sourceTerm = "디엠지",
            preferredTerm = "DMZ",
            replacement = "(DMZ.*+?)",
        )

        val input = "Entering (DMZ.*+?) area now."
        val result = GlossaryTerms.correct(
            text = input,
            terms = listOf(term),
        )
        assertEquals("Entering DMZ area now.", result)
    }

    @Test
    fun hints_returnsEmptyStringWhenEmpty() {
        assertEquals("", GlossaryTerms.hints(emptyList()))

        val disabledTerms = listOf(
            GlossaryTerm(
                sourceLanguage = "ko",
                targetLanguage = "en",
                sourceTerm = "도라산",
                preferredTerm = "Dorasan",
                enabled = false,
            )
        )
        assertEquals("", GlossaryTerms.hints(disabledTerms))
    }

    @Test
    fun hints_generatesValidJsonDataMappingWithEscaping() {
        val terms = listOf(
            GlossaryTerm(
                sourceLanguage = "ko",
                targetLanguage = "en",
                sourceTerm = "도라산역",
                preferredTerm = "Dorasan Station",
            ),
            GlossaryTerm(
                sourceLanguage = "ko",
                targetLanguage = "en",
                sourceTerm = "도라산역", // duplicate sourceTerm
                preferredTerm = "Duplicate Dorasan",
            ),
            GlossaryTerm(
                sourceLanguage = "ko",
                targetLanguage = "en",
                sourceTerm = "특수\"문자\\와\n줄바꿈\t탭",
                preferredTerm = "Special\"Chars\\and\nNewline\tTab",
            ),
        )

        val hints = GlossaryTerms.hints(terms)
        assertEquals(
            """{"도라산역":"Dorasan Station","특수\"문자\\와\n줄바꿈\t탭":"Special\"Chars\\and\nNewline\tTab"}""",
            hints,
        )
    }

    @Test
    fun hints_enforcesMax6TermsAndMax900CharsBudget() {
        // 1. Direct call with 10 terms must be capped at 6 terms
        val tenTerms = (1..10).map { i ->
            GlossaryTerm(
                sourceLanguage = "ko",
                targetLanguage = "en",
                sourceTerm = "단어$i",
                preferredTerm = "Word$i",
            )
        }
        val hints10 = GlossaryTerms.hints(tenTerms)
        assertTrue(hints10.startsWith("{"))
        assertTrue(hints10.endsWith("}"))
        // Check that term 7..10 are not in the JSON
        assertTrue(!hints10.contains("단어7"))
        assertTrue(hints10.contains("단어6"))

        // 2. Budget limit: total JSON <= 900 characters even if terms are long
        val longTerms = (1..6).map { i ->
            GlossaryTerm(
                sourceLanguage = "ko",
                targetLanguage = "en",
                sourceTerm = "긴원어$i",
                preferredTerm = "LongPreferredTerm_${"X".repeat(200)}_$i",
            )
        }
        val hintsLong = GlossaryTerms.hints(longTerms)
        assertTrue("Hints JSON must not exceed 900 characters", hintsLong.length <= 900)
        assertTrue("Hints JSON must be well-formed JSON object", hintsLong.startsWith("{") && hintsLong.endsWith("}"))
    }
}

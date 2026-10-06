package app.guidecast.transmitter

import org.junit.Assert.*
import org.junit.Test

class RelayCaptionPresentationTest {
    private fun native(sequence: Long, target: String, millis: Long, source: String = "안녕하세요.",
        run: Long? = 7, sourceLanguage: String? = "ko-KR", failed: Boolean = false, expired: Boolean = false) =
        TranslationTranscriptLine(sequence, source, millis * 1_000_000, isFinal = true,
            translations = mapOf(target to "result-$target"), sourceLanguageTag = sourceLanguage,
            liveSegmentLanguage = target, nativeAudioSessionId = run, liveOutputState = LiveOutputState.GENERATED,
            liveSourceFailed = failed, liveSourceExpired = expired)

    @Test fun nearbyFiveIndependentCaptionsShareOneSourceButDoNotAssertUtteranceIdentity() {
        val languages = listOf("en", "ja", "zh", "ru", "vi")
        val lines = languages.mapIndexed { index, tag -> native(index.toLong(), tag, 1_000 + index * 100L) }
        val group = relayCaptionPresentation(lines).single()
        assertEquals("안녕하세요.", group.sourceText)
        assertEquals(languages.toSet(), group.translations.keys)
        assertEquals(RelayCaptionAlignment.NEARBY_NATIVE_UNCONFIRMED, group.alignment)
        assertTrue(group.alignmentNotice!!.contains("발화 정렬 미확인"))
        assertEquals(5, group.segments.size)
        assertEquals("ja", group.segmentFor("ja")!!.liveSegmentLanguage)
        assertEquals(lines, group.segments)
    }

    @Test fun repeatedSamePhraseInOneLanguageIsNeverAssignedToAnotherLanguageArbitrarily() {
        val groups = relayCaptionPresentation(listOf(native(1, "en", 1_000), native(2, "en", 1_100), native(3, "ja", 1_200)))
        assertEquals(3, groups.size)
        assertTrue(groups.all { it.segments.size == 1 })
    }

    @Test fun competingRepetitionJustOutsideAnchoredWindowAlsoPreventsFalseGrouping() {
        val groups = relayCaptionPresentation(listOf(native(1, "en", 0), native(2, "ja", 1_900), native(3, "ja", 2_100)))
        assertEquals(3, groups.size)
    }

    @Test fun repeatedUtteranceWellApartRemainsTwoSeparateDisplayGroups() {
        val groups = relayCaptionPresentation(listOf(native(1, "en", 0), native(2, "ja", 100),
            native(3, "en", 10_000), native(4, "ja", 10_100)))
        assertEquals(2, groups.size)
        assertTrue(groups.all { it.segments.size == 2 })
        assertNotEquals(groups[0].id, groups[1].id)
    }

    @Test fun changedRunLanguageTextOrMissingEvidenceCannotMerge() {
        val examples = listOf(
            native(2, "ja", 100, run = 8), native(2, "ja", 100, sourceLanguage = "en-US"),
            native(2, "ja", 100, source = "안녕하세요?"), native(2, "ja", 100, source = ""),
            native(2, "ja", 100, run = null), native(2, "ja", 100, sourceLanguage = null),
            native(2, "ja", 100, failed = true), native(2, "ja", 100, expired = true), native(2, "ja", 2_001),
        )
        examples.forEach { other -> assertEquals(other.toString(), 2, relayCaptionPresentation(listOf(native(1, "en", 0), other)).size) }
    }

    @Test fun interveningDifferentSourceKeepsConsecutiveMatchingRuleConservative() {
        assertEquals(3, relayCaptionPresentation(listOf(native(1, "en", 0),
            native(2, "ja", 100, source = "다른 발화"), native(3, "zh", 200))).size)
    }

    @Test fun pendingTranslationsRetainTheirOwnSegmentStatusWhenGrouped() {
        val generating = native(2, "ja", 100).copy(isFinal = false, liveOutputState = LiveOutputState.GENERATING)
        val group = relayCaptionPresentation(listOf(native(1, "en", 0), generating)).single()
        assertEquals(LiveOutputState.GENERATED, group.segmentFor("en")!!.liveOutputState)
        assertEquals(LiveOutputState.GENERATING, group.segmentFor("ja")!!.liveOutputState)
    }

    @Test fun sharedTextTranslationRetainsItsExistingOneUtteranceIdentityAndChronologicalOrder() {
        val text = TranslationTranscriptLine(999, "원문", 0, translations = mapOf("en" to "English", "ja" to "Japanese"))
        val later = native(1, "en", 500)
        val groups = relayCaptionPresentation(listOf(text, later))
        assertEquals(RelayCaptionAlignment.INDEPENDENT_NATIVE, groups.first().alignment)
        assertEquals(RelayCaptionAlignment.SHARED_UTTERANCE, groups.last().alignment)
        assertNull(groups.last().alignmentNotice)
    }

    @Test fun viewFiltersReturnActualLanguagesWithoutChangingCaptionData() {
        val lines = listOf(native(1, "en", 0), native(2, "ja", 100))
        val before = lines.toList()
        assertEquals(listOf("en", "ja"), relayCaptionDisplayLanguages(listOf("en", "ja"), null))
        assertEquals(listOf("ja"), relayCaptionDisplayLanguages(listOf("en", "ja"), "ja"))
        assertEquals(listOf("en", "ja"), relayCaptionDisplayLanguages(listOf("en", "ja"), "fr"))
        relayCaptionPresentation(lines)
        assertEquals(before, lines)
        assertEquals("영어", relayCaptionLanguageLabel("en"))
        assertEquals("일본어", relayCaptionLanguageLabel("ja"))
    }
}

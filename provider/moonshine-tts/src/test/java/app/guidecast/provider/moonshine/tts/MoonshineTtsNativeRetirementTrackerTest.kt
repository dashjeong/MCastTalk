package app.guidecast.provider.moonshine.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonshineTtsNativeRetirementTrackerTest {
    @Test
    fun `timeout then late acknowledgement permits the full replacement set`() {
        val tracker = MoonshineTtsNativeRetirementTracker()
        val timedOutEnglish = tracker.observe(MoonshineTtsWorkerGeneration("en", 1L))
        timedOutEnglish.markUnconfirmed()

        val whileEnglishMayStillOwnNative = planMoonshineTtsNativeSelection(
            requestedLanguageTags = linkedSetOf("ja", "zh", "nl", "es", "ar"),
            retainedLanguageTags = emptySet(),
            unresolvedGenerations = tracker.snapshot(),
            maximumResidentGenerations = 5,
        )
        assertEquals(4, whileEnglishMayStillOwnNative.admittedLanguageTags.size)
        assertEquals(1, whileEnglishMayStillOwnNative.blockedLanguageTags.size)

        // The shutdown callback/Binder death may arrive after the caller's two-second timeout.
        timedOutEnglish.markConfirmed()

        val afterLateNativeClose = planMoonshineTtsNativeSelection(
            requestedLanguageTags = linkedSetOf("ja", "zh", "nl", "es", "ar"),
            retainedLanguageTags = emptySet(),
            unresolvedGenerations = tracker.snapshot(),
            maximumResidentGenerations = 5,
        )
        assertEquals(linkedSetOf("ja", "zh", "nl", "es", "ar"), afterLateNativeClose.admittedLanguageTags)
        assertTrue(afterLateNativeClose.blockedLanguageTags.isEmpty())
    }

    @Test
    fun `missing acknowledgement keeps only that language successor on fallback`() {
        val tracker = MoonshineTtsNativeRetirementTracker()
        tracker.observe(MoonshineTtsWorkerGeneration("en", 4L)).markUnconfirmed()

        repeat(2) {
            val selection = planMoonshineTtsNativeSelection(
                requestedLanguageTags = linkedSetOf("en", "ja"),
                retainedLanguageTags = emptySet(),
                unresolvedGenerations = tracker.snapshot(),
                maximumResidentGenerations = 5,
            )
            assertEquals(setOf("ja"), selection.admittedLanguageTags)
            assertEquals(setOf("en"), selection.blockedLanguageTags)
        }
    }

    @Test
    fun `one unresolved language leaves retained siblings warm and isolated`() {
        val tracker = MoonshineTtsNativeRetirementTracker()
        tracker.observe(MoonshineTtsWorkerGeneration("en", 9L)).markUnconfirmed()

        val selection = planMoonshineTtsNativeSelection(
            requestedLanguageTags = linkedSetOf("en", "ja", "zh", "nl", "es"),
            retainedLanguageTags = linkedSetOf("zh", "nl"),
            unresolvedGenerations = tracker.snapshot(),
            maximumResidentGenerations = 5,
        )

        assertEquals(setOf("ja", "zh", "nl", "es"), selection.admittedLanguageTags)
        assertEquals(setOf("en"), selection.blockedLanguageTags)
    }
}

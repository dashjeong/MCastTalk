package app.guidecast.provider.mlkit.translation

import app.guidecast.core.translation.LanguageModelStatus
import app.guidecast.core.translation.ModelReadiness
import org.junit.Assert.*
import org.junit.Test

class InterruptedModelPreparationStatusesTest {
    @Test fun stoppedBatchClearsOnlyItsTransientStatusesAndPreservesHealthyLanguages() {
        val before = listOf(
            LanguageModelStatus("en", ModelReadiness.READY),
            LanguageModelStatus("ja", ModelReadiness.DOWNLOADING),
            LanguageModelStatus("zh", ModelReadiness.VERIFYING),
            LanguageModelStatus("vi", ModelReadiness.DOWNLOADING),
        )
        val after = interruptedModelPreparationStatuses(before, setOf("en", "ja", "zh"))
        assertEquals(before[0], after[0])
        assertEquals(ModelReadiness.FAILED, after[1].readiness)
        assertEquals(ModelReadiness.FAILED, after[2].readiness)
        assertEquals(before[3], after[3])
        assertTrue(after[1].errorMessage!!.contains("다시 준비"))
    }

    @Test fun newOwnerClearsOldDeselectedWaitersWithoutChangingMissingOrFailedStates() {
        val before = listOf(
            LanguageModelStatus("zh-TW", ModelReadiness.DOWNLOADING),
            LanguageModelStatus("en", ModelReadiness.NOT_INSTALLED),
            LanguageModelStatus("vi", ModelReadiness.FAILED, errorMessage = "missing voice"),
        )
        val after = interruptedModelPreparationStatuses(before, before.map { it.languageTag }.toSet())
        assertEquals(ModelReadiness.FAILED, after[0].readiness)
        assertEquals(before.drop(1), after.drop(1))
    }
}

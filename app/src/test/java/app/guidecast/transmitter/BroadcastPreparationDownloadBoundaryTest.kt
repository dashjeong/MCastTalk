package app.guidecast.transmitter

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BroadcastPreparationDownloadBoundaryTest {
    @Test
    fun `speech test and broadcast cannot start model download or refresh tasks`() {
        // These non-cancellable Tasks belong to settings. Guard the production entry point:
        // the previous awaitChannelPreparation branch blocked Gemma on a missing ko download.
        val source = listOf(
            File("src/main/java/app/guidecast/transmitter/BroadcastService.kt"),
            File("app/src/main/java/app/guidecast/transmitter/BroadcastService.kt"),
        ).first { it.isFile }.readText()
        assertTrue(source.contains("private suspend fun prepareTranslationPipeline("))
        val pipeline = source.substringAfter("private suspend fun prepareTranslationPipeline(")
            .substringBefore("\n    private ")
        listOf(
            "prepareTranslationModelsWithProcessAdmission(",
            "refreshTranslationModels(",
            ".prepareModels(",
            ".downloadModelFile(",
        ).forEach { prohibitedCall ->
            assertFalse("Speech preparation starts $prohibitedCall", pipeline.contains(prohibitedCall))
        }
    }

    @Test
    fun `eligible Gemma becomes active with no prepared ML Kit fallback`() = runBlocking {
        var warmed = false
        val result = prepareGemmaBroadcastWarmup(
            eligible = true,
            fallbackReady = false,
            priorityLanguageTag = "en",
            warmup = { warmed = true },
            cleanupAfterFailure = { fail("Successful Gemma must remain prepared") },
        )

        assertTrue(warmed)
        assertTrue(result.active)
        assertNull(result.warning)
    }
}

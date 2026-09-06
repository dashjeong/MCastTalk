package app.guidecast.transmitter

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import app.guidecast.core.translation.LanguageModelStatus
import app.guidecast.core.translation.ModelReadiness

class IndependentPreparationStageTest {
    @Test fun stuckTranslationDoesNotHoldVoiceOrRecognitionAndUiUnlocks() = runBlocking {
        val events = mutableListOf<String>()
        var busy = false
        runBoundedModelOperation(2_000, { busy = it }, { fail(it) }) {
            supervisorScope {
                val translation = async {
                    runIndependentPreparationStage("translation", 150, { stage, state ->
                        events += "$stage:$state"
                    }) { awaitCancellation() }
                }
                val voice = async {
                    runIndependentPreparationStage("voice", 1_000, { _, _ -> }) { "ready" }
                }
                val recognition = async {
                    runIndependentPreparationStage("recognition", 1_000, { _, _ -> }) { "ready" }
                }
                assertEquals("ready", voice.await().getOrThrow())
                assertEquals("ready", recognition.await().getOrThrow())
                assertFalse("translation should still be waiting", translation.isCompleted)
                assertTrue(translation.await().isFailure)
            }
        }
        assertFalse(busy)
        assertTrue(events.last().contains("시간 초과"))
    }

    @Test fun operatorStopCancelsEveryStageAndAllowsRetry() = runBlocking {
        val started = List(3) { CompletableDeferred<Unit>() }
        val stopped = mutableSetOf<Int>()
        var busy = false
        val job = launch {
            runBoundedModelOperation(5_000, { busy = it }, { fail(it) }) {
                supervisorScope {
                    started.mapIndexed { index, signal -> async {
                        runIndependentPreparationStage("stage-$index", 5_000, { _, _ -> }) {
                            signal.complete(Unit)
                            try { awaitCancellation() } finally { stopped += index }
                        }
                    } }.awaitAll()
                }
            }
        }
        started.forEach { it.await() }
        job.cancelAndJoin()
        assertFalse(busy)
        assertTrue(job.isCancelled)
        assertEquals(setOf(0, 1, 2), stopped)
        assertEquals("ready", runIndependentPreparationStage("voice", 1_000, { _, _ -> }) {
            "ready"
        }.getOrThrow())
    }

    @Test fun stageTimeoutPreservesReadySiblingLanguage() {
        val report = translationPreparationReportFromStatuses(
            setOf("en", "ja"),
            listOf(LanguageModelStatus("en", ModelReadiness.READY),
                LanguageModelStatus("ja", ModelReadiness.DOWNLOADING)),
            "download timeout",
        )
        assertEquals(setOf("en"), report.readyLanguageTags)
        assertEquals(mapOf("ja" to "download timeout"), report.failures)
    }

    @Test fun healthyVoiceOutcomeIsPublishedBeforeStalledSiblingFinishes() = runBlocking {
        val ready = CompletableDeferred<SpeechPreparationOutcome>()
        val batch = async {
            prepareSpeechSynthesisOutcomesIndependently(
                languageTags = listOf("en", "vi"), timeoutMillis = 200,
                onOutcome = { if (it.languageTag == "en") ready.complete(it) },
            ) { language ->
                if (language == "vi") awaitCancellation()
                SpeechSynthesisLanguagePreparation(language, SpeechSynthesisPreparationBackend.MOONSHINE)
            }
        }
        assertEquals("en", withTimeout(150) { ready.await() }.languageTag)
        assertFalse(batch.isCompleted)
        val outcomes = batch.await()
        assertNotNull(outcomes.first { it.languageTag == "vi" }.error)
        assertNotNull(outcomes.first { it.languageTag == "en" }.result)
    }
}

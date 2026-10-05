package app.guidecast.transmitter

import app.guidecast.core.translation.TranslationStyle
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NativeInputConsentBoundaryTest {
    @Test fun lateProviderItemCannotRetroactivelyIncludeAudioFromAnOffSession() = runTest {
        var enabled = false; var generation = 0L; var localCalls = 0
        val monitor = NativeLearningMonitor()
        val session = NativeLearningSession(backgroundScope, 1, monitor, { enabled }, { true }, { true },
            { generation }, { 0 }, { null }) { pair ->
            localCalls++
            ShadowComparison(pair.original, null, "ko", "en", 0, pair.translation, "Hi", TranslationStyle.CONVERSATIONAL,
                NativeComparisonIdentity(1, pair.inputId, pair.responseId, pair.sequence, 1,
                    TranslationApiProvider.OPENAI_REALTIME, "gpt-realtime-2.1-mini", pair.controlGeneration))
        }
        runCurrent()
        // An OFF connection has no provider item until after the switch.
        enabled = true; generation++
        session.accept(OpenAiAudioEvent("input1", "안녕", "Hello", finished = true,
            responseId = "response1", status = "completed", inputSequence = 1, sourceFinal = true, translationFinal = true))
        runCurrent()
        assertEquals(0, localCalls); assertNull(monitor.state.value.last)
        session.close()
    }
    private class State {
        var enabled = true; var generation = 0L; var calls = 0
        val monitor = NativeLearningMonitor()
        fun session(scope: kotlinx.coroutines.CoroutineScope, id: Long, inputBoundaryKnown: Boolean = true) =
            NativeLearningSession(scope, id, monitor, { enabled }, { true }, { true }, { generation }, { 0 }, { null },
                captureStartsAfterAdmission = inputBoundaryKnown) { pair ->
                calls++
                ShadowComparison(pair.original, null, "ko", "en", 0, pair.translation, "Hi", TranslationStyle.CONVERSATIONAL,
                    NativeComparisonIdentity(id, pair.inputId, pair.responseId, pair.sequence, 1,
                        TranslationApiProvider.OPENAI_REALTIME, "gpt-realtime-2.1-mini", pair.controlGeneration))
            }
    }
    private fun event() = OpenAiAudioEvent("input1", "안녕", "Hello", finished = true,
        responseId = "response1", status = "completed", inputSequence = 1, sourceFinal = true, translationFinal = true)

    @Test fun onOffOnBeforeAnyProviderItemRequiresANewConnection() = runTest {
        val state = State(); val old = state.session(backgroundScope, 1); runCurrent()
        state.enabled = false; state.generation++; state.enabled = true; state.generation++
        old.accept(event()); runCurrent(); assertEquals(0, state.calls)
        assertEquals(NativeLearningPause.RESTART.label, state.monitor.state.value.lastPause)
        old.close(); val fresh = state.session(backgroundScope, 2); runCurrent(); fresh.accept(event()); runCurrent()
        assertEquals(1, state.calls); assertNotNull(state.monitor.state.value.last); fresh.close()
    }

    @Test fun unknownAlreadyRunningCaptureIsExcludedUntilANewInputBoundary() = runTest {
        val state = State(); val old = state.session(backgroundScope, 1, false); runCurrent()
        old.accept(event()); runCurrent(); assertEquals(0, state.calls)
        assertEquals(NativeLearningPause.INPUT_BOUNDARY.label, state.monitor.state.value.lastPause)
        old.close(); val fresh = state.session(backgroundScope, 2); runCurrent(); fresh.accept(event()); runCurrent()
        assertEquals(1, state.calls); fresh.close()
    }

    @Test fun initialOptInAndStableInputAdmissionStillAllowsOneConfirmedPair() = runTest {
        val state = State(); val session = state.session(backgroundScope, 1); runCurrent()
        assertEquals(0, state.calls); session.accept(event()); runCurrent()
        assertEquals(1, state.calls); assertEquals(1L, state.monitor.state.value.completed); session.close()
    }
}

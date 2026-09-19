package app.guidecast.transmitter

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticPreparationTest {
    @Test fun rapidSelectionsCoalesceAndActiveAudioDefersOnlyTheLatestRequest() = runTest {
        val flow = MutableStateFlow(AutomaticPreparationRequest("ko", setOf("en"), emptyMap(), true))
        val prepared = mutableListOf<AutomaticPreparationRequest>()
        var idle = false
        val worker = backgroundScope.launch { flow.prepareAutomatically({ idle }) { prepared += it } }
        runCurrent(); advanceTimeBy(400)
        flow.value = flow.value.copy(targets = setOf("en", "ja")); runCurrent()
        advanceTimeBy(2_000); runCurrent(); assertTrue(prepared.isEmpty())
        idle = true; advanceTimeBy(500); runCurrent()
        assertEquals(listOf(setOf("en", "ja")), prepared.map { it.targets })
        advanceTimeBy(60_000); runCurrent(); assertEquals(1, prepared.size)
        worker.cancel()
    }
    @Test fun stoppingAutomaticPreparationWhileWaitingNeverStartsADeferredDownload() = runTest {
        val flow = MutableStateFlow(AutomaticPreparationRequest("ko", setOf("en"), emptyMap(), true))
        var idle = false; var starts = 0
        val worker = backgroundScope.launch { flow.prepareAutomatically({ idle }) { starts++ } }
        runCurrent(); advanceTimeBy(1_000); runCurrent()
        flow.value = flow.value.copy(enabled = false); runCurrent(); idle = true
        advanceTimeBy(20_000); runCurrent(); assertEquals(0, starts)
        flow.value = flow.value.copy(enabled = true); runCurrent(); advanceTimeBy(750); runCurrent()
        assertEquals(1, starts); worker.cancel()
    }
}

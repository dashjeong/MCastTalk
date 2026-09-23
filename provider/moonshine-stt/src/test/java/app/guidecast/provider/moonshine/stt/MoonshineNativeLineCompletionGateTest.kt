package app.guidecast.provider.moonshine.stt

import org.junit.Assert.*
import org.junit.Test

class MoonshineNativeLineCompletionGateTest {
    @Test fun lateChangedTextAndRepeatedFinalCannotRecreateACompletedNativeLine() {
        val gate = MoonshineNativeLineCompletionGate()
        assertTrue(gate.shouldAccept(40, false))
        assertTrue(gate.shouldAccept(40, true))
        assertFalse(gate.shouldAccept(40, false))
        assertFalse(gate.shouldAccept(40, true))
    }

    @Test fun genuinelyNewNativeIdsAreAcceptedWithoutAssumingNumericOrderOrComparingText() {
        val gate = MoonshineNativeLineCompletionGate()
        val repeatedText = "안내를 따라 이동하세요"
        val delivered = listOf(90L, 7L).mapNotNull { id ->
            repeatedText.takeIf { gate.shouldAccept(id, true) }
        }
        assertEquals(listOf(repeatedText, repeatedText), delivered)
        assertFalse(gate.shouldAccept(90, false))
    }

    @Test fun fullGateRejectsANewFinalExplicitlyAndNeverEvictsAnAncientFinal() {
        val gate = MoonshineNativeLineCompletionGate(maximumCompletedLines = 2)
        assertTrue(gate.shouldAccept(100, true))
        assertTrue(gate.shouldAccept(1, true))
        assertThrows(IllegalStateException::class.java) { gate.shouldAccept(200, true) }
        assertFalse(gate.shouldAccept(100, false))
        assertFalse(gate.shouldAccept(100, true))
        assertFalse(gate.shouldAccept(1, true))
    }
}

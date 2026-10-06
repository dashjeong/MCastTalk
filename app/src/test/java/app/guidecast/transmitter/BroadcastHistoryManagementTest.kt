package app.guidecast.transmitter

import app.guidecast.core.stream.RecordedBroadcast
import org.junit.Assert.*
import org.junit.Test

class BroadcastHistoryManagementTest {
    private fun recording(id: String, state: String, ended: Long? = null, title: String = "") =
        RecordedBroadcast(id, 100, ended, state, 0, null, emptyList(), title)

    @Test fun selectionRequiresThreeFullSecondsAndCannotBeActivatedByAClockRollback() {
        val hold = RecordingSelectionHold(10_000)
        assertFalse(hold.canSelect(9_999))
        assertFalse(hold.canSelect(12_999))
        assertTrue(hold.canSelect(13_000))
    }

    @Test fun scrollConsumedReleaseAndSecondPointerEachCancelTheWholeGesture() {
        listOf(
            { h: RecordingSelectionHold -> h.update(released = true, consumed = false, movedOutsideSlop = false) },
            { h: RecordingSelectionHold -> h.update(released = false, consumed = true, movedOutsideSlop = false) },
            { h: RecordingSelectionHold -> h.update(released = false, consumed = false, movedOutsideSlop = true) },
            { h: RecordingSelectionHold -> h.update(released = false, consumed = false, movedOutsideSlop = false, multiplePointers = true) },
        ).forEach { cancel ->
            val hold = RecordingSelectionHold(0)
            cancel(hold)
            hold.update(released = false, consumed = false, movedOutsideSlop = false)
            assertFalse(hold.canSelect(4_000))
        }
    }

    @Test fun selectionRefreshPreservesOnlyExistingStoppedRecordsAndAlwaysProtectsActiveId() {
        val history = listOf(recording("running", "RECORDING"), recording("paused", "PAUSED"),
            recording("finished", "COMPLETED", 200), recording("interrupted", "INTERRUPTED"),
            recording("active-but-old-state", "COMPLETED", 200))
        assertEquals(listOf("finished", "interrupted"), availableRecordingSelections(
            listOf("running", "paused", "finished", "interrupted", "active-but-old-state", "deleted"), history, "active-but-old-state"))
        assertFalse(recordingCanBeDeleted(history[1], "paused"))
        assertFalse(recordingCanBeDeleted(history.last(), "active-but-old-state"))
    }

    @Test fun namedRecordingDisplaysTheUsersTitleAndLegacyRecordingHasAnAutomaticLabel() {
        assertEquals("입학 안내 방송", recordingDisplayTitle(recording("named", "COMPLETED", 200, "입학 안내 방송")))
        assertTrue(recordingDisplayTitle(recording("legacy", "INTERRUPTED")).startsWith("방송 · "))
    }
}

package app.guidecast.transmitter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppProcessMemorySnapshotTest {
    @Test
    fun aggregatesEveryGuideCastProcessWithoutTreatingItAsACapacityLimit() {
        val snapshot = AppProcessMemorySnapshot.fromKilobytes(
            totalPssKilobytes = listOf(64L * 1_024L, 512L * 1_024L, 96L * 1_024L),
            totalPrivateDirtyKilobytes = listOf(24L * 1_024L, 320L * 1_024L, 48L * 1_024L),
        )

        assertTrue(snapshot.valuesValid)
        assertEquals(3, snapshot.processCount)
        assertEquals(672L * 1_024L * 1_024L, snapshot.totalPssBytes)
        assertEquals(392L * 1_024L * 1_024L, snapshot.totalPrivateDirtyBytes)
    }

    @Test
    fun invalidOrMismatchedProcessSamplesStayUnavailable() {
        assertFalse(
            AppProcessMemorySnapshot.fromKilobytes(
                totalPssKilobytes = emptyList(),
                totalPrivateDirtyKilobytes = emptyList(),
            ).valuesValid,
        )
        assertFalse(
            AppProcessMemorySnapshot.fromKilobytes(
                totalPssKilobytes = listOf(1L, 2L),
                totalPrivateDirtyKilobytes = listOf(1L),
            ).valuesValid,
        )
    }

    @Test
    fun smallPssNoiseDoesNotContinuouslyInvalidateTheOperatorUi() {
        val baseline = AppProcessMemorySnapshot(
            processCount = 4,
            totalPssBytes = 1_000L * 1_024L * 1_024L,
            totalPrivateDirtyBytes = 700L * 1_024L * 1_024L,
            valuesValid = true,
        )
        val noise = baseline.copy(
            totalPssBytes = baseline.totalPssBytes + 8L * 1_024L * 1_024L,
        )
        val material = baseline.copy(
            totalPrivateDirtyBytes = baseline.totalPrivateDirtyBytes + 40L * 1_024L * 1_024L,
        )

        assertFalse(noise.materiallyDiffersFrom(baseline))
        assertTrue(material.materiallyDiffersFrom(baseline))
        assertTrue(baseline.copy(processCount = 5).materiallyDiffersFrom(baseline))
    }

    @Test
    fun viewModelMemoryStateStartsUnavailableUntilIoSamplerPublishesEvidence() {
        val initial = initialAppProcessMemorySnapshot()

        assertFalse(initial.valuesValid)
        assertEquals(0, initial.processCount)
    }
}

package app.guidecast.core.translation

import org.junit.Assert.*
import org.junit.Test

class RagPocPlanTest {
    @Test fun sixOrdersBalancePositionAndCarryover() {
        val orders = (0..5).map { RagPocPlan.order(it, 0) }
        assertEquals(6, orders.toSet().size)
        for (position in 0..2) for (arm in RagPocPlan.Arm.entries) {
            assertEquals(2, orders.count { it[position] == arm })
        }
        assertEquals(6, orders.flatMap { it.zipWithNext() }.toSet().size)
    }
    @Test(expected = IllegalArgumentException::class) fun mismatchedControlCannotMasqueradeAsLengthMatched() {
        RagPocPlan.validateReferences("relevant", "short")
    }
    @Test fun absentListenerAndQualityEvidenceCannotPassOnTranslationLatencyAlone() {
        val missing = RagPocPlan.missingMeasurements(mapOf("retrievalMs" to 3.0, "decodeMs" to 900.0, "prefillMs" to 10.0))
        assertTrue("listenerFirstAudioMs" in missing)
        assertTrue("omissions" in missing)
        assertTrue("thermalStatus" in missing)
    }
}

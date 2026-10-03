package app.guidecast.core.translation

import org.junit.Assert.*
import org.junit.Test

class OnlineUsageTest {
    @Test fun cachedTokensAreNotChargedTwiceAndAudioHasIndependentRates() {
        val usage = OnlineUsage(ModalityUsage(1000, 200, 500), ModalityUsage(1000, 200, 500))
        assertEquals(0, OnlinePrices.realtimeMini.estimate(usage)!!.compareTo("0.019752".toBigDecimal()))
        assertNull(OnlinePrices.realtimeMini.estimate(null))
    }
    @Test(expected = IllegalArgumentException::class) fun impossibleCachedUsageIsRejected() {
        ModalityUsage(10, 11, 0)
    }
    @Test fun listenersDoNotCreateDuplicateRequestsAndOldCallbacksNeverCompleteNewWork() {
        val ledger = RealtimeRequestLedger()
        val first = ledger.begin(1, "EN", 7)!!
        repeat(50) { assertNull(ledger.begin(1, "en", 7)) }
        ledger.cancelAll()
        val next = ledger.begin(1, "en", 8)!!
        assertFalse(ledger.accepts(first))
        assertFalse(ledger.finish(first))
        assertTrue(ledger.accepts(next))
        assertEquals(8L, next.corpusVersion)
        assertTrue(ledger.finish(next))
        assertFalse(ledger.finish(next))
        assertNull(ledger.begin(1, "en", 9))
    }
}

package app.guidecast.core.translation

import java.math.BigDecimal
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class OnlineRequestBudgetTest {
    private fun n(value: String) = BigDecimal(value)
    @Test fun simultaneousRequestsCannotOversubscribeLimit() {
        val budget = OnlineRequestBudget()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val admitted = pool.invokeAll(List(20) { java.util.concurrent.Callable { budget.reserve(n("0.1"), n("1")) } })
                .count { it.get() != null }
            assertEquals(10, admitted)
            assertEquals(0, budget.snapshot().committed.compareTo(n("1")))
        } finally { pool.shutdownNow() }
    }
    @Test fun unknownTimeoutUsageSurvivesRestartAndDuplicateSettlementDoesNothing() {
        var stored = OnlineRequestBudget.Snapshot(n("0"), n("0"))
        val budget = OnlineRequestBudget(persist = { stored = it; true })
        val reservation = budget.reserve(n("0.8"), n("1"))!!
        assertTrue(budget.settle(reservation, null))
        assertFalse(budget.settle(reservation, n("0")))
        val restarted = OnlineRequestBudget(stored.known, stored.held)
        assertNull(restarted.reserve(n("0.3"), n("1")))
    }
    @Test fun verifiedUsageSettlesExactlyOnceAndReleasesOnlyUnusedReservation() {
        val budget = OnlineRequestBudget()
        val reservation = budget.reserve(n("0.8"), n("1"))!!
        assertTrue(budget.settle(reservation, n("0.2")))
        assertFalse(budget.settle(reservation, n("0.2")))
        assertEquals(0, budget.snapshot().known.compareTo(n("0.2")))
        assertEquals(0, budget.snapshot().held.signum())
    }
    @Test fun persistenceFailurePreventsDispatchAndSettlementFailureRetainsFunds() {
        var writable = false
        val budget = OnlineRequestBudget(persist = { writable })
        assertNull(budget.reserve(n("0.1"), n("1")))
        writable = true
        val reservation = budget.reserve(n("0.5"), n("1"))!!
        writable = false
        assertFalse(budget.settle(reservation, n("0.1")))
        assertEquals(0, budget.snapshot().held.compareTo(n("0.5")))
    }
}

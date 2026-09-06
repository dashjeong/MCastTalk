package app.guidecast.transmitter

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderFinalGateTest {
    @Test
    fun `first final wins and a racing duplicate or late partial is rejected`() {
        val gate = ProviderFinalGate()

        assertTrue(gate.shouldAccept(providerSequence = 7, isFinal = false))
        assertTrue(gate.shouldAccept(providerSequence = 7, isFinal = true))
        assertFalse(gate.shouldAccept(providerSequence = 7, isFinal = true))
        assertFalse(gate.shouldAccept(providerSequence = 7, isFinal = false))
        assertTrue(gate.shouldAccept(providerSequence = 8, isFinal = true))
    }

    @Test
    fun `simultaneous finals for one provider sequence have exactly one winner`() {
        val gate = ProviderFinalGate()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        try {
            val contenders = List(2) {
                pool.submit<Boolean> {
                    start.await()
                    gate.shouldAccept(providerSequence = 42, isFinal = true)
                }
            }

            start.countDown()
            val accepted = contenders.map { it.get(2, TimeUnit.SECONDS) }

            assertEquals(1, accepted.count { it })
            assertFalse(gate.shouldAccept(providerSequence = 42, isFinal = false))
        } finally {
            pool.shutdownNow()
        }
    }
}

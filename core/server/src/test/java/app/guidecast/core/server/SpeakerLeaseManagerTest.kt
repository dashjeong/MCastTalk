package app.guidecast.core.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerLeaseManagerTest {

    @Test
    fun `first speaker acquires lease and blocks concurrent speaker until closed`() {
        val manager = SpeakerLeaseManager(idleTimeoutNanos = 5_000_000_000L)
        val t0 = 1_000_000_000L

        // 1. Speaker 1 acquires lease
        val result1 = manager.tryAcquire("192.168.1.100", nowNanos = t0)
        assertTrue(result1 is SpeakerLeaseResult.Granted)
        val lease1 = (result1 as SpeakerLeaseResult.Granted).lease
        assertEquals(1L, lease1.generation)
        assertTrue(manager.isLeaseActive(nowNanos = t0))

        // 2. Speaker 2 tries to acquire lease while Speaker 1 is active -> BUSY
        val result2 = manager.tryAcquire("192.168.1.101", nowNanos = t0 + 1_000_000_000L)
        assertTrue(result2 is SpeakerLeaseResult.Busy)

        // 3. Speaker 1 closes lease -> Speaker 2 can now acquire
        lease1.close()
        assertFalse(manager.isLeaseActive(nowNanos = t0 + 2_000_000_000L))

        val result3 = manager.tryAcquire("192.168.1.101", nowNanos = t0 + 2_000_000_000L)
        assertTrue(result3 is SpeakerLeaseResult.Granted)
        val lease2 = (result3 as SpeakerLeaseResult.Granted).lease
        assertEquals(2L, lease2.generation)

        lease2.close()
    }

    @Test
    fun `idle timeout expires abandoned lease and allows new speaker`() {
        val timeoutNanos = 3_000_000_000L // 3 seconds
        val manager = SpeakerLeaseManager(idleTimeoutNanos = timeoutNanos)
        val t0 = 10_000_000_000L

        val result1 = manager.tryAcquire("192.168.1.100", nowNanos = t0)
        assertTrue(result1 is SpeakerLeaseResult.Granted)
        val lease1 = (result1 as SpeakerLeaseResult.Granted).lease

        // Before timeout: Busy
        val busyResult = manager.tryAcquire("192.168.1.101", nowNanos = t0 + 2_000_000_000L)
        assertTrue(busyResult is SpeakerLeaseResult.Busy)

        // After timeout (4s > 3s timeout): Expired lease reclaimed
        val successResult = manager.tryAcquire("192.168.1.101", nowNanos = t0 + 4_000_000_000L)
        assertTrue(successResult is SpeakerLeaseResult.Granted)
        assertFalse(manager.recordActivity(lease1.generation, nowNanos = t0 + 5_000_000_000L))

        // Activity recorded for lease 2 extends its life
        assertTrue(manager.recordActivity(
            generation = (successResult as SpeakerLeaseResult.Granted).lease.generation,
            nowNanos = t0 + 6_000_000_000L,
        ))
        assertTrue(manager.isLeaseActive(nowNanos = t0 + 7_000_000_000L))
    }
}

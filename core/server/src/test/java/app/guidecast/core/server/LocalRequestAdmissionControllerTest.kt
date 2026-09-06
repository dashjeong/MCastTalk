package app.guidecast.core.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRequestAdmissionControllerTest {
    @Test
    fun `rate windows are isolated by remote address and reset on the monotonic clock`() {
        var nowMillis = 10_000L
        val controller = LocalRequestAdmissionController(
            windowMillis = 1_000L,
            idleRetentionMillis = 1_000L,
            readApiLimit = LocalRequestLimit(
                maxRequestsPerWindow = 2,
                maxConcurrentRequests = 2,
            ),
            timeSourceMillis = { nowMillis },
        )

        repeat(2) {
            val allowed = controller.tryAcquire("192.168.45.2", LocalRequestKind.READ_API)
                as LocalRequestAdmission.Allowed
            allowed.lease.close()
        }
        assertTrue(
            controller.tryAcquire("192.168.45.2", LocalRequestKind.READ_API) is
                LocalRequestAdmission.Rejected,
        )

        val otherRemote = controller.tryAcquire("192.168.45.3", LocalRequestKind.READ_API)
            as LocalRequestAdmission.Allowed
        otherRemote.lease.close()

        nowMillis += 1_000L
        val reset = controller.tryAcquire("192.168.45.2", LocalRequestKind.READ_API)
            as LocalRequestAdmission.Allowed
        reset.lease.close()
    }

    @Test
    fun `concurrent requests are bounded and an idempotent lease release restores capacity`() {
        val controller = LocalRequestAdmissionController(
            readApiLimit = LocalRequestLimit(
                maxRequestsPerWindow = 10,
                maxConcurrentRequests = 1,
            ),
        )

        val first = controller.tryAcquire("192.168.45.2", LocalRequestKind.READ_API)
            as LocalRequestAdmission.Allowed
        assertTrue(
            controller.tryAcquire("192.168.45.2", LocalRequestKind.READ_API) is
                LocalRequestAdmission.Rejected,
        )

        first.lease.close()
        first.lease.close()
        val afterRelease = controller.tryAcquire("192.168.45.2", LocalRequestKind.READ_API)
            as LocalRequestAdmission.Allowed
        afterRelease.lease.close()
    }

    @Test
    fun `one remote cannot exhaust websocket capacity reserved for distinct listeners`() {
        val controller = LocalRequestAdmissionController(
            webSocketLimit = LocalRequestLimit(
                maxRequestsPerWindow = 100,
                maxConcurrentRequests = 2,
            ),
        )
        val firstRemoteLeases = List(2) {
            (controller.tryAcquire(
                "192.168.45.2",
                LocalRequestKind.WEBSOCKET_HANDSHAKE,
            ) as LocalRequestAdmission.Allowed).lease
        }
        assertTrue(
            controller.tryAcquire("192.168.45.2", LocalRequestKind.WEBSOCKET_HANDSHAKE) is
                LocalRequestAdmission.Rejected,
        )

        val distinctListenerLeases = (3..52).map { host ->
            val decision = controller.tryAcquire(
                "192.168.45.$host",
                LocalRequestKind.WEBSOCKET_HANDSHAKE,
            )
            assertTrue("distinct listener $host was rejected", decision is LocalRequestAdmission.Allowed)
            (decision as LocalRequestAdmission.Allowed).lease
        }
        assertEquals(50, distinctListenerLeases.size)

        firstRemoteLeases.first().close()
        val restored = controller.tryAcquire(
            "192.168.45.2",
            LocalRequestKind.WEBSOCKET_HANDSHAKE,
        ) as LocalRequestAdmission.Allowed
        restored.lease.close()
        firstRemoteLeases.drop(1).forEach { it.close() }
        distinctListenerLeases.forEach { it.close() }
    }

    @Test
    fun `tracked remote table has a hard cap and only prunes expired inactive entries`() {
        var nowMillis = 1_000L
        val controller = LocalRequestAdmissionController(
            windowMillis = 1_000L,
            idleRetentionMillis = 1_000L,
            maxTrackedRemotes = 2,
            timeSourceMillis = { nowMillis },
        )

        val first = controller.tryAcquire("192.168.45.2", LocalRequestKind.PAGE_ASSET)
            as LocalRequestAdmission.Allowed
        val second = controller.tryAcquire("192.168.45.3", LocalRequestKind.PAGE_ASSET)
            as LocalRequestAdmission.Allowed
        first.lease.close()
        second.lease.close()

        val atCapacity = controller.tryAcquire("192.168.45.4", LocalRequestKind.PAGE_ASSET)
        assertTrue(atCapacity is LocalRequestAdmission.Rejected)
        assertEquals(1L, (atCapacity as LocalRequestAdmission.Rejected).retryAfterSeconds)

        nowMillis += 1_000L
        val afterPrune = controller.tryAcquire("192.168.45.4", LocalRequestKind.PAGE_ASSET)
            as LocalRequestAdmission.Allowed
        afterPrune.lease.close()
    }
}

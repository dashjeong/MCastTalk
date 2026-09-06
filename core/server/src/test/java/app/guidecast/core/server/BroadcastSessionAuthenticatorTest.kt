package app.guidecast.core.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcastSessionAuthenticatorTest {
    @Test
    fun `open session accepts requests without token`() {
        val authenticator = BroadcastSessionAuthenticator.create(BroadcastAccess.Open)

        assertTrue(authenticator.authorize(null))
    }

    @Test
    fun `qr session accepts only generated token`() {
        val authenticator = BroadcastSessionAuthenticator.create(BroadcastAccess.QrToken)
        val token = requireNotNull(authenticator.tokenForQr())

        assertTrue(authenticator.authorize(token))
        assertFalse(authenticator.authorize("wrong-token"))
        assertFalse(authenticator.authorize(null))
    }

    @Test
    fun `pin session returns a usable token and clears supplied candidate`() {
        val authenticator = BroadcastSessionAuthenticator.create(
            BroadcastAccess.Pin.from(charArrayOf('1', '2', '3', '4')),
        )
        val candidate = charArrayOf('1', '2', '3', '4')

        val result = authenticator.joinWithPin(candidate, "192.168.1.20")

        assertTrue(result is PinJoinResult.Success)
        assertTrue(candidate.all { it == '\u0000' })
        assertTrue(authenticator.authorize((result as PinJoinResult.Success).token))
    }

    @Test
    fun `pin attempts are rate limited after five failures`() {
        val authenticator = BroadcastSessionAuthenticator.create(
            BroadcastAccess.Pin.from(charArrayOf('1', '2', '3', '4')),
        )

        repeat(5) {
            assertEquals(
                PinJoinResult.Invalid,
                authenticator.joinWithPin(charArrayOf('0', '0', '0', '0'), "192.168.1.21"),
            )
        }
        assertEquals(
            PinJoinResult.RateLimited,
            authenticator.joinWithPin(charArrayOf('1', '2', '3', '4'), "192.168.1.21"),
        )
    }

    @Test
    fun `pin failure tracking has a hard cap without evicting active limits`() {
        val limiter = PinAttemptLimiter(
            maxFailures = 5,
            windowMillis = 100L,
            blockMillis = 500L,
            maxTrackedRemotes = 2,
        )

        listOf("peer-a", "peer-b").forEach { peer ->
            assertTrue(limiter.mayAttempt(peer, now = 0L))
            limiter.recordFailure(peer, now = 0L)
        }
        repeat(10_000) { suffix ->
            val peer = "overflow-$suffix"
            assertFalse(limiter.mayAttempt(peer, now = 50L))
        }

        assertEquals(2, limiter.trackedRemoteCount())
        // Capacity pressure must not reset the failure history of a tracked peer.
        repeat(4) { limiter.recordFailure("peer-a", now = 50L) }
        assertFalse(limiter.mayAttempt("peer-a", now = 200L))

        // peer-b was not blocked and expires after its window. Reclaiming it admits exactly one
        // new peer while peer-a's configured block remains intact.
        assertTrue(limiter.mayAttempt("replacement", now = 200L))
        limiter.recordFailure("replacement", now = 200L)
        assertEquals(2, limiter.trackedRemoteCount())
        assertFalse(limiter.mayAttempt("peer-a", now = 499L))
        assertTrue(limiter.mayAttempt("peer-a", now = 550L))
    }

    @Test
    fun `rate limited listener candidate is cleared`() {
        val authenticator = BroadcastSessionAuthenticator.create(
            BroadcastAccess.Pin.from(charArrayOf('1', '2', '3', '4')),
        )
        repeat(5) {
            authenticator.joinWithPin(charArrayOf('0', '0', '0', '0'), "192.168.1.22")
        }
        val candidate = charArrayOf('1', '2', '3', '4')

        assertEquals(
            PinJoinResult.RateLimited,
            authenticator.joinWithPin(candidate, "192.168.1.22"),
        )
        assertTrue(candidate.all { it == '\u0000' })
    }

    @Test
    fun `listener PIN and speaker PIN issue isolated capability tokens`() {
        val authenticator = BroadcastSessionAuthenticator.create(
            access = BroadcastAccess.Pin.from(charArrayOf('1', '2', '3', '4')),
            speakerAccess = SpeakerAccess.Pin.from(charArrayOf('5', '6', '7', '8')),
        )

        val listenerJoin = authenticator.joinWithPin(
            charArrayOf('1', '2', '3', '4'),
            "192.168.1.30",
        ) as PinJoinResult.Success
        val speakerJoin = authenticator.joinSpeaker(
            charArrayOf('5', '6', '7', '8'),
            "192.168.1.30",
        ) as PinJoinResult.Success

        assertFalse(listenerJoin.token == speakerJoin.token)
        assertTrue(authenticator.authorize(listenerJoin.token))
        assertFalse(authenticator.authorize(speakerJoin.token))
        assertTrue(authenticator.authorizeSpeaker(speakerJoin.token))
        assertFalse(authenticator.authorizeSpeaker(listenerJoin.token))
    }

    @Test
    fun `open speaker access still returns only the speaker capability and clears candidate`() {
        val authenticator = BroadcastSessionAuthenticator.create(
            access = BroadcastAccess.QrToken,
            speakerAccess = SpeakerAccess.Open,
        )
        val candidate = charArrayOf('9', '9', '9', '9')

        val result = authenticator.joinSpeaker(candidate, "192.168.1.31")

        assertFalse(authenticator.speakerRequiresPin)
        assertTrue(result is PinJoinResult.Success)
        assertTrue(candidate.all { it == '\u0000' })
        val speakerToken = (result as PinJoinResult.Success).token
        assertTrue(authenticator.authorizeSpeaker(speakerToken))
        assertFalse(authenticator.authorize(speakerToken))
        assertFalse(authenticator.authorizeSpeaker(requireNotNull(authenticator.tokenForQr())))
    }

    @Test
    fun `speaker PIN rejects the listener PIN and clears every supplied candidate`() {
        val authenticator = BroadcastSessionAuthenticator.create(
            access = BroadcastAccess.Pin.from(charArrayOf('1', '2', '3', '4')),
            speakerAccess = SpeakerAccess.Pin.from(charArrayOf('5', '6', '7', '8')),
        )
        val wrongCandidate = charArrayOf('1', '2', '3', '4')
        val correctCandidate = charArrayOf('5', '6', '7', '8')

        assertEquals(
            PinJoinResult.Invalid,
            authenticator.joinSpeaker(wrongCandidate, "192.168.1.32"),
        )
        val success = authenticator.joinSpeaker(correctCandidate, "192.168.1.32")

        assertTrue(wrongCandidate.all { it == '\u0000' })
        assertTrue(correctCandidate.all { it == '\u0000' })
        assertTrue(success is PinJoinResult.Success)
        assertTrue(authenticator.authorizeSpeaker((success as PinJoinResult.Success).token))
    }

    @Test
    fun `listener and speaker PIN rate limits do not poison each other`() {
        val speakerLimited = BroadcastSessionAuthenticator.create(
            access = BroadcastAccess.Pin.from(charArrayOf('1', '2', '3', '4')),
            speakerAccess = SpeakerAccess.Pin.from(charArrayOf('5', '6', '7', '8')),
        )
        repeat(5) {
            assertEquals(
                PinJoinResult.Invalid,
                speakerLimited.joinSpeaker(charArrayOf('0', '0', '0', '0'), "192.168.1.33"),
            )
        }
        assertEquals(
            PinJoinResult.RateLimited,
            speakerLimited.joinSpeaker(charArrayOf('5', '6', '7', '8'), "192.168.1.33"),
        )
        assertTrue(
            speakerLimited.joinWithPin(
                charArrayOf('1', '2', '3', '4'),
                "192.168.1.33",
            ) is PinJoinResult.Success,
        )

        val listenerLimited = BroadcastSessionAuthenticator.create(
            access = BroadcastAccess.Pin.from(charArrayOf('1', '2', '3', '4')),
            speakerAccess = SpeakerAccess.Pin.from(charArrayOf('5', '6', '7', '8')),
        )
        repeat(5) {
            assertEquals(
                PinJoinResult.Invalid,
                listenerLimited.joinWithPin(charArrayOf('0', '0', '0', '0'), "192.168.1.34"),
            )
        }
        assertEquals(
            PinJoinResult.RateLimited,
            listenerLimited.joinWithPin(charArrayOf('1', '2', '3', '4'), "192.168.1.34"),
        )
        assertTrue(
            listenerLimited.joinSpeaker(
                charArrayOf('5', '6', '7', '8'),
                "192.168.1.34",
            ) is PinJoinResult.Success,
        )
    }
}

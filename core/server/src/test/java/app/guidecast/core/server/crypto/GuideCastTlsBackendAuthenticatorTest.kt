package app.guidecast.core.server.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuideCastTlsBackendAuthenticatorTest {
    @Test
    fun `attestation authenticates canonical peer and rejects every tampered field`() {
        val authenticator = GuideCastTlsBackendAuthenticator.create()
        val attestation = authenticator.attest("192.168.45.20")
        val validHeaders = headers(attestation)

        assertEquals("192.168.45.20", authenticator.verify(validHeaders::get))
        assertNull(
            authenticator.verify(
                (validHeaders +
                    (GuideCastTlsBackendAuthenticator.PEER_HEADER to listOf("192.168.45.21")))::get,
            ),
        )
        assertNull(
            authenticator.verify(
                (validHeaders +
                    (GuideCastTlsBackendAuthenticator.MAC_HEADER to listOf("invalid")))::get,
            ),
        )
        assertNull(
            authenticator.verify(
                (validHeaders +
                    (GuideCastTlsBackendAuthenticator.VERSION_HEADER to listOf("2")))::get,
            ),
        )
    }

    @Test
    fun `duplicate capability headers are rejected`() {
        val authenticator = GuideCastTlsBackendAuthenticator.create()
        val attestation = authenticator.attest("127.0.0.1")
        val validHeaders = headers(attestation)
        val duplicated = validHeaders + (
            GuideCastTlsBackendAuthenticator.NONCE_HEADER to
                listOf(attestation.nonce, attestation.nonce)
            )

        assertNull(authenticator.verify(duplicated::get))
    }

    private fun headers(
        attestation: GuideCastTlsBackendAuthenticator.Attestation,
    ): Map<String, List<String>> = mapOf(
        GuideCastTlsBackendAuthenticator.VERSION_HEADER to
            listOf(GuideCastTlsBackendAuthenticator.PROTOCOL_VERSION),
        GuideCastTlsBackendAuthenticator.PEER_HEADER to listOf(attestation.peerAddress),
        GuideCastTlsBackendAuthenticator.NONCE_HEADER to listOf(attestation.nonce),
        GuideCastTlsBackendAuthenticator.MAC_HEADER to listOf(attestation.mac),
    )
}

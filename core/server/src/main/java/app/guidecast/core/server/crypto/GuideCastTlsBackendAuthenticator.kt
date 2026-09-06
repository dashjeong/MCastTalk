package app.guidecast.core.server.crypto

import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Per-process capability channel between the TLS terminator and its loopback-only HTTP backend.
 *
 * A loopback port is not an authentication boundary on Android: another application can connect
 * to it. The proxy therefore signs the original socket peer for every backend connection. The
 * backend accepts `TLS_TERMINATED` only when all four singleton headers verify. Client-supplied
 * headers with this prefix are stripped by [GuideCastTlsProxy] before these values are inserted.
 */
internal class GuideCastTlsBackendAuthenticator private constructor(
    secret: ByteArray,
    private val random: SecureRandom,
) {
    private val secret = secret.copyOf()

    class Attestation internal constructor(
        val peerAddress: String,
        val nonce: String,
        val mac: String,
    )

    fun attest(peerAddress: String): Attestation {
        val normalizedPeer = normalizeAddress(peerAddress)
            ?: throw IllegalArgumentException("Invalid TLS peer address")
        val nonceBytes = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes)
        return Attestation(
            peerAddress = normalizedPeer,
            nonce = nonce,
            mac = encodeMac(macFor(normalizedPeer, nonce)),
        )
    }

    /** Returns the authenticated original peer address, or null for any malformed/spoofed input. */
    fun verify(headerValues: (String) -> List<String>?): String? {
        val version = headerValues(VERSION_HEADER).singleValueOrNull() ?: return null
        if (version != PROTOCOL_VERSION) return null
        val peer = headerValues(PEER_HEADER).singleValueOrNull()?.let(::normalizeAddress) ?: return null
        val nonce = headerValues(NONCE_HEADER).singleValueOrNull() ?: return null
        val nonceBytes = decodeBase64Url(nonce) ?: return null
        if (nonceBytes.size != NONCE_BYTES) return null
        val suppliedMac = headerValues(MAC_HEADER).singleValueOrNull()
            ?.let(::decodeBase64Url)
            ?.takeIf { it.size == MAC_BYTES }
            ?: return null
        val expectedMac = macFor(peer, nonce)
        return peer.takeIf { MessageDigest.isEqual(expectedMac, suppliedMac) }
    }

    private fun macFor(peerAddress: String, nonce: String): ByteArray =
        Mac.getInstance(HMAC_ALGORITHM).run {
            init(SecretKeySpec(secret, HMAC_ALGORITHM))
            doFinal(
                "$PROTOCOL_VERSION\n$nonce\n$peerAddress"
                    .toByteArray(StandardCharsets.US_ASCII),
            )
        }

    private fun encodeMac(value: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun decodeBase64Url(value: String): ByteArray? {
        if (value.isBlank() || value.length > MAX_ENCODED_VALUE_LENGTH) return null
        return runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull()
    }

    private fun normalizeAddress(value: String): String? {
        if (value.isBlank() || value.length > MAX_PEER_ADDRESS_LENGTH) return null
        if (value.any { character ->
                !(character.isDigit() || character in 'a'..'f' || character in 'A'..'F' ||
                    character == '.' || character == ':')
            }
        ) return null
        return runCatching { InetAddress.getByName(value).hostAddress }
            .getOrNull()
            ?.takeIf { normalized -> normalized.equals(value, ignoreCase = true) }
    }

    private fun List<String>?.singleValueOrNull(): String? =
        this?.takeIf { it.size == 1 }?.single()?.takeIf { it.isNotBlank() && it == it.trim() }

    companion object {
        const val INTERNAL_HEADER_PREFIX = "x-guidecast-internal-"
        const val VERSION_HEADER = "X-GuideCast-Internal-Version"
        const val PEER_HEADER = "X-GuideCast-Internal-Peer"
        const val NONCE_HEADER = "X-GuideCast-Internal-Nonce"
        const val MAC_HEADER = "X-GuideCast-Internal-Mac"
        const val PROTOCOL_VERSION = "1"

        private const val SECRET_BYTES = 32
        private const val NONCE_BYTES = 16
        private const val MAC_BYTES = 32
        private const val MAX_PEER_ADDRESS_LENGTH = 64
        private const val MAX_ENCODED_VALUE_LENGTH = 128
        private const val HMAC_ALGORITHM = "HmacSHA256"

        fun create(random: SecureRandom = SecureRandom()): GuideCastTlsBackendAuthenticator =
            GuideCastTlsBackendAuthenticator(
                secret = ByteArray(SECRET_BYTES).also(random::nextBytes),
                random = random,
            )
    }
}

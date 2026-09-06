package app.guidecast.core.server.crypto

import io.ktor.server.engine.connector
import io.ktor.server.engine.sslConnector
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXCertPathValidatorResult
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideCastCertificateAuthorityTest {

    @Test
    fun `root CA is generated with valid X509v3 structure and CA basic constraints`() {
        val tempDir = File.createTempFile("guidecast-ca-test", "")
        tempDir.delete()
        tempDir.mkdirs()

        try {
            val ca = GuideCastCertificateAuthority.getOrCreate(tempDir)
            val cert = ca.caCertificate

            // 1. Version 3
            assertEquals(3, cert.version)

            // 2. Self-signed signature check
            cert.verify(ca.caKeyPair.public)
            cert.checkValidity()

            // 3. CA Basic Constraints (cA=true, pathLen=0)
            assertTrue(cert.basicConstraints >= 0)

            // 4. KeyUsage: keyCertSign (bit 5), cRLSign (bit 6)
            val ku = cert.keyUsage
            assertNotNull(ku)
            assertTrue("bit 5 (keyCertSign) must be true", ku[5])
            assertTrue("bit 6 (cRLSign) must be true", ku[6])

            // 5. Persistence reload check
            val reloaded = GuideCastCertificateAuthority.getOrCreate(tempDir)
            assertEquals(cert.serialNumber, reloaded.caCertificate.serialNumber)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `server leaf certificate includes IP SAN, DNS SAN, and verifies against Root CA`() {
        val tempDir = File.createTempFile("guidecast-leaf-test", "")
        tempDir.delete()
        tempDir.mkdirs()

        try {
            val ca = GuideCastCertificateAuthority.getOrCreate(tempDir)
            val testIp = InetAddress.getByName("192.168.43.1")
            val bundle = ca.issueServerCertificate(
                hostAddress = testIp,
                dnsName = "guidecast.local",
                validityDays = 30,
            )

            val serverCert = bundle.serverCert

            // 1. Version 3
            assertEquals(3, serverCert.version)

            // 2. Verified against Root CA public key
            serverCert.verify(ca.caKeyPair.public)
            serverCert.checkValidity()

            // 3. Not a CA
            assertEquals(-1, serverCert.basicConstraints)

            // 4. KeyUsage: digitalSignature (0), keyEncipherment (2)
            val ku = serverCert.keyUsage
            assertNotNull(ku)
            assertTrue("bit 0 (digitalSignature) must be true", ku[0])
            assertTrue("bit 2 (keyEncipherment) must be true", ku[2])

            // 5. ExtendedKeyUsage: serverAuth (1.3.6.1.5.5.7.3.1)
            val eku = serverCert.extendedKeyUsage
            assertNotNull(eku)
            assertTrue(eku.contains("1.3.6.1.5.5.7.3.1"))

            // 6. Subject Alternative Names (SAN)
            val sanCollection = serverCert.subjectAlternativeNames
            assertNotNull("SubjectAlternativeNames must be present", sanCollection)

            // Type 2: dNSName, Type 7: iPAddress
            val dnsNames = sanCollection.filter { it[0] == 2 }.map { it[1].toString() }
            val ipAddresses = sanCollection.filter { it[0] == 7 }.map { it[1].toString() }

            assertTrue("SAN must contain DNS guidecast.local", dnsNames.contains("guidecast.local"))
            assertTrue("SAN must contain IP 192.168.43.1", ipAddresses.contains("192.168.43.1"))

            // 7. In-memory KeyStore validation
            val ks = bundle.toKeyStore()
            assertTrue(ks.isKeyEntry(ServerCertificateBundle.KEY_ALIAS))
            val chain = ks.getCertificateChain(ServerCertificateBundle.KEY_ALIAS)
            assertEquals(2, chain.size)
            assertEquals(serverCert, chain[0])
            assertEquals(ca.caCertificate, chain[1])

            // 8. Apple .mobileconfig generation
            val mobileConfig = ca.generateAppleMobileConfig()
            assertTrue(mobileConfig.contains("com.apple.security.root"))
            assertTrue(mobileConfig.contains("guidecast-root-ca.crt"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `PEM download round trips through the platform X509 parser`() {
        withCertificateAuthority("guidecast-pem-test") { ca ->
            val pem = ca.caCertificatePem()
            val pemText = pem.toString(Charsets.US_ASCII)

            assertTrue(pemText.startsWith("-----BEGIN CERTIFICATE-----\n"))
            assertTrue(pemText.endsWith("-----END CERTIFICATE-----\n"))
            val parsed = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(pem)) as X509Certificate

            assertArrayEquals(ca.caCertificate.encoded, parsed.encoded)
            assertEquals(ca.caCertificate.subjectX500Principal, parsed.subjectX500Principal)
            val expectedFingerprint = MessageDigest.getInstance("SHA-256")
                .digest(parsed.encoded)
                .joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
            assertEquals(expectedFingerprint, ca.caSha256Fingerprint())
        }
    }

    @Test
    fun `downloaded DER fingerprint verification accepts exact bytes and warns on tampering`() {
        withCertificateAuthority("guidecast-ca-fingerprint-verification") { ca ->
            val expected = ca.caSha256Fingerprint()
            val exact = verifyGuideCastCaDerFingerprint(expected, ca.caCertificate.encoded)

            assertTrue(exact.matches)
            assertEquals(expected, exact.downloadedCertificateFingerprint)
            assertEquals(null, exact.warning)

            val tampered = ca.caCertificate.encoded.copyOf().also { bytes ->
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
            }
            val mismatch = verifyGuideCastCaDerFingerprint(expected, tampered)

            assertTrue(!mismatch.matches)
            assertTrue(mismatch.downloadedCertificateFingerprint != expected)
            assertNotNull(mismatch.warning)
            assertTrue(mismatch.warning.orEmpty().contains("지문 불일치"))
            assertTrue(mismatch.warning.orEmpty().contains("설치하지"))
        }
    }

    @Test
    fun `server certificate passes platform PKIX validation against generated root`() {
        withCertificateAuthority("guidecast-pkix-test") { ca ->
            val bundle = ca.issueServerCertificate(
                hostAddress = InetAddress.getByName("192.168.45.1"),
                dnsName = "guidecast.local",
                validityDays = 30,
            )
            val certificateFactory = CertificateFactory.getInstance("X.509")
            val certPath = certificateFactory.generateCertPath(listOf(bundle.serverCert))
            val parameters = PKIXParameters(setOf(TrustAnchor(ca.caCertificate, null))).apply {
                isRevocationEnabled = false
            }

            val result = CertPathValidator.getInstance("PKIX")
                .validate(certPath, parameters) as PKIXCertPathValidatorResult

            assertEquals(ca.caCertificate, result.trustAnchor.trustedCert)
            assertEquals(ca.caCertificate.subjectX500Principal, bundle.serverCert.issuerX500Principal)
            assertTrue(ca.caCertificate.serialNumber.signum() > 0)
            assertTrue(bundle.serverCert.serialNumber.signum() > 0)
        }
    }

    @Test
    fun `root and leaf expose consistent subject and authority key identifiers`() {
        withCertificateAuthority("guidecast-key-id-test") { ca ->
            val leaf = ca.issueServerCertificate(
                hostAddress = InetAddress.getByName("192.168.45.1"),
                dnsName = "guidecast.local",
            ).serverCert

            val rootSubjectKeyId = subjectKeyIdentifier(ca.caCertificate)
            val rootAuthorityKeyId = authorityKeyIdentifier(ca.caCertificate)
            val leafSubjectKeyId = subjectKeyIdentifier(leaf)
            val leafAuthorityKeyId = authorityKeyIdentifier(leaf)

            assertArrayEquals(
                MessageDigest.getInstance("SHA-256").digest(ca.caCertificate.publicKey.encoded),
                rootSubjectKeyId,
            )
            assertArrayEquals(rootSubjectKeyId, rootAuthorityKeyId)
            assertArrayEquals(rootSubjectKeyId, leafAuthorityKeyId)
            assertArrayEquals(
                MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded),
                leafSubjectKeyId,
            )
            assertTrue("Root and leaf SKI must identify different keys", !rootSubjectKeyId.contentEquals(leafSubjectKeyId))
        }
    }

    @Test
    fun `valid legacy key and certificate migrate atomically without rotating trusted root`() {
        val sourceDir = newTempDirectory("guidecast-ca-legacy-source")
        val migrationDir = newTempDirectory("guidecast-ca-legacy-target")
        try {
            val legacy = GuideCastCertificateAuthority.getOrCreate(sourceDir)
            File(migrationDir, "ca-v2.key").writeBytes(legacy.caKeyPair.private.encoded)
            File(migrationDir, "ca-v2.crt").writeBytes(legacy.caCertificate.encoded)

            val migrated = GuideCastCertificateAuthority.getOrCreate(migrationDir)
            val reloaded = GuideCastCertificateAuthority.getOrCreate(migrationDir)

            assertEquals(legacy.caCertificate.serialNumber, migrated.caCertificate.serialNumber)
            assertEquals(migrated.caCertificate.serialNumber, reloaded.caCertificate.serialNumber)
            assertTrue(File(migrationDir, "ca-v3.p12").isFile)
            assertFalse(
                "Validated migration must not retain a duplicate raw private key",
                File(migrationDir, "ca-v2.key").exists(),
            )
            assertTrue(
                MessageDigest.isEqual(
                    legacy.caCertificate.encoded,
                    migrated.caCertificate.encoded,
                ),
            )
            migrated.issueServerCertificate(InetAddress.getByName("192.168.45.1"))
                .serverCert
                .verify(migrated.caCertificate.publicKey)
        } finally {
            sourceDir.deleteRecursively()
            migrationDir.deleteRecursively()
        }
    }

    @Test
    fun `mismatched legacy private key and certificate are rejected as one pair`() {
        val firstDir = newTempDirectory("guidecast-ca-first")
        val secondDir = newTempDirectory("guidecast-ca-second")
        val targetDir = newTempDirectory("guidecast-ca-mismatch")
        try {
            val first = GuideCastCertificateAuthority.getOrCreate(firstDir)
            val second = GuideCastCertificateAuthority.getOrCreate(secondDir)
            File(targetDir, "ca-v2.key").writeBytes(first.caKeyPair.private.encoded)
            File(targetDir, "ca-v2.crt").writeBytes(second.caCertificate.encoded)

            val recovered = GuideCastCertificateAuthority.getOrCreate(targetDir)

            assertTrue(recovered.caCertificate.serialNumber != first.caCertificate.serialNumber)
            assertTrue(recovered.caCertificate.serialNumber != second.caCertificate.serialNumber)
            assertTrue(
                "An unverified legacy pair must remain available for explicit recovery",
                File(targetDir, "ca-v2.key").isFile,
            )
            recovered.caCertificate.verify(recovered.caCertificate.publicKey)
            recovered.issueServerCertificate(InetAddress.getByName("192.168.45.1"))
                .serverCert
                .verify(recovered.caCertificate.publicKey)
        } finally {
            firstDir.deleteRecursively()
            secondDir.deleteRecursively()
            targetDir.deleteRecursively()
        }
    }

    @Test
    fun `corrupt atomic container falls back to a valid legacy pair`() {
        val sourceDir = newTempDirectory("guidecast-ca-fallback-source")
        val targetDir = newTempDirectory("guidecast-ca-fallback-target")
        try {
            val legacy = GuideCastCertificateAuthority.getOrCreate(sourceDir)
            File(targetDir, "ca-v2.key").writeBytes(legacy.caKeyPair.private.encoded)
            File(targetDir, "ca-v2.crt").writeBytes(legacy.caCertificate.encoded)
            File(targetDir, "ca-v3.p12").writeBytes(byteArrayOf(1, 2, 3, 4))

            val recovered = GuideCastCertificateAuthority.getOrCreate(targetDir)
            val reloaded = GuideCastCertificateAuthority.getOrCreate(targetDir)

            assertEquals(legacy.caCertificate.serialNumber, recovered.caCertificate.serialNumber)
            assertEquals(recovered.caCertificate.serialNumber, reloaded.caCertificate.serialNumber)
            assertFalse(
                "A recovered and reloaded exact legacy key must be removed",
                File(targetDir, "ca-v2.key").exists(),
            )
        } finally {
            sourceDir.deleteRecursively()
            targetDir.deleteRecursively()
        }
    }

    private fun withCertificateAuthority(
        prefix: String,
        block: (GuideCastCertificateAuthority) -> Unit,
    ) {
        val tempDir = File.createTempFile(prefix, "")
        tempDir.delete()
        tempDir.mkdirs()
        try {
            block(GuideCastCertificateAuthority.getOrCreate(tempDir))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun newTempDirectory(prefix: String): File =
        File.createTempFile(prefix, "").apply {
            delete()
            mkdirs()
        }

    private fun subjectKeyIdentifier(certificate: X509Certificate): ByteArray {
        val extension = requireNotNull(certificate.getExtensionValue(SUBJECT_KEY_IDENTIFIER_OID))
        val outer = readDerElement(extension)
        assertEquals(DER_OCTET_STRING, outer.tag)
        assertEquals(extension.size, outer.nextOffset)
        val inner = readDerElement(outer.value)
        assertEquals(DER_OCTET_STRING, inner.tag)
        assertEquals(outer.value.size, inner.nextOffset)
        return inner.value
    }

    private fun authorityKeyIdentifier(certificate: X509Certificate): ByteArray {
        val extension = requireNotNull(certificate.getExtensionValue(AUTHORITY_KEY_IDENTIFIER_OID))
        val outer = readDerElement(extension)
        assertEquals(DER_OCTET_STRING, outer.tag)
        assertEquals(extension.size, outer.nextOffset)
        val sequence = readDerElement(outer.value)
        assertEquals(DER_SEQUENCE, sequence.tag)
        assertEquals(outer.value.size, sequence.nextOffset)
        val keyIdentifier = readDerElement(sequence.value)
        assertEquals(DER_CONTEXT_KEY_IDENTIFIER, keyIdentifier.tag)
        assertEquals(sequence.value.size, keyIdentifier.nextOffset)
        return keyIdentifier.value
    }

    private fun readDerElement(encoded: ByteArray, offset: Int = 0): DerElement {
        require(offset in encoded.indices) { "DER element offset is out of range" }
        val tag = encoded[offset].toInt() and 0xFF
        var cursor = offset + 1
        require(cursor < encoded.size) { "DER element has no length" }
        val firstLength = encoded[cursor++].toInt() and 0xFF
        val length = if (firstLength and 0x80 == 0) {
            firstLength
        } else {
            val lengthBytes = firstLength and 0x7F
            require(lengthBytes in 1..4 && cursor + lengthBytes <= encoded.size) {
                "Unsupported DER length"
            }
            var decoded = 0
            repeat(lengthBytes) {
                decoded = (decoded shl 8) or (encoded[cursor++].toInt() and 0xFF)
            }
            decoded
        }
        require(length >= 0 && cursor + length <= encoded.size) { "Truncated DER element" }
        return DerElement(
            tag = tag,
            value = encoded.copyOfRange(cursor, cursor + length),
            nextOffset = cursor + length,
        )
    }

    private data class DerElement(
        val tag: Int,
        val value: ByteArray,
        val nextOffset: Int,
    )

    private companion object {
        const val SUBJECT_KEY_IDENTIFIER_OID = "2.5.29.14"
        const val AUTHORITY_KEY_IDENTIFIER_OID = "2.5.29.35"
        const val DER_OCTET_STRING = 0x04
        const val DER_SEQUENCE = 0x30
        const val DER_CONTEXT_KEY_IDENTIFIER = 0x80
    }
}

package app.guidecast.core.server.crypto

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date
import java.util.UUID

data class ServerCertificateBundle(
    val serverKey: KeyPair,
    val serverCert: X509Certificate,
    val caCert: X509Certificate,
) {
    /** Creates an in-memory PKCS12 KeyStore for the app's JSSE TLS termination layer. */
    fun toKeyStore(password: CharArray = KEYSTORE_PASSWORD): KeyStore {
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, password)
        val chain = arrayOf(serverCert, caCert)
        keyStore.setKeyEntry(KEY_ALIAS, serverKey.private, password, chain)
        return keyStore
    }

    companion object {
        val KEYSTORE_PASSWORD = "guidecast-internal-tls-key".toCharArray()
        const val KEY_ALIAS = "guidecast-server"
    }
}

/**
 * On-device Local Certificate Authority & Dynamic IP SAN Certificate Generator.
 *
 * Provides a unique, per-installation Root CA that issues dynamic TLS server leaf
 * certificates containing the transmitter's current Wi-Fi hotspot IP address in the
 * Subject Alternative Name (IP SAN) extension.
 *
 * Complies strictly with AGENTS.md:
 * - Zero hardcoded or pre-shared private keys in APK or repository.
 * - Zero external library dependencies (uses pure Kotlin Asn1Der encoder).
 * - Full offline operation without public WebPKI dependency.
 */
class GuideCastCertificateAuthority(
    val caKeyPair: KeyPair,
    val caCertificate: X509Certificate,
) {

    /** PEM is the most broadly accepted manual CA-import format on Android and desktop OSes. */
    fun caCertificatePem(): ByteArray {
        val body = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
            .encodeToString(caCertificate.encoded)
        return buildString {
            append("-----BEGIN CERTIFICATE-----\n")
            append(body)
            append("\n-----END CERTIFICATE-----\n")
        }.toByteArray(StandardCharsets.US_ASCII)
    }

    fun caSha256Fingerprint(): String = MessageDigest.getInstance("SHA-256")
        .digest(caCertificate.encoded)
        .joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }

    /**
     * Issues a dynamic TLS server leaf certificate signed by this Root CA.
     * Includes the current Wi-Fi hotspot IP address in the Subject Alternative Name (SAN).
     */
    fun issueServerCertificate(
        hostAddress: InetAddress,
        dnsName: String = "guidecast.local",
        validityDays: Int = 90,
    ): ServerCertificateBundle {
        val serverKeyPair = generateRsaKeyPair()
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 5 * 60 * 1_000L) // 5 min skew allowance
        val notAfter = Date(now + validityDays * 86_400_000L)
        val serialNumber = positiveSerialNumber()

        // 1. OIDs
        val sha256WithRsaOid = Asn1Der.oid(1, 2, 840, 113549, 1, 1, 11)
        val cnOid = Asn1Der.oid(2, 5, 4, 3)
        val oOid = Asn1Der.oid(2, 5, 4, 10)

        val basicConstraintsOid = Asn1Der.oid(2, 5, 29, 19)
        val keyUsageOid = Asn1Der.oid(2, 5, 29, 15)
        val extKeyUsageOid = Asn1Der.oid(2, 5, 29, 37)
        val serverAuthOid = Asn1Der.oid(1, 3, 6, 1, 5, 5, 7, 3, 1)
        val subjectAltNameOid = Asn1Der.oid(2, 5, 29, 17)
        val subjectKeyIdentifierOid = Asn1Der.oid(2, 5, 29, 14)
        val authorityKeyIdentifierOid = Asn1Der.oid(2, 5, 29, 35)

        val sigAlg = Asn1Der.sequence(sha256WithRsaOid, Asn1Der.nullValue())

        // 2. Names
        val issuer = caCertificate.subjectX500Principal.encoded
        val subject = Asn1Der.name(
            Asn1Der.rdn(oOid, Asn1Der.utf8String("GuideCast")),
            Asn1Der.rdn(cnOid, Asn1Der.utf8String(dnsName)),
        )

        // 3. SubjectPublicKeyInfo
        val subjectPublicKeyInfo = serverKeyPair.public.encoded
        val serverKeyIdentifier = keyIdentifier(serverKeyPair.public.encoded)
        val caKeyIdentifier = keyIdentifier(caKeyPair.public.encoded)

        // 4. Extensions
        // BasicConstraints: cA = false (critical)
        val extBasicConstraints = Asn1Der.sequence(
            basicConstraintsOid,
            Asn1Der.boolean(true), // critical
            Asn1Der.octetString(Asn1Der.sequence(Asn1Der.boolean(false))),
        )
        // KeyUsage: digitalSignature (bit 0), keyEncipherment (bit 2) -> 10100000 (0xA0), unused bits = 5
        val extKeyUsage = Asn1Der.sequence(
            keyUsageOid,
            Asn1Der.boolean(true), // critical
            Asn1Der.octetString(Asn1Der.bitString(byteArrayOf(0xA0.toByte()), unusedBits = 5)),
        )
        // ExtendedKeyUsage: serverAuth
        val extExtKeyUsage = Asn1Der.sequence(
            extKeyUsageOid,
            Asn1Der.octetString(Asn1Der.sequence(serverAuthOid)),
        )
        // SubjectAlternativeName: DNSName and IPAddress
        val sanContent = Asn1Der.sequence(
            Asn1Der.generalNameDns(dnsName),
            Asn1Der.generalNameIp(hostAddress),
        )
        val extSan = Asn1Der.sequence(
            subjectAltNameOid,
            Asn1Der.octetString(sanContent),
        )
        val extSubjectKeyIdentifier = Asn1Der.sequence(
            subjectKeyIdentifierOid,
            Asn1Der.octetString(Asn1Der.octetString(serverKeyIdentifier)),
        )
        val extAuthorityKeyIdentifier = Asn1Der.sequence(
            authorityKeyIdentifierOid,
            Asn1Der.octetString(
                Asn1Der.sequence(Asn1Der.taggedImplicit(0, caKeyIdentifier)),
            ),
        )

        val extensions = Asn1Der.taggedExplicit(
            3,
            Asn1Der.sequence(
                extBasicConstraints,
                extKeyUsage,
                extExtKeyUsage,
                extSan,
                extSubjectKeyIdentifier,
                extAuthorityKeyIdentifier,
            ),
        )

        // 5. TBSCertificate
        val tbsCertificate = Asn1Der.sequence(
            Asn1Der.taggedExplicit(0, Asn1Der.integer(2)), // v3
            Asn1Der.integer(serialNumber),
            sigAlg,
            issuer,
            Asn1Der.sequence(Asn1Der.utcTime(notBefore), Asn1Der.utcTime(notAfter)),
            subject,
            subjectPublicKeyInfo,
            extensions,
        )

        // 6. Sign with CA Private Key
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(caKeyPair.private)
            update(tbsCertificate)
        }.sign()

        // 7. Full X.509 Certificate DER
        val certDer = Asn1Der.sequence(
            tbsCertificate,
            sigAlg,
            Asn1Der.bitString(signature),
        )

        val serverCert = parseX509(certDer)
        serverCert.verify(caKeyPair.public) // verify signature integrity immediately

        return ServerCertificateBundle(
            serverKey = serverKeyPair,
            serverCert = serverCert,
            caCert = caCertificate,
        )
    }

    /**
     * Generates standard Apple .mobileconfig profile containing the Root CA certificate.
     * Enables 1-tap installation on iOS Safari.
     */
    fun generateAppleMobileConfig(): String {
        val caBase64 = Base64.getEncoder().encodeToString(caCertificate.encoded)
        val payloadUuid = UUID.nameUUIDFromBytes(caCertificate.encoded).toString()
        val profileUuid = UUID.randomUUID().toString()

        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>PayloadContent</key>
                <array>
                    <dict>
                        <key>PayloadCertificateFileName</key>
                        <string>guidecast-root-ca.crt</string>
                        <key>PayloadContent</key>
                        <data>$caBase64</data>
                        <key>PayloadDescription</key>
                        <string>GuideCast Hotspot Local Root CA Certificate</string>
                        <key>PayloadDisplayName</key>
                        <string>GuideCast Local Root CA</string>
                        <key>PayloadIdentifier</key>
                        <string>app.guidecast.ca.$payloadUuid</string>
                        <key>PayloadType</key>
                        <string>com.apple.security.root</string>
                        <key>PayloadUUID</key>
                        <string>$payloadUuid</string>
                        <key>PayloadVersion</key>
                        <integer>1</integer>
                    </dict>
                </array>
                <key>PayloadDescription</key>
                <string>Trusts the local GuideCast transmitter Wi-Fi hotspot certificate for secure browser microphone broadcast.</string>
                <key>PayloadDisplayName</key>
                <string>GuideCast Hotspot Security Profile</string>
                <key>PayloadIdentifier</key>
                <string>app.guidecast.profile.$profileUuid</string>
                <key>PayloadOrganization</key>
                <string>GuideCast</string>
                <key>PayloadRemovalDisallowed</key>
                <false/>
                <key>PayloadType</key>
                <string>Configuration</string>
                <key>PayloadUUID</key>
                <string>$profileUuid</string>
                <key>PayloadVersion</key>
                <integer>1</integer>
            </dict>
            </plist>
        """.trimIndent()
    }

    companion object {
        private val random = SecureRandom()
        private val CA_STORE_PASSWORD = "guidecast-private-ca-container".toCharArray()
        private const val CA_STORE_ALIAS = "guidecast-root-ca"
        private const val CA_STORE_FILE = "ca-v3.p12"

        fun generateRsaKeyPair(): KeyPair =
            KeyPairGenerator.getInstance("RSA").apply {
                initialize(2048, random)
            }.generateKeyPair()

        fun getOrCreate(storageDir: File): GuideCastCertificateAuthority {
            check((storageDir.isDirectory || storageDir.mkdirs()) && storageDir.isDirectory) {
                "Cannot create GuideCast CA storage directory"
            }
            val atomicStore = File(storageDir, CA_STORE_FILE)
            val keyFile = File(storageDir, "ca-v2.key")
            val certFile = File(storageDir, "ca-v2.crt")
            loadAtomicStore(atomicStore)?.let { persisted ->
                deleteMatchingLegacyPrivateKeyIfSafe(keyFile, certFile, persisted)
                return persisted
            }

            // v2 rotates the earlier hand-encoded profile whose DER-only download was rejected by
            // some Android certificate pickers. A valid pair is migrated without rotating the CA
            // already trusted by field devices. The raw legacy key is removed only after the new
            // container reloads, validates, and identifies the exact same trusted Root CA.
            val authority = loadLegacyPair(keyFile, certFile) ?: run {
                val caKeyPair = generateRsaKeyPair()
                val caCert = createSelfSignedRootCa(caKeyPair)
                GuideCastCertificateAuthority(caKeyPair, caCert).also(::validateRootCaPair)
            }
            writeAtomicStore(atomicStore, authority)
            val persisted = checkNotNull(loadAtomicStore(atomicStore)) {
                "GuideCast CA container did not reload after atomic migration"
            }
            check(MessageDigest.isEqual(authority.caCertificate.encoded, persisted.caCertificate.encoded)) {
                "GuideCast CA fingerprint changed during atomic migration"
            }
            deleteMatchingLegacyPrivateKeyIfSafe(keyFile, certFile, persisted)
            return persisted
        }

        private fun loadAtomicStore(file: File): GuideCastCertificateAuthority? {
            if (!file.isFile) return null
            return runCatching {
                val keyStore = KeyStore.getInstance("PKCS12")
                file.inputStream().buffered().use { input ->
                    keyStore.load(input, CA_STORE_PASSWORD)
                }
                val privateKey = keyStore.getKey(CA_STORE_ALIAS, CA_STORE_PASSWORD) as? PrivateKey
                    ?: error("GuideCast CA container has no private key")
                val cert = keyStore.getCertificate(CA_STORE_ALIAS) as? X509Certificate
                    ?: error("GuideCast CA container has no root certificate")
                GuideCastCertificateAuthority(
                    KeyPair(cert.publicKey, privateKey),
                    cert,
                ).also(::validateRootCaPair)
            }.getOrNull()
        }

        private fun loadLegacyPair(
            keyFile: File,
            certFile: File,
        ): GuideCastCertificateAuthority? {
            if (!keyFile.isFile || !certFile.isFile) return null
            if (
                java.nio.file.Files.isSymbolicLink(keyFile.toPath()) ||
                java.nio.file.Files.isSymbolicLink(certFile.toPath())
            ) {
                return null
            }
            return runCatching {
                val keySpec = PKCS8EncodedKeySpec(keyFile.readBytes())
                val privateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpec)
                val cert = parseX509(certFile.readBytes())
                GuideCastCertificateAuthority(
                    KeyPair(cert.publicKey, privateKey),
                    cert,
                ).also(::validateRootCaPair)
            }.getOrNull()
        }

        /**
         * Deletes the obsolete extractable v2 key only when both stores validate as the exact
         * same Root CA. A failed delete is deliberately non-fatal: the verified v3 container
         * remains usable and the next startup retries without rotating the field-trusted CA.
         *
         * This removes the legacy duplicate, but it is not a claim of physical flash erasure.
         */
        private fun deleteMatchingLegacyPrivateKeyIfSafe(
            keyFile: File,
            certFile: File,
            persisted: GuideCastCertificateAuthority,
        ): Boolean {
            if (!keyFile.isFile || !certFile.isFile) return false
            val legacy = loadLegacyPair(keyFile, certFile) ?: return false
            if (
                !MessageDigest.isEqual(
                    legacy.caCertificate.encoded,
                    persisted.caCertificate.encoded,
                )
            ) {
                return false
            }
            return runCatching {
                restrictToOwner(keyFile)
                java.nio.file.Files.deleteIfExists(keyFile.toPath())
                !keyFile.exists()
            }.getOrDefault(false)
        }

        /** Writes one key+certificate container and swaps it into place in a single filesystem op. */
        private fun writeAtomicStore(
            file: File,
            authority: GuideCastCertificateAuthority,
        ) {
            validateRootCaPair(authority)
            val keyStore = KeyStore.getInstance("PKCS12").apply {
                load(null, CA_STORE_PASSWORD)
                setKeyEntry(
                    CA_STORE_ALIAS,
                    authority.caKeyPair.private,
                    CA_STORE_PASSWORD,
                    arrayOf(authority.caCertificate),
                )
            }
            val temporary = File.createTempFile(".guidecast-ca-", ".tmp", file.parentFile)
            try {
                restrictToOwner(temporary)
                FileOutputStream(temporary).use { output ->
                    keyStore.store(output, CA_STORE_PASSWORD)
                    output.fd.sync()
                }
                java.nio.file.Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                restrictToOwner(file)
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }

        private fun restrictToOwner(file: File) {
            check(file.setReadable(false, false) && file.setReadable(true, true)) {
                "Cannot restrict GuideCast CA read permissions"
            }
            check(file.setWritable(false, false) && file.setWritable(true, true)) {
                "Cannot restrict GuideCast CA write permissions"
            }
            file.setExecutable(false, false)
        }

        private fun validateRootCaPair(authority: GuideCastCertificateAuthority) {
            val certificate = authority.caCertificate
            val keyPair = authority.caKeyPair
            certificate.checkValidity()
            check(certificate.subjectX500Principal == certificate.issuerX500Principal) {
                "GuideCast root certificate is not self-issued"
            }
            check(certificate.basicConstraints >= 0) {
                "GuideCast root certificate is not a CA"
            }
            val keyUsage = certificate.keyUsage
            check(keyUsage != null && keyUsage.size > 6 && keyUsage[5] && keyUsage[6]) {
                "GuideCast root certificate lacks certificate-signing usage"
            }
            check(keyPair.private.algorithm == "RSA" && certificate.publicKey.algorithm == "RSA") {
                "GuideCast root key algorithm is unsupported"
            }
            certificate.verify(certificate.publicKey)

            val challenge = ByteArray(32).also(random::nextBytes)
            val signature = Signature.getInstance("SHA256withRSA").apply {
                initSign(keyPair.private)
                update(challenge)
            }.sign()
            check(
                Signature.getInstance("SHA256withRSA").run {
                    initVerify(certificate.publicKey)
                    update(challenge)
                    verify(signature)
                },
            ) { "GuideCast root private key does not match its certificate" }
        }

        private fun createSelfSignedRootCa(keyPair: KeyPair): X509Certificate {
            val now = System.currentTimeMillis()
            val notBefore = Date(now - 10 * 60 * 1_000L)
            val notAfter = Date(now + 3650L * 86_400_000L) // 10 years validity
            val serialNumber = positiveSerialNumber()

            val sha256WithRsaOid = Asn1Der.oid(1, 2, 840, 113549, 1, 1, 11)
            val cnOid = Asn1Der.oid(2, 5, 4, 3)
            val oOid = Asn1Der.oid(2, 5, 4, 10)
            val basicConstraintsOid = Asn1Der.oid(2, 5, 29, 19)
            val keyUsageOid = Asn1Der.oid(2, 5, 29, 15)
            val subjectKeyIdentifierOid = Asn1Der.oid(2, 5, 29, 14)
            val authorityKeyIdentifierOid = Asn1Der.oid(2, 5, 29, 35)

            val sigAlg = Asn1Der.sequence(sha256WithRsaOid, Asn1Der.nullValue())

            val issuerAndSubject = Asn1Der.name(
                Asn1Der.rdn(oOid, Asn1Der.utf8String("GuideCast")),
                Asn1Der.rdn(cnOid, Asn1Der.utf8String("GuideCast Local Root CA")),
            )

            val subjectPublicKeyInfo = keyPair.public.encoded
            val rootKeyIdentifier = keyIdentifier(keyPair.public.encoded)

            // BasicConstraints: cA = true, pathLenConstraint = 0 (critical)
            val extBasicConstraints = Asn1Der.sequence(
                basicConstraintsOid,
                Asn1Der.boolean(true), // critical
                Asn1Der.octetString(Asn1Der.sequence(Asn1Der.boolean(true), Asn1Der.integer(0))),
            )

            // KeyUsage: keyCertSign (bit 5), cRLSign (bit 6) -> 00000110 (0x06), unused bits = 1
            val extKeyUsage = Asn1Der.sequence(
                keyUsageOid,
                Asn1Der.boolean(true), // critical
                Asn1Der.octetString(Asn1Der.bitString(byteArrayOf(0x06), unusedBits = 1)),
            )
            val extSubjectKeyIdentifier = Asn1Der.sequence(
                subjectKeyIdentifierOid,
                Asn1Der.octetString(Asn1Der.octetString(rootKeyIdentifier)),
            )
            val extAuthorityKeyIdentifier = Asn1Der.sequence(
                authorityKeyIdentifierOid,
                Asn1Der.octetString(
                    Asn1Der.sequence(Asn1Der.taggedImplicit(0, rootKeyIdentifier)),
                ),
            )

            val extensions = Asn1Der.taggedExplicit(
                3,
                Asn1Der.sequence(
                    extBasicConstraints,
                    extKeyUsage,
                    extSubjectKeyIdentifier,
                    extAuthorityKeyIdentifier,
                ),
            )

            val tbsCertificate = Asn1Der.sequence(
                Asn1Der.taggedExplicit(0, Asn1Der.integer(2)), // v3
                Asn1Der.integer(serialNumber),
                sigAlg,
                issuerAndSubject,
                Asn1Der.sequence(Asn1Der.utcTime(notBefore), Asn1Der.utcTime(notAfter)),
                issuerAndSubject,
                subjectPublicKeyInfo,
                extensions,
            )

            val signature = Signature.getInstance("SHA256withRSA").apply {
                initSign(keyPair.private)
                update(tbsCertificate)
            }.sign()

            val certDer = Asn1Der.sequence(
                tbsCertificate,
                sigAlg,
                Asn1Der.bitString(signature),
            )

            val cert = parseX509(certDer)
            cert.verify(keyPair.public)
            return cert
        }

        private fun positiveSerialNumber(): BigInteger =
            BigInteger(128, random).setBit(0).max(BigInteger.ONE)

        private fun keyIdentifier(subjectPublicKeyInfo: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(subjectPublicKeyInfo)

        private fun parseX509(bytes: ByteArray): X509Certificate {
            val factory = CertificateFactory.getInstance("X.509")
            return factory.generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
        }

    }
}

/**
 * Independent check that the bytes offered by `/ca.der` still identify the installation-local
 * CA advertised to the operator. A mismatch is a warning, not permission to silently stop an
 * otherwise usable local broadcast.
 */
internal data class GuideCastCaFingerprintVerification(
    val expectedFingerprint: String,
    val downloadedCertificateFingerprint: String,
    val matches: Boolean,
) {
    val warning: String?
        get() = if (matches) {
            null
        } else {
            "인증서 지문 불일치: 다운로드 인증서가 이 송출기의 사설 CA와 다릅니다. " +
                "설치하지 말고 방송을 다시 시작하세요."
        }
}

internal fun verifyGuideCastCaDerFingerprint(
    expectedFingerprint: String,
    certificateDer: ByteArray,
): GuideCastCaFingerprintVerification {
    val actualFingerprint = MessageDigest.getInstance("SHA-256")
        .digest(certificateDer)
        .joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
    val normalizedExpected = expectedFingerprint
        .filterNot { it == ':' || it.isWhitespace() }
        .uppercase()
    val normalizedActual = actualFingerprint.replace(":", "")
    return GuideCastCaFingerprintVerification(
        expectedFingerprint = expectedFingerprint,
        downloadedCertificateFingerprint = actualFingerprint,
        matches = normalizedExpected.isNotEmpty() && normalizedExpected == normalizedActual,
    )
}

package app.mcasttalk.windows.host

import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.certificates.KeyType
import io.ktor.network.tls.extensions.HashAlgorithm
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import java.security.KeyPair
import java.security.PrivateKey
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.PosixFilePermissions
import java.util.EnumSet
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.security.auth.x500.X500Principal

/** A workspace-owned certificate. Never changes the OS trust store or firewall. */
internal class LanTls(val keyStore: KeyStore, val password: String) {
    fun clientContext(): SSLContext {
        val trust = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        trust.setCertificateEntry("mcasttalk", keyStore.getCertificateChain("mcasttalk").last())
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        managers.init(trust)
        return SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
    }

    companion object {
        fun openOrCreate(root: Path, hosts: Set<String>): LanTls {
            require(hosts.isNotEmpty() && hosts.all(::isPrivateIpv4))
            val dir = Files.createDirectories(root.resolve("config/tls"))
            val acl = Files.getFileAttributeView(dir, AclFileAttributeView::class.java)
            if (acl != null) {
                acl.acl = listOf(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
                    .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT).build())
            } else Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
            val storeFile = dir.resolve("server.p12")
            val passwordFile = dir.resolve("server-password.txt")
            val password: String
            val store: KeyStore
            if (Files.exists(storeFile)) {
                password = Files.readString(passwordFile).trim()
                store = KeyStore.getInstance("PKCS12").apply {
                    Files.newInputStream(storeFile).use { load(it, password.toCharArray()) }
                }
                val cert = store.getCertificate("mcasttalk") as X509Certificate
                cert.checkValidity()
                val addresses = cert.subjectAlternativeNames.orEmpty().filter { it[0] == 7 }.map { it[1].toString() }
                require(hosts.all { it in addresses }) {
                    "LAN address changed. Existing certificate is preserved; create a new TLS configuration before sharing this address."
                }
            } else {
                require(!Files.exists(passwordFile)) { "Incomplete TLS setup: preserve and inspect config/tls" }
                password = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
                // Generate a real local CA for client trust, but never persist the CA private key.
                // Only this server certificate can be used after setup; renewal requires explicit re-trust.
                val ca = buildKeyStore {
                    certificate("ca") {
                        this.password=password; subject=X500Principal("CN=MCastTalk local meeting CA")
                        keyType=KeyType.CA; hash=HashAlgorithm.SHA256; keySizeInBits=3072; daysValid=366
                        domains=emptyList();ipAddresses=emptyList()
                    }
                }
                val caCert=ca.getCertificate("ca")
                val caKeys=KeyPair(caCert.publicKey,ca.getKey("ca",password.toCharArray()) as PrivateKey)
                store = buildKeyStore {
                    certificate("mcasttalk") {
                        this.password = password
                        hash = HashAlgorithm.SHA256
                        signWith(caKeys, caCert, X500Principal("CN=MCastTalk local meeting CA"))
                        subject = X500Principal("CN=MCastTalk local meeting")
                        domains = listOf("localhost")
                        ipAddresses = (hosts + "127.0.0.1" + "::1").map(InetAddress::getByName)
                        daysValid = 365
                        keySizeInBits = 3072
                    }
                }
                Files.writeString(passwordFile, password, StandardOpenOption.CREATE_NEW)
                Files.newOutputStream(storeFile, StandardOpenOption.CREATE_NEW).use { store.store(it, password.toCharArray()) }
            }
            val cert = store.getCertificateChain("mcasttalk").last()
            val pem = "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.encoded) + "\n-----END CERTIFICATE-----\n"
            Files.writeString(dir.resolve("MCastTalk-LAN.crt"), pem)
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }
            Files.writeString(dir.resolve("certificate-fingerprint.txt"), fingerprint)
            return LanTls(store, password)
        }
    }
}

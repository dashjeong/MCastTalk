package app.guidecast.core.server.crypto

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Lightweight pure Kotlin ASN.1 DER encoder.
 *
 * Produces valid X.509 v3 DER structures for local Root CA and dynamic server leaf
 * certificates without requiring third-party library dependencies (such as BouncyCastle).
 */
internal object Asn1Der {

    fun sequence(vararg elements: ByteArray): ByteArray {
        val totalSize = elements.sumOf { it.size }
        val out = ByteArrayOutputStream(totalSize + 4)
        out.write(0x30)
        writeLength(out, totalSize)
        for (el in elements) out.write(el)
        return out.toByteArray()
    }

    fun sequence(elements: List<ByteArray>): ByteArray = sequence(*elements.toTypedArray())

    fun set(vararg elements: ByteArray): ByteArray {
        val totalSize = elements.sumOf { it.size }
        val out = ByteArrayOutputStream(totalSize + 4)
        out.write(0x31)
        writeLength(out, totalSize)
        for (el in elements) out.write(el)
        return out.toByteArray()
    }

    fun integer(value: BigInteger): ByteArray {
        val bytes = value.toByteArray() // correctly two's complement and minimally sized
        val out = ByteArrayOutputStream(bytes.size + 4)
        out.write(0x02)
        writeLength(out, bytes.size)
        out.write(bytes)
        return out.toByteArray()
    }

    fun integer(value: Long): ByteArray = integer(BigInteger.valueOf(value))

    fun bitString(bytes: ByteArray, unusedBits: Int = 0): ByteArray {
        val out = ByteArrayOutputStream(bytes.size + 5)
        out.write(0x03)
        writeLength(out, bytes.size + 1)
        out.write(unusedBits and 0xFF)
        out.write(bytes)
        return out.toByteArray()
    }

    fun octetString(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size + 4)
        out.write(0x04)
        writeLength(out, bytes.size)
        out.write(bytes)
        return out.toByteArray()
    }

    fun nullValue(): ByteArray = byteArrayOf(0x05, 0x00)

    fun boolean(value: Boolean): ByteArray = byteArrayOf(0x01, 0x01, if (value) 0xFF.toByte() else 0x00)

    fun utf8String(text: String): ByteArray {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val out = ByteArrayOutputStream(bytes.size + 4)
        out.write(0x0C)
        writeLength(out, bytes.size)
        out.write(bytes)
        return out.toByteArray()
    }

    fun printableString(text: String): ByteArray {
        val bytes = text.toByteArray(StandardCharsets.US_ASCII)
        val out = ByteArrayOutputStream(bytes.size + 4)
        out.write(0x13)
        writeLength(out, bytes.size)
        out.write(bytes)
        return out.toByteArray()
    }

    fun ia5String(text: String): ByteArray {
        val bytes = text.toByteArray(StandardCharsets.US_ASCII)
        val out = ByteArrayOutputStream(bytes.size + 4)
        out.write(0x16)
        writeLength(out, bytes.size)
        out.write(bytes)
        return out.toByteArray()
    }

    fun utcTime(date: Date): ByteArray {
        val format = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val text = format.format(date)
        val bytes = text.toByteArray(StandardCharsets.US_ASCII)
        val out = ByteArrayOutputStream(bytes.size + 4)
        out.write(0x17)
        writeLength(out, bytes.size)
        out.write(bytes)
        return out.toByteArray()
    }

    fun taggedExplicit(tagNumber: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(content.size + 4)
        out.write(0xA0 or (tagNumber and 0x1F))
        writeLength(out, content.size)
        out.write(content)
        return out.toByteArray()
    }

    fun taggedImplicit(tagNumber: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(content.size + 4)
        out.write(0x80 or (tagNumber and 0x1F))
        writeLength(out, content.size)
        out.write(content)
        return out.toByteArray()
    }

    fun oid(vararg parts: Int): ByteArray {
        require(parts.size >= 2) { "OID must have at least 2 components" }
        val out = ByteArrayOutputStream()
        out.write(parts[0] * 40 + parts[1])
        for (i in 2 until parts.size) {
            writeOidComponent(out, parts[i])
        }
        val bytes = out.toByteArray()
        val result = ByteArrayOutputStream(bytes.size + 4)
        result.write(0x06)
        writeLength(result, bytes.size)
        result.write(bytes)
        return result.toByteArray()
    }

    fun oid(oidString: String): ByteArray {
        val parts = oidString.split('.').map { it.toInt() }
        return oid(*parts.toIntArray())
    }

    fun rdn(typeOid: ByteArray, value: ByteArray): ByteArray =
        set(sequence(typeOid, value))

    fun name(vararg rdns: ByteArray): ByteArray =
        sequence(*rdns)

    fun generalNameDns(dnsName: String): ByteArray =
        taggedImplicit(2, dnsName.toByteArray(StandardCharsets.US_ASCII))

    fun generalNameIp(ipAddress: InetAddress): ByteArray =
        taggedImplicit(7, ipAddress.address)

    private fun writeOidComponent(out: ByteArrayOutputStream, value: Int) {
        if (value < 128) {
            out.write(value)
        } else {
            val buffer = ByteArray(5)
            var idx = 4
            var v = value
            buffer[idx--] = (v and 0x7F).toByte()
            v = v ushr 7
            while (v > 0) {
                buffer[idx--] = ((v and 0x7F) or 0x80).toByte()
                v = v ushr 7
            }
            out.write(buffer, idx + 1, 4 - idx)
        }
    }

    private fun writeLength(out: ByteArrayOutputStream, length: Int) {
        if (length < 128) {
            out.write(length)
        } else if (length <= 0xFF) {
            out.write(0x81)
            out.write(length)
        } else if (length <= 0xFFFF) {
            out.write(0x82)
            out.write((length ushr 8) and 0xFF)
            out.write(length and 0xFF)
        } else if (length <= 0xFFFFFF) {
            out.write(0x83)
            out.write((length ushr 16) and 0xFF)
            out.write((length ushr 8) and 0xFF)
            out.write(length and 0xFF)
        } else {
            out.write(0x84)
            out.write((length ushr 24) and 0xFF)
            out.write((length ushr 16) and 0xFF)
            out.write((length ushr 8) and 0xFF)
            out.write(length and 0xFF)
        }
    }
}

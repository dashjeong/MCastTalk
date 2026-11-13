package app.guidecast.core.server

import java.io.Closeable
import java.security.SecureRandom
import java.util.Base64

/** 화자 마이크와 같은 보안 쿠키 교환. 초대는 한 번만 쓰며 언어 권한은 서버가 정한다. */
class InterpreterSessions(private val languages: Set<String>, private val now: () -> Long = System::nanoTime) {
    private data class Invite(val value: String, val expires: Long)
    private val invites = mutableMapOf<String, Invite>()
    private val credentials = mutableMapOf<String, String>()
    private val owners = mutableMapOf<String, Any>()
    private val random = SecureRandom()
    private fun secret() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    @Synchronized fun invite(language: String): String? {
        if (language !in languages) return null
        return secret().also { invites[language] = Invite(it, now() + 300_000_000_000L) }
    }
    @Synchronized fun exchange(language: String, invite: String): String? {
        val expected = invites[language] ?: return null
        if (now() > expected.expires || !java.security.MessageDigest.isEqual(expected.value.toByteArray(), invite.toByteArray())) return null
        invites.remove(language)
        // 언어당 최근 초대 1개의 재접속 권한만 보관한다. 기존 연결은 점유를 놓을 때까지 유지한다.
        credentials.entries.removeAll { it.value == language }
        return secret().also { credentials[it] = language }
    }
    @Synchronized fun language(credential: String?): String? = credential?.let(credentials::get)

    @Synchronized fun acquire(language: String): Closeable? {
        if (language !in languages || language in owners) return null
        val owner = Any()
        owners[language] = owner
        return Closeable { synchronized(this) { if (owners[language] === owner) owners.remove(language) } }
    }
}

internal fun interpreterPcmHasVoice(bytes: ByteArray): Boolean {
    var energy = 0.0
    for (i in 0 until bytes.size - 1 step 2) {
        val sample = ((bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)).toShort().toInt()
        energy += sample.toDouble() * sample
    }
    return bytes.size >= 2 && energy / (bytes.size / 2) >= 300.0 * 300.0
}

/** 청크 경계의 마지막 표본과 소수 위치를 보존하는 연속 선형 변환기. */
internal class InterpreterPcmConverter(private val rate: Int) {
    private var tail: Double? = null
    private var phase = 0.0
    init { require(rate in 8_000..48_000) }
    fun convert(bytes: ByteArray): ByteArray {
        require(bytes.isNotEmpty() && bytes.size % 2 == 0)
        if (rate == 16_000) return bytes
        val offset = if (tail == null) 0 else 1
        val input = DoubleArray(bytes.size / 2 + offset)
        if (offset == 1) input[0] = tail!!
        for (i in bytes.indices step 2) input[i / 2 + offset] =
            ((bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)).toShort().toDouble()
        val ratio = 16_000.0 / rate
        val count = kotlin.math.ceil((input.size - 1 - phase) / ratio - 1e-10).toInt().coerceAtLeast(0)
        val output = ByteArray(count * 2)
        for (i in 0 until count) {
            val at = phase + i * ratio
            val left = at.toInt()
            val value = (input[left] + (input[left + 1] - input[left]) * (at - left)).toInt().coerceIn(-32768, 32767)
            output[i * 2] = value.toByte(); output[i * 2 + 1] = (value shr 8).toByte()
        }
        phase = (phase + count * ratio - (input.size - 1)).coerceAtLeast(0.0)
        tail = input.last()
        return output
    }
}

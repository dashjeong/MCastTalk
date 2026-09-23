package app.guidecast.core.server

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

sealed interface BroadcastAccess {
    data object Open : BroadcastAccess
    data object QrToken : BroadcastAccess

    class Pin private constructor(internal val digits: CharArray) : BroadcastAccess {
        companion object {
            fun from(digits: CharArray): Pin {
                require(digits.size in 4..8 && digits.all(Char::isDigit)) {
                    "PIN must contain four to eight digits"
                }
                return Pin(digits.copyOf())
            }
        }
    }
}

/** Independent access policy for the instructor microphone page. */
sealed interface SpeakerAccess {
    data object Open : SpeakerAccess

    class Pin private constructor(internal val digits: CharArray) : SpeakerAccess {
        companion object {
            fun from(digits: CharArray): Pin {
                require(digits.size in 4..8 && digits.all(Char::isDigit)) {
                    "Speaker PIN must contain four to eight digits"
                }
                return Pin(digits.copyOf())
            }
        }
    }
}

enum class BroadcastAccessMode {
    OPEN,
    QR_TOKEN,
    PIN,
}

sealed interface PinJoinResult {
    data class Success(val token: String) : PinJoinResult
    data object Invalid : PinJoinResult
    data object RateLimited : PinJoinResult
}

class BroadcastSessionAuthenticator private constructor(
    val mode: BroadcastAccessMode,
    private val token: String,
    private val speakerToken: String,
    private val pinSalt: ByteArray?,
    private val pinHash: ByteArray?,
    private val speakerPinSalt: ByteArray?,
    private val speakerPinHash: ByteArray?,
) {
    private val limiter = PinAttemptLimiter()
    private val speakerLimiter = PinAttemptLimiter()

    fun tokenForQr(): String? = if (mode == BroadcastAccessMode.QR_TOKEN) token else null

    fun tokenForSpeaker(): String = speakerToken

    val speakerRequiresPin: Boolean
        get() = speakerPinSalt != null && speakerPinHash != null

    fun authorize(candidate: String?): Boolean = when (mode) {
        BroadcastAccessMode.OPEN -> true
        BroadcastAccessMode.QR_TOKEN,
        BroadcastAccessMode.PIN,
        -> candidate != null && constantTimeEquals(token, candidate)
    }

    fun authorizeSpeaker(candidate: String?): Boolean =
        candidate != null && constantTimeEquals(speakerToken, candidate)

    fun joinSpeaker(candidate: CharArray, remoteKey: String): PinJoinResult {
        if (!speakerRequiresPin) {
            candidate.fill('\u0000')
            return PinJoinResult.Success(speakerToken)
        }
        if (!speakerLimiter.mayAttempt(remoteKey)) {
            candidate.fill('\u0000')
            return PinJoinResult.RateLimited
        }

        val matches = MessageDigest.isEqual(
            requireNotNull(speakerPinHash),
            hashPin(candidate, requireNotNull(speakerPinSalt)),
        )
        candidate.fill('\u0000')
        return if (matches) {
            speakerLimiter.recordSuccess(remoteKey)
            PinJoinResult.Success(speakerToken)
        } else {
            speakerLimiter.recordFailure(remoteKey)
            PinJoinResult.Invalid
        }
    }

    fun joinWithPin(candidate: CharArray, remoteKey: String): PinJoinResult {
        if (mode != BroadcastAccessMode.PIN || pinSalt == null || pinHash == null) {
            return PinJoinResult.Invalid
        }
        if (!limiter.mayAttempt(remoteKey)) {
            candidate.fill('\u0000')
            return PinJoinResult.RateLimited
        }

        val matches = MessageDigest.isEqual(pinHash, hashPin(candidate, pinSalt))
        candidate.fill('\u0000')
        return if (matches) {
            limiter.recordSuccess(remoteKey)
            PinJoinResult.Success(token)
        } else {
            limiter.recordFailure(remoteKey)
            PinJoinResult.Invalid
        }
    }

    companion object {
        private val random = SecureRandom()

        fun create(
            access: BroadcastAccess,
            speakerAccess: SpeakerAccess = SpeakerAccess.Open,
        ): BroadcastSessionAuthenticator {
            val token = randomBytes(24).toBase64Url()
            val speakerToken = randomBytes(32).toBase64Url()
            val (mode, pinSalt, pinHash) = when (access) {
                BroadcastAccess.Open -> Triple(BroadcastAccessMode.OPEN, null, null)
                BroadcastAccess.QrToken -> Triple(BroadcastAccessMode.QR_TOKEN, null, null)
                is BroadcastAccess.Pin -> {
                    val salt = randomBytes(16)
                    val hash = hashPin(access.digits, salt)
                    access.digits.fill('\u0000')
                    Triple(BroadcastAccessMode.PIN, salt, hash)
                }
            }
            val (speakerPinSalt, speakerPinHash) = when (speakerAccess) {
                SpeakerAccess.Open -> null to null
                is SpeakerAccess.Pin -> {
                    val salt = randomBytes(16)
                    val hash = hashPin(speakerAccess.digits, salt)
                    speakerAccess.digits.fill('\u0000')
                    salt to hash
                }
            }
            return BroadcastSessionAuthenticator(
                mode = mode,
                token = token,
                speakerToken = speakerToken,
                pinSalt = pinSalt,
                pinHash = pinHash,
                speakerPinSalt = speakerPinSalt,
                speakerPinHash = speakerPinHash,
            )
        }

        private fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)

        private fun ByteArray.toBase64Url(): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(this)

        private fun constantTimeEquals(expected: String, actual: String): Boolean =
            MessageDigest.isEqual(
                expected.toByteArray(StandardCharsets.US_ASCII),
                actual.toByteArray(StandardCharsets.US_ASCII),
            )

        private fun hashPin(pin: CharArray, salt: ByteArray): ByteArray {
            val encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(pin))
            val bytes = ByteArray(encoded.remaining())
            encoded.get(bytes)
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(salt)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            val result = digest.digest(bytes)
            bytes.fill(0)
            return result
        }
    }
}

internal class PinAttemptLimiter(
    private val maxFailures: Int = 5,
    private val windowMillis: Long = 60_000,
    private val blockMillis: Long = 300_000,
    private val maxTrackedRemotes: Int = 256,
) {
    private data class Attempts(
        var windowStartedAt: Long,
        var failures: Int,
        var blockedUntil: Long,
    )

    private val attemptsByRemote = mutableMapOf<String, Attempts>()

    init {
        require(maxFailures > 0)
        require(windowMillis > 0L)
        require(blockMillis >= windowMillis)
        require(maxTrackedRemotes > 0)
    }

    @Synchronized
    fun mayAttempt(remoteKey: String, now: Long = System.currentTimeMillis()): Boolean {
        val attempts = attemptsByRemote[remoteKey]
        if (attempts == null) return hasCapacityForNewRemote(now)
        if (now < attempts.blockedUntil) return false
        if (now - attempts.windowStartedAt >= windowMillis) {
            attemptsByRemote.remove(remoteKey)
            return true
        }
        return attempts.failures < maxFailures
    }

    @Synchronized
    fun recordFailure(remoteKey: String, now: Long = System.currentTimeMillis()) {
        val attempts = attemptsByRemote[remoteKey] ?: run {
            // Never evict a still-limited peer: that would reset its failure history. When all
            // slots are occupied, unknown peers stay rate-limited until an existing record truly
            // expires, keeping both memory and brute-force exposure bounded.
            if (!hasCapacityForNewRemote(now)) return
            Attempts(now, 0, 0).also { attemptsByRemote[remoteKey] = it }
        }
        if (now - attempts.windowStartedAt >= windowMillis) {
            attempts.windowStartedAt = now
            attempts.failures = 0
        }
        attempts.failures += 1
        if (attempts.failures >= maxFailures) attempts.blockedUntil = now + blockMillis
    }

    @Synchronized
    fun recordSuccess(remoteKey: String) {
        attemptsByRemote.remove(remoteKey)
    }

    @Synchronized
    internal fun trackedRemoteCount(): Int = attemptsByRemote.size

    private fun hasCapacityForNewRemote(now: Long): Boolean {
        if (attemptsByRemote.size < maxTrackedRemotes) return true
        pruneExpired(now)
        return attemptsByRemote.size < maxTrackedRemotes
    }

    private fun pruneExpired(now: Long) {
        attemptsByRemote.entries.removeAll {
            now >= it.value.blockedUntil && now - it.value.windowStartedAt >= windowMillis
        }
    }
}

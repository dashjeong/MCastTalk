package app.mcasttalk.windows.host

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Semaphore
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class AccountOperationBusyException : IllegalStateException("Account operation is temporarily unavailable")

internal class PasswordHash(val salt: ByteArray, val derived: ByteArray) {
    override fun toString(): String = "PasswordHash[redacted]"
}

internal object PasswordHasher {
    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 128
    // OWASP Password Storage Cheat Sheet, PBKDF2-HMAC-SHA256 (checked 2026-09-19).
    // This JDK implementation is not a claim of FIPS certification.
    const val ITERATIONS = 600_000
    const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private val random = SecureRandom()
    // Process-wide, no unbounded queue of expensive requests (including administrative resets).
    private val slots = Semaphore(2)
    private val dummy = PasswordHash(ByteArray(16).also(random::nextBytes), ByteArray(32).also(random::nextBytes))

    fun validate(password: CharArray) {
        require(password.size in MIN_LENGTH..MAX_LENGTH) { "Password must be $MIN_LENGTH-$MAX_LENGTH characters" }
    }

    fun hash(password: CharArray): PasswordHash {
        validate(password)
        val salt = ByteArray(16).also(random::nextBytes)
        return PasswordHash(salt, derive(password, salt))
    }

    fun verify(password: CharArray, hash: PasswordHash?): Boolean {
        validate(password)
        val expected = hash ?: dummy
        val actual = derive(password, expected.salt)
        return try {
            val matches = MessageDigest.isEqual(expected.derived, actual)
            hash != null && matches
        } finally { actual.fill(0) }
    }

    private fun derive(password: CharArray, salt: ByteArray): ByteArray {
        if (!slots.tryAcquire()) throw AccountOperationBusyException()
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded }
        finally { spec.clearPassword(); slots.release() }
    }
}

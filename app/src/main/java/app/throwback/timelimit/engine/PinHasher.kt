package app.throwback.timelimit.engine

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Salted PBKDF2-HMAC-SHA256 hashing for the 4-digit PIN (DECISIONS.md #24). Only the salt and
 * the hash are kept; the PIN is never stored or logged. Pure JVM (javax.crypto), slow on purpose:
 * call it off the main thread.
 *
 * A 4-digit PIN can't resist an offline attack whatever the iteration count; the hash keeps it
 * from being read straight out of a backup, and the throttling in TimeLimitEngine slows guessing.
 */
class PinHasher(
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val random: SecureRandom = SecureRandom(),
) {
    fun hash(pin: String): PinHash {
        require(isValidPin(pin)) { "PIN must be ${Rules.PIN_LENGTH} digits" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return PinHash(
            salt = b64.encodeToString(salt),
            hash = b64.encodeToString(derive(pin, salt, iterations)),
            iterations = iterations,
        )
    }

    /** Constant-time comparison against [stored], using the iteration count it was made with. */
    fun verify(pin: String, stored: PinHash): Boolean {
        if (!isValidPin(pin)) return false
        val salt = runCatching { Base64.getDecoder().decode(stored.salt) }.getOrNull() ?: return false
        val expected = runCatching { Base64.getDecoder().decode(stored.hash) }.getOrNull() ?: return false
        if (stored.iterations <= 0) return false
        return MessageDigest.isEqual(derive(pin, salt, stored.iterations), expected)
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val chars = pin.toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    companion object {
        const val ALGORITHM = "PBKDF2WithHmacSHA256"
        const val DEFAULT_ITERATIONS = 120_000
        const val SALT_BYTES = 16
        private const val KEY_BITS = 256
        private val b64 = Base64.getEncoder()

        fun isValidPin(pin: String): Boolean = pin.length == Rules.PIN_LENGTH && pin.all { it in '0'..'9' }
    }
}

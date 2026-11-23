package com.mhm.curbstomp.domain

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Pure password hashing & constant-time verification utility.
 *
 * - New hashes use salted `PBKDF2WithHmacSHA256` (`pbkdf2_sha256$<iterations>$<saltHex>$<hashHex>`).
 * - Legacy unsalted 64-char hex SHA-256 hashes from v1.1.0 remain verifiable and are flagged by
 *   [needsUpgrade] so callers can transparently migrate them after a successful password check.
 */
object PasswordHasher {

    private const val PBKDF2_PREFIX = "pbkdf2_sha256"
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val DEFAULT_ITERATIONS = 100_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256

    private val secureRandom = SecureRandom()

    fun hash(password: String, iterations: Int = DEFAULT_ITERATIONS): String {
        val salt = ByteArray(SALT_BYTES)
        secureRandom.nextBytes(salt)
        val derived = pbkdf2(password.toCharArray(), salt, iterations)
        return "$PBKDF2_PREFIX\$$iterations\$${toHex(salt)}\$${toHex(derived)}"
    }

    fun verify(password: String, storedHash: String): Boolean {
        if (storedHash.isEmpty()) return false
        return if (storedHash.startsWith("$PBKDF2_PREFIX$")) {
            verifyPbkdf2(password, storedHash)
        } else {
            verifyLegacySha256(password, storedHash)
        }
    }

    /**
     * Returns true when [storedHash] uses the legacy unsalted SHA-256 format and should be
     * re-hashed with salted PBKDF2 on the next successful verification.
     */
    fun needsUpgrade(storedHash: String): Boolean {
        return !storedHash.startsWith("$PBKDF2_PREFIX$")
    }

    private fun verifyPbkdf2(password: String, storedHash: String): Boolean {
        val parts = storedHash.split('$')
        if (parts.size != 4 || parts[0] != PBKDF2_PREFIX) return false
        val iterations = parts[1].toIntOrNull() ?: return false
        if (iterations <= 0) return false
        val salt = fromHex(parts[2]) ?: return false
        val expected = fromHex(parts[3]) ?: return false
        val actual = pbkdf2(password.toCharArray(), salt, iterations)
        return MessageDigest.isEqual(actual, expected)
    }

    private fun verifyLegacySha256(password: String, storedHash: String): Boolean {
        val expected = fromHex(storedHash) ?: return false
        val digest = MessageDigest.getInstance("SHA-256")
        val actual = digest.digest(password.toByteArray(Charsets.UTF_8))
        return MessageDigest.isEqual(actual, expected)
    }

    private fun pbkdf2(chars: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(chars, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun toHex(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef"
        val result = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            result[i * 2] = hexChars[v ushr 4]
            result[i * 2 + 1] = hexChars[v and 0x0F]
        }
        return String(result)
    }

    private fun fromHex(hex: String): ByteArray? {
        if (hex.length % 2 != 0 || hex.isEmpty()) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = hexDigit(hex[i * 2])
            val lo = hexDigit(hex[i * 2 + 1])
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}

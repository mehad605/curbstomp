package com.mhm.curbstomp.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Locale

class PasswordHasherTest {

    @Test
    fun pbkdf2HashesWithUniqueRandomSaltsAndVerifiesCorrectly() {
        val hash1 = PasswordHasher.hash("my-secret-pass", iterations = 1_000)
        val hash2 = PasswordHasher.hash("my-secret-pass", iterations = 1_000)

        assertNotEquals(hash1, hash2)
        assertTrue(hash1.startsWith("pbkdf2_sha256\$1000\$"))
        assertFalse(PasswordHasher.needsUpgrade(hash1))

        assertTrue(PasswordHasher.verify("my-secret-pass", hash1))
        assertTrue(PasswordHasher.verify("my-secret-pass", hash2))
        assertFalse(PasswordHasher.verify("wrong-pass", hash1))
    }

    @Test
    fun legacyUnsaltedSha256VerifiesAndFlagsNeedsUpgrade() {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("legacy-pass".toByteArray(Charsets.UTF_8))
        val legacyHex = digest.joinToString("") { String.format(Locale.US, "%02x", it) }

        assertTrue(PasswordHasher.needsUpgrade(legacyHex))
        assertTrue(PasswordHasher.verify("legacy-pass", legacyHex))
        assertFalse(PasswordHasher.verify("other-pass", legacyHex))
    }

    @Test
    fun rejectsEmptyOrMalformedStoredHashes() {
        assertFalse(PasswordHasher.verify("anything", ""))
        assertFalse(PasswordHasher.verify("anything", "not-hex"))
        assertFalse(PasswordHasher.verify("anything", "pbkdf2_sha256\$0\$aa\$bb"))
        assertFalse(PasswordHasher.verify("anything", "pbkdf2_sha256\$1000\$zz\$bb"))
        assertFalse(PasswordHasher.verify("anything", "pbkdf2_sha256\$1000\$onlythree"))
    }
}

package com.mhm.curbstomp.ui.common

import android.content.Context
import android.widget.Button
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.mhm.curbstomp.Curbstomp
import com.mhm.curbstomp.R
import com.mhm.curbstomp.domain.PasswordHasher
import com.mhm.curbstomp.ui.activity.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PasswordDialogRobolectricTest {

    @Before
    fun clearPersistedLockout() {
        PasswordDialog.resetInMemoryLockout()
        val app = ApplicationProvider.getApplicationContext<Curbstomp>()
        app.getSharedPreferences("curbstomp_lockout", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun legacySha256(password: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun awaitCondition(timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (predicate()) return
            Thread.sleep(20L)
        }
        ShadowLooper.idleMainLooper()
        assertTrue("Condition not met within ${timeoutMs}ms", predicate())
    }

    @Test
    fun legacySha256PasswordVerifiesAndAutoUpgradesToPbkdf2() {
        val app = ApplicationProvider.getApplicationContext<Curbstomp>()
        val repo = app.container.settingsRepository
        val legacyHash = legacySha256("hunter2")
        runBlocking { repo.updatePasswordHash(legacyHash) }

        val unlocked = AtomicBoolean(false)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.lifecycleScope.launch {
                    PasswordDialog.verifyPassword(activity, repo) {
                        unlocked.set(true)
                    }
                }
            }

            awaitCondition { ShadowDialog.getLatestDialog()?.isShowing == true }
            val dialog = ShadowDialog.getLatestDialog()!!
            dialog.findViewById<TextInputEditText>(R.id.etPassword).setText("hunter2")
            dialog.findViewById<Button>(R.id.btnConfirm).performClick()

            awaitCondition { unlocked.get() }
        }

        val storedHash = runBlocking { repo.settings.first().passwordHash }
        assertNotNull(storedHash)
        assertTrue("Expected PBKDF2 upgrade, got: $storedHash", storedHash!!.startsWith("pbkdf2_sha256$"))
        assertTrue(PasswordHasher.verify("hunter2", storedHash))
    }

    @Test
    fun threeFailedAttemptsPersistLockoutAndBlockFourthAttempt() {
        val app = ApplicationProvider.getApplicationContext<Curbstomp>()
        val repo = app.container.settingsRepository
        runBlocking { repo.updatePasswordHash(PasswordHasher.hash("correct-horse")) }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.lifecycleScope.launch {
                    PasswordDialog.verifyPassword(activity, repo) {}
                }
            }

            awaitCondition { ShadowDialog.getLatestDialog()?.isShowing == true }
            val dialog = ShadowDialog.getLatestDialog()!!
            val etPassword = dialog.findViewById<TextInputEditText>(R.id.etPassword)
            val tilPassword = dialog.findViewById<TextInputLayout>(R.id.tilPassword)
            val btnConfirm = dialog.findViewById<Button>(R.id.btnConfirm)

            repeat(3) {
                etPassword.setText("wrong-guess")
                btnConfirm.performClick()
                awaitCondition { btnConfirm.isEnabled && tilPassword.error != null }
            }

            val prefs = app.getSharedPreferences("curbstomp_lockout", Context.MODE_PRIVATE)
            assertEquals(3, prefs.getInt("failed_attempts", 0))
            assertTrue(prefs.getLong("lockout_until_wall_ms", 0L) > System.currentTimeMillis())

            // 4th immediate attempt is rejected synchronously with lockout error
            etPassword.setText("correct-horse")
            btnConfirm.performClick()
            assertTrue(tilPassword.error.toString().contains("Too many attempts"))
        }
    }
}

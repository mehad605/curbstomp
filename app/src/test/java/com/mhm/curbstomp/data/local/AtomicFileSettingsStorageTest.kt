package com.mhm.curbstomp.data.local

import androidx.test.core.app.ApplicationProvider
import com.mhm.curbstomp.Curbstomp
import com.mhm.curbstomp.data.models.AppLimit
import com.mhm.curbstomp.data.models.InstantFocus
import com.mhm.curbstomp.data.models.Rule
import com.mhm.curbstomp.data.models.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AtomicFileSettingsStorageTest {

    @Test
    fun writesReadsOverwritesAndRecoversFromCorruptionUsingAtomicFile() {
        val app = ApplicationProvider.getApplicationContext<Curbstomp>()
        val target = File(app.filesDir, "robolectric_datastore/settings_${System.nanoTime()}.json")
        try {
            val storage = AtomicFileSettingsStorage(target)

            // Missing file returns defaults
            assertEquals(Settings(), storage.read())

            // Write creates parent directory and persists full schema
            val initial = Settings(
                passwordHash = "pbkdf2_sha256\$120000\$abc\$def",
                antiUninstallEnabled = true,
                extraHardenEnabled = true,
                deviceAdminActivationRequestedAt = 123_456L,
                rules = listOf(
                    Rule(
                        id = "r1",
                        name = "Work",
                        startTime = "09:00",
                        endTime = "17:00",
                        daysOfWeek = listOf(Calendar.MONDAY, Calendar.WEDNESDAY),
                        appLimits = listOf(AppLimit("com.example.social", 900_000L)),
                        isWhitelist = false,
                        isActive = true
                    )
                ),
                instantFocusSessions = listOf(
                    InstantFocus(
                        id = "f1",
                        startTimeMillis = 1000L,
                        endTimeMillis = 2000L,
                        appLimits = listOf(AppLimit("com.example.game", 0L)),
                        isActive = true,
                        isWhitelist = true,
                        isPaused = true,
                        remainingMillis = 500L
                    )
                ),
                appUsageMillis = mapOf("r1_20260316_com.example.social" to 60_000L)
            )
            storage.write(initial)
            assertTrue(target.exists())
            assertEquals(initial, storage.read())

            // Overwrite replaces previous contents atomically
            val updated = initial.copy(extraHardenEnabled = false)
            storage.write(updated)
            assertEquals(updated, storage.read())

            // Corrupt JSON falls back cleanly to default Settings
            target.writeText("{not-valid-json")
            assertEquals(Settings(), storage.read())
        } finally {
            target.parentFile?.deleteRecursively()
        }
    }
}

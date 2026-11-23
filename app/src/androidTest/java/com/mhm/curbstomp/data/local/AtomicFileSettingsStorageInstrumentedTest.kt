package com.mhm.curbstomp.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mhm.curbstomp.data.models.AppLimit
import com.mhm.curbstomp.data.models.Rule
import com.mhm.curbstomp.data.models.Settings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Calendar

@RunWith(AndroidJUnit4::class)
class AtomicFileSettingsStorageInstrumentedTest {

    @Test
    fun roundTripsAndRecoversFromCorruptJsonOnAndroidFilesystem() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testFile = File(context.cacheDir, "test_settings_${System.nanoTime()}.json")
        try {
            val storage = AtomicFileSettingsStorage(testFile)

            // Missing file returns default Settings
            assertEquals(Settings(), storage.read())

            // Write and read back full settings
            val expected = Settings(
                passwordHash = "pbkdf2_sha256\$120000\$salt\$hash",
                antiUninstallEnabled = true,
                extraHardenEnabled = true,
                rules = listOf(
                    Rule(
                        id = "r1",
                        name = "Deep Work",
                        startTime = "09:00",
                        endTime = "17:00",
                        daysOfWeek = listOf(Calendar.MONDAY, Calendar.TUESDAY),
                        appLimits = listOf(AppLimit("com.example.social", 600_000L)),
                        isWhitelist = false,
                        isActive = true
                    )
                ),
                appUsageMillis = mapOf("r1_20260316_com.example.social" to 120_000L)
            )
            storage.write(expected)
            assertEquals(expected, storage.read())

            // Corrupt JSON gracefully falls back to defaults without crashing
            testFile.writeText("{corrupt-json")
            assertEquals(Settings(), storage.read())
        } finally {
            testFile.delete()
        }
    }
}

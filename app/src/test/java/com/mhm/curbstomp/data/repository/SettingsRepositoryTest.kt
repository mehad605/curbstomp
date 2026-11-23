package com.mhm.curbstomp.data.repository

import com.mhm.curbstomp.data.local.SettingsJson
import com.mhm.curbstomp.data.local.SettingsStorage
import com.mhm.curbstomp.data.local.SettingsStore
import com.mhm.curbstomp.data.models.AppLimit
import com.mhm.curbstomp.data.models.InstantFocus
import com.mhm.curbstomp.data.models.Rule
import com.mhm.curbstomp.data.models.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class SettingsRepositoryTest {

    private class InMemoryJsonStorage(initial: Settings = Settings()) : SettingsStorage {
        private var rawJson: String = SettingsJson.toJson(initial)
        var writeCount: Int = 0
            private set

        override fun read(): Settings = SettingsJson.fromJson(rawJson)

        override fun write(settings: Settings) {
            writeCount++
            rawJson = SettingsJson.toJson(settings)
        }
    }

    private fun createRepo(initial: Settings = Settings()): Pair<SettingsRepository, InMemoryJsonStorage> {
        val storage = InMemoryJsonStorage(initial)
        val store = SettingsStore(storage, ioDispatcher = Dispatchers.Unconfined)
        return SettingsRepository(store) to storage
    }

    @Test
    fun upsertAndDeleteRuleUpdatesStateAndPrunesOrphanUsage() = runBlocking {
        val staleUsage = mapOf(
            "deleted-rule_20261123_com.example.app" to 60_000L,
            "r1_20200101_com.example.app" to 60_000L
        )
        val (repo, storage) = createRepo(Settings(appUsageMillis = staleUsage))

        val rule = Rule(
            id = "r1",
            name = "Work",
            startTime = "09:00",
            endTime = "17:00",
            daysOfWeek = listOf(Calendar.MONDAY),
            appLimits = listOf(AppLimit("com.example.app", 0L)),
            isActive = true
        )
        repo.upsertRule(rule)

        val afterUpsert = repo.settings.first()
        assertEquals(1, afterUpsert.rules.size)
        assertEquals("Work", afterUpsert.rules[0].name)
        assertTrue(afterUpsert.appUsageMillis.isEmpty())

        // Updating existing rule replaces in place
        repo.upsertRule(rule.copy(name = "Deep Work"))
        assertEquals("Deep Work", repo.settings.first().rules[0].name)
        assertEquals(1, repo.settings.first().rules.size)

        // Toggling active state
        repo.setRuleActive("r1", false)
        assertFalse(repo.settings.first().rules[0].isActive)

        // No-op toggle skips redundant disk write
        val writesBeforeNoOp = storage.writeCount
        repo.setRuleActive("r1", false)
        assertEquals(writesBeforeNoOp, storage.writeCount)

        // Deleting rule removes it cleanly
        repo.deleteRule("r1")
        assertTrue(repo.settings.first().rules.isEmpty())
    }

    @Test
    fun instantFocusLifecyclePauseResumeAddTimeEditAppsStop() = runBlocking {
        val (repo, _) = createRepo()
        val focus = InstantFocus(
            id = "f1",
            startTimeMillis = 1_000_000L,
            endTimeMillis = 1_000_000L + 30 * 60_000L,
            appLimits = listOf(AppLimit("com.a", 0L)),
            isActive = true,
            isWhitelist = false
        )

        repo.upsertInstantFocus(focus)

        // Pause 10 minutes in -> 20 minutes remaining
        repo.pauseInstantFocus("f1", nowMillis = 1_000_000L + 10 * 60_000L)
        val paused = repo.settings.first().instantFocusSessions[0]
        assertTrue(paused.isPaused)
        assertEquals(20 * 60_000L, paused.remainingMillis)

        // Add 15 minutes while paused -> 35 minutes remaining
        repo.addInstantFocusTime("f1", 15L)
        val extendedPaused = repo.settings.first().instantFocusSessions[0]
        assertEquals(35 * 60_000L, extendedPaused.remainingMillis)

        // Resume at t = 2_000_000L -> endTime = 2_000_000L + 35 min
        repo.resumeInstantFocus("f1", nowMillis = 2_000_000L)
        val resumed = repo.settings.first().instantFocusSessions[0]
        assertFalse(resumed.isPaused)
        assertEquals(2_000_000L + 35 * 60_000L, resumed.endTimeMillis)

        // Add 10 minutes while running -> endTime += 10 min
        repo.addInstantFocusTime("f1", 10L)
        assertEquals(2_000_000L + 45 * 60_000L, repo.settings.first().instantFocusSessions[0].endTimeMillis)

        // Update focus apps
        repo.updateInstantFocusApps("f1", listOf("com.a", "com.b"))
        assertEquals(
            listOf("com.a", "com.b"),
            repo.settings.first().instantFocusSessions[0].appLimits.map { it.packageName }
        )

        // Stop focus
        repo.stopInstantFocus("f1", nowMillis = 2_100_000L)
        val stopped = repo.settings.first().instantFocusSessions[0]
        assertFalse(stopped.isActive)
        assertFalse(stopped.isPaused)
        assertEquals(2_100_000L, stopped.endTimeMillis)
    }

    @Test
    fun securityTogglesAndPasswordHashPersistCorrectly() = runBlocking {
        val (repo, _) = createRepo()

        repo.updatePasswordHash("hash-v1")
        repo.setAntiUninstallEnabled(true)
        repo.setExtraHardenEnabled(true)
        repo.markDeviceAdminActivationRequested(123_456L)

        val settings = repo.settings.first()
        assertEquals("hash-v1", settings.passwordHash)
        assertTrue(settings.antiUninstallEnabled)
        assertTrue(settings.extraHardenEnabled)
        assertEquals(123_456L, settings.deviceAdminActivationRequestedAt)
    }
}

package com.mhm.curbstomp.utils

import com.mhm.curbstomp.data.models.AppLimit
import com.mhm.curbstomp.data.models.InstantFocus
import com.mhm.curbstomp.data.models.Rule
import com.mhm.curbstomp.data.models.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class RuleEngineTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun utcMillis(year: Int, monthOneBased: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Long {
        val cal = Calendar.getInstance(utc)
        cal.clear()
        cal.set(year, monthOneBased - 1, day, hour, minute, second)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    @Test
    fun parseTimeToMinutesHandlesValidAndInvalidInputs() {
        assertEquals(0, RuleEngine.parseTimeToMinutes("00:00"))
        assertEquals(9 * 60 + 30, RuleEngine.parseTimeToMinutes("09:30"))
        assertEquals(23 * 60 + 59, RuleEngine.parseTimeToMinutes("23:59"))
        assertNull(RuleEngine.parseTimeToMinutes("24:00"))
        assertNull(RuleEngine.parseTimeToMinutes("12:60"))
        assertNull(RuleEngine.parseTimeToMinutes("bad"))
    }

    @Test
    fun nonLatinDefaultLocaleStillProducesAsciiKeysAndEvaluatesRules() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ar", "EG"))
            // 2026-03-16 is Monday
            val now = utcMillis(2026, 3, 16, 10, 15)
            val (today, yesterday) = RuleEngine.dateKeysFor(now, utc)
            assertEquals("20260316", today)
            assertEquals("20260315", yesterday)

            val rule = Rule(
                id = "r1",
                name = "Work",
                startTime = "09:00",
                endTime = "17:00",
                daysOfWeek = listOf(Calendar.MONDAY),
                appLimits = listOf(AppLimit("com.example.social", 0L)),
                isWhitelist = false,
                isActive = true
            )
            val res = RuleEngine.evaluatePackage(
                settings = Settings(rules = listOf(rule)),
                packageName = "com.example.social",
                nowMillis = now,
                timeZone = utc
            )
            assertTrue(res.blocked)
            assertEquals("Rule Work: App Blocked!", res.blockReason)
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun pausedInstantFocusDoesNotBlockAndIsNotPruned() {
        val now = utcMillis(2026, 3, 16, 12, 0)
        val pausedFocus = InstantFocus(
            id = "f-paused",
            startTimeMillis = now - 600_000L,
            endTimeMillis = now - 60_000L, // wall-clock passed endTime while paused
            appLimits = listOf(AppLimit("com.example.yt", 0L)),
            isActive = true,
            isWhitelist = false,
            isPaused = true,
            remainingMillis = 300_000L
        )
        val expiredActiveFocus = InstantFocus(
            id = "f-expired",
            startTimeMillis = now - 600_000L,
            endTimeMillis = now - 1_000L,
            appLimits = listOf(AppLimit("com.example.yt", 0L)),
            isActive = true,
            isWhitelist = false,
            isPaused = false,
            remainingMillis = 0L
        )

        val pruned = RuleEngine.pruneExpiredInstantFocus(listOf(pausedFocus, expiredActiveFocus), now)
        assertEquals(1, pruned.size)
        assertEquals("f-paused", pruned[0].id)

        // Paused session must NOT block even if within start..end
        val pausedInWindow = pausedFocus.copy(endTimeMillis = now + 300_000L)
        val eval = RuleEngine.evaluatePackage(
            settings = Settings(instantFocusSessions = listOf(pausedInWindow)),
            packageName = "com.example.yt",
            nowMillis = now,
            timeZone = utc
        )
        assertFalse(eval.blocked)
        assertNotNull(RuleEngine.findDisplayInstantFocus(listOf(pausedFocus), now))
    }

    @Test
    fun activeInstantFocusBlocksBlacklistAndWhitelistCorrectly() {
        val now = utcMillis(2026, 3, 16, 12, 0)
        val whitelistFocus = InstantFocus(
            id = "f-wl",
            startTimeMillis = now - 60_000L,
            endTimeMillis = now + 600_000L,
            appLimits = listOf(AppLimit("com.example.allowed", 0L)),
            isActive = true,
            isWhitelist = true,
            isPaused = false
        )
        val settings = Settings(instantFocusSessions = listOf(whitelistFocus))
        assertFalse(RuleEngine.evaluatePackage(settings, "com.example.allowed", now, timeZone = utc).blocked)
        val blocked = RuleEngine.evaluatePackage(settings, "com.example.other", now, timeZone = utc)
        assertTrue(blocked.blocked)
        assertEquals("Instant Focus: App Not Allowed!", blocked.blockReason)
    }

    @Test
    fun crossMidnightRuleChecksPrevDayAndUsesYesterdayUsageKey() {
        val rule = Rule(
            id = "night",
            name = "Sleep",
            startTime = "22:00",
            endTime = "06:00",
            daysOfWeek = listOf(Calendar.MONDAY),
            appLimits = listOf(AppLimit("com.example.game", 60_000L)),
            isWhitelist = false,
            isActive = true
        )
        // Monday 23:00 -> active with Monday's date key 20260316
        val mondayNight = utcMillis(2026, 3, 16, 23, 0)
        val res1 = RuleEngine.evaluatePackage(
            settings = Settings(rules = listOf(rule)),
            packageName = "com.example.game",
            nowMillis = mondayNight,
            addUsageMillis = 30_000L,
            timeZone = utc
        )
        assertFalse(res1.blocked)
        assertEquals(30_000L, res1.updatedUsageMap?.get("night_20260316_com.example.game"))
        assertEquals(30_000L, res1.nextWakeDelayMillis)

        // Tuesday 02:00 -> still active because Monday (prevDay) is in daysOfWeek; shares 20260316 key!
        val tuesdayEarly = utcMillis(2026, 3, 17, 2, 0)
        val res2 = RuleEngine.evaluatePackage(
            settings = Settings(rules = listOf(rule), appUsageMillis = res1.updatedUsageMap!!),
            packageName = "com.example.game",
            nowMillis = tuesdayEarly,
            addUsageMillis = 30_000L,
            timeZone = utc
        )
        assertTrue(res2.blocked)
        assertEquals("Rule Sleep: App Blocked!", res2.blockReason)
        assertEquals(60_000L, res2.updatedUsageMap?.get("night_20260316_com.example.game"))

        // Tuesday 23:00 -> NOT active because Tuesday is not in daysOfWeek
        val tuesdayNight = utcMillis(2026, 3, 17, 23, 0)
        val res3 = RuleEngine.evaluatePackage(
            settings = Settings(rules = listOf(rule)),
            packageName = "com.example.game",
            nowMillis = tuesdayNight,
            timeZone = utc
        )
        assertFalse(res3.blocked)
        assertFalse(res3.hasTrackedLimit)
    }

    @Test
    fun twentyFourHourRuleIsActiveAllDayOnMatchingDay() {
        val rule = Rule(
            id = "allday",
            name = "Detox",
            startTime = "00:00",
            endTime = "00:00",
            daysOfWeek = listOf(Calendar.SUNDAY),
            appLimits = listOf(AppLimit("com.example.social", 0L)),
            isWhitelist = false,
            isActive = true
        )
        val sunday = utcMillis(2026, 3, 15, 14, 30)
        val monday = utcMillis(2026, 3, 16, 14, 30)
        assertTrue(RuleEngine.evaluatePackage(Settings(rules = listOf(rule)), "com.example.social", sunday, timeZone = utc).blocked)
        assertFalse(RuleEngine.evaluatePackage(Settings(rules = listOf(rule)), "com.example.social", monday, timeZone = utc).blocked)
    }

    @Test
    fun nextWakeDelayComputesExactUpcomingRuleStartAndNullWhenUnaffected() {
        val rule = Rule(
            id = "focus",
            name = "Morning",
            startTime = "09:00",
            endTime = "11:00",
            daysOfWeek = listOf(Calendar.MONDAY),
            appLimits = listOf(AppLimit("com.example.social", 0L)),
            isWhitelist = false,
            isActive = true
        )
        val monday830 = utcMillis(2026, 3, 16, 8, 30, 0)
        val settings = Settings(rules = listOf(rule))

        val affected = RuleEngine.evaluatePackage(settings, "com.example.social", monday830, timeZone = utc)
        assertFalse(affected.blocked)
        assertEquals(30 * 60_000L, affected.nextWakeDelayMillis)

        // Unrelated app should have NO scheduled wakeup at all!
        val unaffected = RuleEngine.evaluatePackage(settings, "com.example.calculator", monday830, timeZone = utc)
        assertFalse(unaffected.blocked)
        assertNull(unaffected.nextWakeDelayMillis)
    }

    @Test
    fun pruneStaleUsageRemovesOldDatesAndDeletedRulesOnly() {
        val rule = Rule(
            id = "r1",
            name = "Keep",
            startTime = "09:00",
            endTime = "17:00",
            daysOfWeek = listOf(Calendar.MONDAY),
            appLimits = emptyList()
        )
        val usage = mapOf(
            "r1_20260316_com.a" to 1000L,       // today -> keep
            "r1_20260315_com.a" to 2000L,       // yesterday -> keep
            "r1_20260314_com.a" to 3000L,       // 2 days ago -> prune
            "deleted_20260316_com.a" to 4000L,  // deleted rule -> prune
            "r1_20260316_com.foo_bar" to 5000L  // pkg with underscore -> keep
        )
        val pruned = RuleEngine.pruneStaleUsage(usage, listOf(rule), "20260316", "20260315")
        assertEquals(3, pruned.size)
        assertEquals(1000L, pruned["r1_20260316_com.a"])
        assertEquals(2000L, pruned["r1_20260315_com.a"])
        assertEquals(5000L, pruned["r1_20260316_com.foo_bar"])

        // When nothing is stale, returns exact same map reference
        val secondPass = RuleEngine.pruneStaleUsage(pruned, listOf(rule), "20260316", "20260315")
        assertSame(pruned, secondPass)
    }
}

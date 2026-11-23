package com.mhm.curbstomp.domain

import com.mhm.curbstomp.data.models.AppLimit
import com.mhm.curbstomp.data.models.InstantFocus
import com.mhm.curbstomp.data.models.Rule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class StrictModePolicyTest {

    private val baseBlacklistRule = Rule(
        id = "r1",
        name = "Work",
        startTime = "09:00",
        endTime = "17:00",
        daysOfWeek = listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY),
        appLimits = listOf(
            AppLimit("com.instagram.android", 0L),
            AppLimit("com.google.android.youtube", 600_000L)
        ),
        isWhitelist = false,
        isActive = true
    )

    @Test
    fun inactiveRuleNeverRequiresPasswordToEdit() {
        val inactive = baseBlacklistRule.copy(isActive = false)
        val weaker = inactive.copy(
            startTime = "10:00",
            endTime = "11:00",
            daysOfWeek = listOf(Calendar.MONDAY),
            appLimits = emptyList()
        )
        assertFalse(StrictModePolicy.requiresPasswordForRuleEdit(inactive, weaker))
    }

    @Test
    fun makingActiveBlacklistStricterDoesNotRequirePassword() {
        val stricter = baseBlacklistRule.copy(
            startTime = "08:00",
            endTime = "18:00",
            daysOfWeek = listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY),
            appLimits = listOf(
                AppLimit("com.instagram.android", 0L),
                AppLimit("com.google.android.youtube", 300_000L),
                AppLimit("com.tiktok.android", 0L)
            )
        )
        assertFalse(StrictModePolicy.requiresPasswordForRuleEdit(baseBlacklistRule, stricter))
    }

    @Test
    fun weakeningActiveBlacklistRequiresPasswordInEveryDimension() {
        // Flipping mode
        assertTrue(
            StrictModePolicy.requiresPasswordForRuleEdit(
                baseBlacklistRule,
                baseBlacklistRule.copy(isWhitelist = true)
            )
        )
        // Shortening time window
        assertTrue(
            StrictModePolicy.requiresPasswordForRuleEdit(
                baseBlacklistRule,
                baseBlacklistRule.copy(endTime = "16:00")
            )
        )
        // Removing a scheduled day
        assertTrue(
            StrictModePolicy.requiresPasswordForRuleEdit(
                baseBlacklistRule,
                baseBlacklistRule.copy(daysOfWeek = listOf(Calendar.MONDAY, Calendar.TUESDAY))
            )
        )
        // Removing a blocked app
        assertTrue(
            StrictModePolicy.requiresPasswordForRuleEdit(
                baseBlacklistRule,
                baseBlacklistRule.copy(appLimits = listOf(AppLimit("com.google.android.youtube", 600_000L)))
            )
        )
        // Increasing allowed usage minutes
        assertTrue(
            StrictModePolicy.requiresPasswordForRuleEdit(
                baseBlacklistRule,
                baseBlacklistRule.copy(
                    appLimits = listOf(
                        AppLimit("com.instagram.android", 0L),
                        AppLimit("com.google.android.youtube", 900_000L)
                    )
                )
            )
        )
    }

    @Test
    fun activeWhitelistAllowsRemovingAppsAndRequiresPasswordForAddingOrIncreasing() {
        val wl = baseBlacklistRule.copy(isWhitelist = true)

        // Removing an allowed app is stricter -> no password
        val stricter = wl.copy(appLimits = listOf(AppLimit("com.instagram.android", 0L)))
        assertFalse(StrictModePolicy.requiresPasswordForRuleEdit(wl, stricter))

        // Adding an allowed app is weaker -> requires password
        val addedApp = wl.copy(appLimits = wl.appLimits + AppLimit("com.example.new", 0L))
        assertTrue(StrictModePolicy.requiresPasswordForRuleEdit(wl, addedApp))

        // Increasing limit on allowed app is weaker -> requires password
        val increasedLimit = wl.copy(
            appLimits = listOf(
                AppLimit("com.instagram.android", 0L),
                AppLimit("com.google.android.youtube", 1_200_000L)
            )
        )
        assertTrue(StrictModePolicy.requiresPasswordForRuleEdit(wl, increasedLimit))
    }

    @Test
    fun instantFocusPolicyProtectsDurationModeAndApps() {
        val now = 1_700_000_000_000L
        val whitelistFocus = InstantFocus(
            id = "f1",
            startTimeMillis = now,
            endTimeMillis = now + 60 * 60_000L,
            appLimits = listOf(AppLimit("com.app.a", 0L), AppLimit("com.app.b", 0L)),
            isActive = true,
            isWhitelist = true
        )

        // Making whitelist stricter (longer, fewer apps) -> free
        assertFalse(
            StrictModePolicy.requiresPasswordForFocusEdit(
                oldFocus = whitelistFocus,
                newDurationMins = 90L,
                newIsWhitelist = true,
                newPackages = setOf("com.app.a")
            )
        )
        // Shorter duration -> password
        assertTrue(
            StrictModePolicy.requiresPasswordForFocusEdit(
                oldFocus = whitelistFocus,
                newDurationMins = 30L,
                newIsWhitelist = true,
                newPackages = setOf("com.app.a", "com.app.b")
            )
        )
        // Adding an app to whitelist -> password
        assertTrue(
            StrictModePolicy.requiresPasswordForFocusAppsEdit(
                oldFocus = whitelistFocus,
                newPackages = setOf("com.app.a", "com.app.b", "com.app.c")
            )
        )

        // Blacklist: adding blocked app is free, removing blocked app requires password
        val blacklistFocus = whitelistFocus.copy(isWhitelist = false)
        assertFalse(
            StrictModePolicy.requiresPasswordForFocusAppsEdit(
                oldFocus = blacklistFocus,
                newPackages = setOf("com.app.a", "com.app.b", "com.app.c")
            )
        )
        assertTrue(
            StrictModePolicy.requiresPasswordForFocusAppsEdit(
                oldFocus = blacklistFocus,
                newPackages = setOf("com.app.a")
            )
        )
    }
}

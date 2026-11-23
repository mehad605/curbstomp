package com.mhm.curbstomp.data.local

import com.mhm.curbstomp.data.models.AppLimit
import com.mhm.curbstomp.data.models.InstantFocus
import com.mhm.curbstomp.data.models.Rule
import com.mhm.curbstomp.data.models.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class SettingsJsonTest {

    @Test
    fun roundTripPreservesAllFields() {
        val original = Settings(
            rules = listOf(
                Rule(
                    id = "rule-1",
                    name = "Work Hours",
                    startTime = "09:00",
                    endTime = "17:30",
                    daysOfWeek = listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.FRIDAY),
                    appLimits = listOf(
                        AppLimit("com.instagram.android", 0L),
                        AppLimit("com.google.android.youtube", 1_800_000L)
                    ),
                    isWhitelist = false,
                    isActive = true
                ),
                Rule(
                    id = "rule-2",
                    name = "Night Sleep",
                    startTime = "22:30",
                    endTime = "06:30",
                    daysOfWeek = listOf(Calendar.SUNDAY, Calendar.SATURDAY),
                    appLimits = listOf(AppLimit("com.spotify.music", 600_000L)),
                    isWhitelist = true,
                    isActive = false
                )
            ),
            instantFocusSessions = listOf(
                InstantFocus(
                    id = "focus-1",
                    name = "Deep Work",
                    startTimeMillis = 1_700_000_000_000L,
                    endTimeMillis = 1_700_003_600_000L,
                    appLimits = listOf(AppLimit("com.twitter.android", 0L)),
                    isActive = true,
                    isWhitelist = true,
                    isPaused = true,
                    remainingMillis = 1_200_000L
                )
            ),
            passwordHash = "pbkdf2_sha256\$100000\$00112233445566778899aabbccddeeff\$deadbeef",
            antiUninstallEnabled = true,
            extraHardenEnabled = true,
            deviceAdminActivationRequestedAt = 1_700_000_123_456L,
            appUsageMillis = mapOf(
                "rule-1_20261123_com.google.android.youtube" to 420_000L,
                "rule-2_20261122_com.spotify.music" to 120_000L
            )
        )

        val json = SettingsJson.toJson(original)
        val restored = SettingsJson.fromJson(json)

        assertEquals(original, restored)
    }

    @Test
    fun parsesLegacyGsonJsonWithMissingFieldsAndNullPassword() {
        val legacyGson = """
            {
              "rules": [
                {
                  "id": "r1",
                  "name": "Study",
                  "startTime": "20:00",
                  "endTime": "23:00",
                  "daysOfWeek": [2, 3, 4],
                  "appLimits": [{"packageName": "com.reddit.frontpage", "maxUsageMillis": 900000}],
                  "isWhitelist": false,
                  "isActive": true
                }
              ],
              "instantFocusSessions": [],
              "passwordHash": null,
              "antiUninstallEnabled": false,
              "appUsageMillis": {"r1_20261123_com.reddit.frontpage": 60000}
            }
        """.trimIndent()

        val parsed = SettingsJson.fromJson(legacyGson)
        assertEquals(1, parsed.rules.size)
        assertEquals("Study", parsed.rules[0].name)
        assertEquals(listOf(2, 3, 4), parsed.rules[0].daysOfWeek)
        assertEquals(900_000L, parsed.rules[0].appLimits[0].maxUsageMillis)
        assertNull(parsed.passwordHash)
        assertEquals(false, parsed.extraHardenEnabled)
        assertEquals(0L, parsed.deviceAdminActivationRequestedAt)
        assertEquals(60_000L, parsed.appUsageMillis["r1_20261123_com.reddit.frontpage"])
    }

    @Test
    fun recoversGracefullyFromBlankOrCorruptJson() {
        assertEquals(Settings(), SettingsJson.fromJson(""))
        assertEquals(Settings(), SettingsJson.fromJson("{not valid json"))
    }
}

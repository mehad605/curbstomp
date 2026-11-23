package com.mhm.curbstomp.data.local

import com.mhm.curbstomp.data.models.AppLimit
import com.mhm.curbstomp.data.models.InstantFocus
import com.mhm.curbstomp.data.models.Rule
import com.mhm.curbstomp.data.models.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Zero-dependency JSON serializer/deserializer for [Settings] backed by Android's `org.json`.
 * Preserves full schema compatibility with existing `datastore/settings.json` files.
 */
object SettingsJson {

    fun toJson(settings: Settings): String {
        val root = JSONObject()

        val rulesArray = JSONArray()
        for (rule in settings.rules) {
            val obj = JSONObject()
            obj.put("id", rule.id)
            obj.put("name", rule.name)
            obj.put("startTime", rule.startTime)
            obj.put("endTime", rule.endTime)

            val daysArray = JSONArray()
            for (day in rule.daysOfWeek) {
                daysArray.put(day)
            }
            obj.put("daysOfWeek", daysArray)
            obj.put("appLimits", appLimitsToJson(rule.appLimits))
            obj.put("isWhitelist", rule.isWhitelist)
            obj.put("isActive", rule.isActive)
            rulesArray.put(obj)
        }
        root.put("rules", rulesArray)

        val focusArray = JSONArray()
        for (focus in settings.instantFocusSessions) {
            val obj = JSONObject()
            obj.put("id", focus.id)
            obj.put("name", focus.name)
            obj.put("startTimeMillis", focus.startTimeMillis)
            obj.put("endTimeMillis", focus.endTimeMillis)
            obj.put("appLimits", appLimitsToJson(focus.appLimits))
            obj.put("isActive", focus.isActive)
            obj.put("isWhitelist", focus.isWhitelist)
            obj.put("isPaused", focus.isPaused)
            obj.put("remainingMillis", focus.remainingMillis)
            focusArray.put(obj)
        }
        root.put("instantFocusSessions", focusArray)

        if (settings.passwordHash != null) {
            root.put("passwordHash", settings.passwordHash)
        }
        root.put("antiUninstallEnabled", settings.antiUninstallEnabled)
        root.put("extraHardenEnabled", settings.extraHardenEnabled)
        root.put("deviceAdminActivationRequestedAt", settings.deviceAdminActivationRequestedAt)

        val usageObj = JSONObject()
        for ((key, value) in settings.appUsageMillis) {
            usageObj.put(key, value)
        }
        root.put("appUsageMillis", usageObj)

        return root.toString()
    }

    fun fromJson(raw: String): Settings {
        if (raw.isBlank()) return Settings()
        return try {
            val root = JSONObject(raw)

            val rules = mutableListOf<Rule>()
            val rulesArray = root.optJSONArray("rules")
            if (rulesArray != null) {
                for (i in 0 until rulesArray.length()) {
                    val obj = rulesArray.optJSONObject(i) ?: continue
                    val days = mutableListOf<Int>()
                    val daysArray = obj.optJSONArray("daysOfWeek")
                    if (daysArray != null) {
                        for (j in 0 until daysArray.length()) {
                            days.add(daysArray.optInt(j))
                        }
                    }
                    rules.add(
                        Rule(
                            id = obj.optString("id", "").ifEmpty { UUID.randomUUID().toString() },
                            name = obj.optString("name", "New Rule"),
                            startTime = obj.optString("startTime", "00:00"),
                            endTime = obj.optString("endTime", "23:59"),
                            daysOfWeek = days,
                            appLimits = appLimitsFromJson(obj.optJSONArray("appLimits")),
                            isWhitelist = obj.optBoolean("isWhitelist", false),
                            isActive = obj.optBoolean("isActive", true)
                        )
                    )
                }
            }

            val focusSessions = mutableListOf<InstantFocus>()
            val focusArray = root.optJSONArray("instantFocusSessions")
            if (focusArray != null) {
                for (i in 0 until focusArray.length()) {
                    val obj = focusArray.optJSONObject(i) ?: continue
                    focusSessions.add(
                        InstantFocus(
                            id = obj.optString("id", "").ifEmpty { UUID.randomUUID().toString() },
                            name = obj.optString("name", "Instant Focus"),
                            startTimeMillis = obj.optLong("startTimeMillis", 0L),
                            endTimeMillis = obj.optLong("endTimeMillis", 0L),
                            appLimits = appLimitsFromJson(obj.optJSONArray("appLimits")),
                            isActive = obj.optBoolean("isActive", true),
                            isWhitelist = obj.optBoolean("isWhitelist", false),
                            isPaused = obj.optBoolean("isPaused", false),
                            remainingMillis = obj.optLong("remainingMillis", 0L)
                        )
                    )
                }
            }

            val passwordHash = if (root.has("passwordHash") && !root.isNull("passwordHash")) {
                root.optString("passwordHash", "").ifEmpty { null }
            } else {
                null
            }

            val usageMap = mutableMapOf<String, Long>()
            val usageObj = root.optJSONObject("appUsageMillis")
            if (usageObj != null) {
                val keys = usageObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    usageMap[key] = usageObj.optLong(key, 0L)
                }
            }

            Settings(
                rules = rules,
                instantFocusSessions = focusSessions,
                passwordHash = passwordHash,
                antiUninstallEnabled = root.optBoolean("antiUninstallEnabled", false),
                extraHardenEnabled = root.optBoolean("extraHardenEnabled", false),
                deviceAdminActivationRequestedAt = root.optLong("deviceAdminActivationRequestedAt", 0L),
                appUsageMillis = usageMap
            )
        } catch (_: Exception) {
            Settings()
        }
    }

    private fun appLimitsToJson(limits: List<AppLimit>): JSONArray {
        val arr = JSONArray()
        for (limit in limits) {
            val obj = JSONObject()
            obj.put("packageName", limit.packageName)
            obj.put("maxUsageMillis", limit.maxUsageMillis)
            arr.put(obj)
        }
        return arr
    }

    private fun appLimitsFromJson(arr: JSONArray?): List<AppLimit> {
        if (arr == null) return emptyList()
        val result = ArrayList<AppLimit>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val pkg = obj.optString("packageName", "")
            if (pkg.isNotEmpty()) {
                result.add(AppLimit(pkg, obj.optLong("maxUsageMillis", 0L)))
            }
        }
        return result
    }
}

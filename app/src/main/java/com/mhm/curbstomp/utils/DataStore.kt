package com.mhm.curbstomp.utils

import android.content.Context
import android.util.AtomicFile
import com.mhm.curbstomp.data.models.AppLimit
import com.mhm.curbstomp.data.models.InstantFocus
import com.mhm.curbstomp.data.models.Rule
import com.mhm.curbstomp.data.models.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

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

private class SettingsStore(private val file: File) {
    private val atomicFile = AtomicFile(file)
    private val mutex = Mutex()
    private val state = MutableStateFlow(Settings())

    @Volatile
    private var loaded = false

    val data: Flow<Settings> = flow {
        ensureLoaded()
        emitAll(state)
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            ensureLoadedLocked()
        }
    }

    private suspend fun ensureLoadedLocked() {
        if (loaded) return
        val diskSettings = withContext(Dispatchers.IO) {
            try {
                val bytes = atomicFile.readFully()
                SettingsJson.fromJson(bytes.toString(Charsets.UTF_8))
            } catch (_: FileNotFoundException) {
                Settings()
            } catch (_: Exception) {
                Settings()
            }
        }
        state.value = diskSettings
        loaded = true
    }

    suspend fun updateData(transform: (Settings) -> Settings): Settings {
        return mutex.withLock {
            ensureLoadedLocked()
            val current = state.value
            val updated = transform(current)
            if (updated == current) return@withLock current

            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                val stream = atomicFile.startWrite()
                try {
                    stream.write(SettingsJson.toJson(updated).toByteArray(Charsets.UTF_8))
                    atomicFile.finishWrite(stream)
                } catch (e: Exception) {
                    atomicFile.failWrite(stream)
                    throw e
                }
            }
            state.value = updated
            updated
        }
    }
}

class DataStoreManager(context: Context) {

    private val appContext = context.applicationContext

    companion object {
        @Volatile
        private var storeInstance: SettingsStore? = null

        private fun getStore(context: Context): SettingsStore {
            return storeInstance ?: synchronized(this) {
                storeInstance ?: SettingsStore(
                    File(context.applicationContext.filesDir, "datastore/settings.json")
                ).also { storeInstance = it }
            }
        }
    }

    private val store = getStore(appContext)

    val settings: Flow<Settings> = store.data

    suspend fun update(transform: (Settings) -> Settings): Settings {
        return store.updateData(transform)
    }

    suspend fun updateSettings(newSettings: Settings) {
        store.updateData { newSettings }
    }

    suspend fun updateRules(rules: List<Rule>) {
        store.updateData { it.copy(rules = rules) }
    }

    suspend fun updateInstantFocusSessions(sessions: List<InstantFocus>) {
        store.updateData { it.copy(instantFocusSessions = sessions) }
    }

    suspend fun updatePasswordHash(hash: String?) {
        store.updateData { it.copy(passwordHash = hash) }
    }

    suspend fun updateAntiUninstallEnabled(enabled: Boolean) {
        store.updateData { it.copy(antiUninstallEnabled = enabled) }
    }

    suspend fun updateDeviceAdminActivationRequestedAt(timestamp: Long) {
        store.updateData { it.copy(deviceAdminActivationRequestedAt = timestamp) }
    }

    suspend fun updateAppUsageMillis(usageMap: Map<String, Long>) {
        store.updateData { it.copy(appUsageMillis = usageMap) }
    }
}

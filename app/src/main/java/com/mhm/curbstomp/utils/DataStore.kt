package com.mhm.curbstomp.utils

import android.content.Context
import androidx.datastore.core.MultiProcessDataStoreFactory
import androidx.datastore.core.Serializer
import com.google.gson.Gson
import com.mhm.curbstomp.data.models.Settings
import com.mhm.curbstomp.data.models.Rule
import com.mhm.curbstomp.data.models.InstantFocus
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Type

class GsonSerializer<T>(
    private val gson: Gson,
    private val type: Type,
    override val defaultValue: T
) : Serializer<T> {

    override suspend fun readFrom(input: InputStream): T {
        return try {
            gson.fromJson(input.readBytes().decodeToString(), type) ?: defaultValue
        } catch (e: Exception) {
            e.printStackTrace()
            defaultValue
        }
    }

    override suspend fun writeTo(t: T, output: OutputStream) {
        output.write(gson.toJson(t).toByteArray())
    }
}

class DataStoreManager(private val context: Context) {
    private val gson = Gson()

    companion object {
        @Volatile
        private var INSTANCE: androidx.datastore.core.DataStore<Settings>? = null

        fun getSettingsDataStore(context: Context, gson: Gson): androidx.datastore.core.DataStore<Settings> {
            return INSTANCE ?: synchronized(this) {
                val instance = MultiProcessDataStoreFactory.create(
                    serializer = GsonSerializer(
                        gson = gson,
                        type = Settings::class.java,
                        defaultValue = Settings()
                    ),
                    produceFile = { File(context.applicationContext.filesDir, "datastore/settings.json") }
                )
                INSTANCE = instance
                instance
            }
        }
    }

    private val settingsDataStore = getSettingsDataStore(context, gson)

    val settings = settingsDataStore.data

    suspend fun updateSettings(newSettings: Settings) {
        settingsDataStore.updateData { newSettings }
    }

    suspend fun updateRules(rules: List<Rule>) {
        settingsDataStore.updateData { it.copy(rules = rules) }
    }

    suspend fun updateInstantFocusSessions(sessions: List<InstantFocus>) {
        settingsDataStore.updateData { it.copy(instantFocusSessions = sessions) }
    }

    suspend fun updatePasswordHash(hash: String?) {
        settingsDataStore.updateData { it.copy(passwordHash = hash) }
    }

    suspend fun updateAntiUninstallEnabled(enabled: Boolean) {
        settingsDataStore.updateData { it.copy(antiUninstallEnabled = enabled) }
    }

    suspend fun updateDeviceAdminActivationRequestedAt(timestamp: Long) {
        settingsDataStore.updateData { it.copy(deviceAdminActivationRequestedAt = timestamp) }
    }

    suspend fun updateAppUsageMillis(usageMap: Map<String, Long>) {
        settingsDataStore.updateData { it.copy(appUsageMillis = usageMap) }
    }
}

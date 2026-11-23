package com.mhm.curbstomp

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import com.mhm.curbstomp.data.local.SettingsStore
import com.mhm.curbstomp.data.repository.InstalledAppsRepository
import com.mhm.curbstomp.data.repository.SettingsRepository
import com.mhm.curbstomp.ui.common.AppIconLoader

/**
 * Lightweight, zero-reflection manual DI container scoped to the [Application] lifecycle.
 * Lazily instantiates repositories so background service wakeups never allocate UI caches.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val settingsStore: SettingsStore by lazy {
        SettingsStore(java.io.File(appContext.filesDir, "datastore/settings.json"))
    }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(settingsStore) }

    private val installedAppsRepoLazy = lazy { InstalledAppsRepository(appContext) }
    val installedAppsRepository: InstalledAppsRepository by installedAppsRepoLazy

    private val appIconLoaderLazy = lazy { AppIconLoader(appContext) }
    val appIconLoader: AppIconLoader by appIconLoaderLazy

    fun trimUiCaches() {
        if (installedAppsRepoLazy.isInitialized()) {
            installedAppsRepoLazy.value.clearCache()
        }
        if (appIconLoaderLazy.isInitialized()) {
            appIconLoaderLazy.value.clear()
        }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as Curbstomp).container

class Curbstomp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        Thread.setDefaultUncaughtExceptionHandler(CrashLogger(this))
        super.onCreate()
        container = AppContainer(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && ::container.isInitialized) {
            container.trimUiCaches()
        }
    }
}

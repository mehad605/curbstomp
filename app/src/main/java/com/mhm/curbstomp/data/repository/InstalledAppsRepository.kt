package com.mhm.curbstomp.data.repository

import android.content.Context
import android.content.Intent
import com.mhm.curbstomp.data.models.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Provides the cached list of launchable third-party/user applications (excluding Curbstomp itself).
 * Cache is cleared automatically when the UI is hidden via `Application.onTrimMemory`.
 */
class InstalledAppsRepository(context: Context) {

    private val appContext = context.applicationContext

    @Volatile
    private var cachedApps: List<AppInfo>? = null

    fun clearCache() {
        cachedApps = null
    }

    suspend fun getLaunchableApps(): List<AppInfo> = withContext(Dispatchers.Default) {
        cachedApps?.let { return@withContext it }

        val pm = appContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        val list = ArrayList<AppInfo>(resolveInfos.size)

        for (info in resolveInfos) {
            val pkgName = info.activityInfo.packageName
            if (pkgName != appContext.packageName) {
                val label = info.loadLabel(pm).toString()
                list.add(AppInfo(label = label, packageName = pkgName))
            }
        }

        val uniqueSorted = list
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
        cachedApps = uniqueSorted
        uniqueSorted
    }
}

package com.mhm.curbstomp.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.mhm.curbstomp.ui.activity.SelectAppsActivity.AppItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

object AppCache {
    var cachedApps: List<AppItem>? = null

    suspend fun getApps(context: Context): List<AppItem> = withContext(Dispatchers.Default) {
        if (cachedApps != null) {
            return@withContext cachedApps!!
        }

        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        val list = mutableListOf<AppItem>()

        for (info in resolveInfos) {
            val pkgName = info.activityInfo.packageName
            if (pkgName != context.packageName) {
                val label = info.loadLabel(pm).toString()
                list.add(AppItem(label, pkgName))
            }
        }
        
        val uniqueList = list.distinctBy { it.packageName }.toMutableList()
        uniqueList.sortBy { it.label.lowercase(Locale.ROOT) }
        
        cachedApps = uniqueList
        return@withContext uniqueList
    }
}

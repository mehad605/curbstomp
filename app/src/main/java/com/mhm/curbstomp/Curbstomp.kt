package com.mhm.curbstomp

import android.app.Application
import android.content.ComponentCallbacks2
import com.mhm.curbstomp.utils.AppCache

class Curbstomp : Application() {
    override fun onCreate() {
        Thread.setDefaultUncaughtExceptionHandler(CrashLogger(this))
        super.onCreate()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            AppCache.clear()
        }
    }
}

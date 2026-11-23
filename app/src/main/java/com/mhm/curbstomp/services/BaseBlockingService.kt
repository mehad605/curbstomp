package com.mhm.curbstomp.services

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.mhm.curbstomp.appContainer
import com.mhm.curbstomp.data.repository.SettingsRepository

open class BaseBlockingService : AccessibilityService() {

    protected val settingsRepository: SettingsRepository by lazy {
        appContainer.settingsRepository
    }

    private var lastActionTimeStamp: Long = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    protected fun isCooldownElapsed(cooldownMs: Long): Boolean {
        return SystemClock.uptimeMillis() - lastActionTimeStamp > cooldownMs
    }

    protected fun pressHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        lastActionTimeStamp = SystemClock.uptimeMillis()
    }
}

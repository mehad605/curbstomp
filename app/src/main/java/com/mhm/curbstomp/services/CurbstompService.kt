package com.mhm.curbstomp.services

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.mhm.curbstomp.data.models.Settings
import com.mhm.curbstomp.utils.RuleEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

class CurbstompService : BaseBlockingService() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateMutex = Mutex()

    @Volatile
    private var currentSettings: Settings = Settings()

    @Volatile
    private var isScreenOn: Boolean = true

    private var activePackage: String? = null
    private var sessionStartElapsedMs: Long = 0L
    private var nextCheckJob: Job? = null
    private var defaultLauncherPackage: String? = null
    private var receiverRegistered: Boolean = false

    companion object {
        private const val TARGET_EVENTS_MASK =
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        private const val KICK_COOLDOWN_MS = 500L
    }

    private val systemStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    serviceScope.launch {
                        stateMutex.withLock {
                            nextCheckJob?.cancel()
                            nextCheckJob = null
                            flushCurrentSessionLocked()
                        }
                    }
                }
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED -> {
                    val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                    isScreenOn = pm?.isInteractive ?: true
                    serviceScope.launch {
                        stateMutex.withLock {
                            if (isScreenOn) {
                                val pkg = activePackage
                                if (pkg != null) {
                                    evaluateAndScheduleLocked(pkg, addUsageMillis = 0L)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        serviceInfo = info

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        isScreenOn = pm?.isInteractive ?: true

        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                systemStateReceiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }

        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfo = packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        defaultLauncherPackage = resolveInfo?.activityInfo?.packageName

        serviceScope.launch {
            dataStoreManager.settings.collectLatest { settings ->
                val previous = currentSettings
                currentSettings = settings
                cleanupExpiredInstantFocus(settings)

                val policyChanged = previous.rules != settings.rules ||
                    previous.instantFocusSessions != settings.instantFocusSessions ||
                    previous.extraHardenEnabled != settings.extraHardenEnabled
                if (policyChanged && isScreenOn) {
                    stateMutex.withLock {
                        val pkg = activePackage
                        if (pkg != null) {
                            flushCurrentSessionLocked()
                            evaluateAndScheduleLocked(pkg, addUsageMillis = 0L)
                        }
                    }
                }
            }
        }
    }

    private suspend fun cleanupExpiredInstantFocus(settings: Settings) {
        val currentTime = System.currentTimeMillis()
        val active = RuleEngine.pruneExpiredInstantFocus(settings.instantFocusSessions, currentTime)
        if (active !== settings.instantFocusSessions) {
            dataStoreManager.updateInstantFocusSessions(active)
        }
    }

    private fun isCriticalSystemApp(packageName: String): Boolean {
        if (packageName == "com.mhm.curbstomp" || packageName == "com.android.systemui" || packageName == defaultLauncherPackage) {
            return true
        }

        try {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
            if (telecomManager?.defaultDialerPackage == packageName) return true
        } catch (_: Exception) {}

        try {
            if (android.provider.Telephony.Sms.getDefaultSmsPackage(this) == packageName) return true
        } catch (_: Exception) {}

        val fallbacks = setOf(
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.server.telecom",
            "com.samsung.android.incallui",
            "com.android.phone",
            "com.google.android.contacts",
            "com.android.contacts",
            "com.samsung.android.dialer",
            "com.samsung.android.contacts",
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.samsung.android.messaging"
        )
        return fallbacks.contains(packageName)
    }

    private suspend fun flushCurrentSessionLocked() {
        val pkg = activePackage ?: return
        val start = sessionStartElapsedMs
        if (start <= 0L) return
        sessionStartElapsedMs = 0L
        val elapsed = (SystemClock.elapsedRealtime() - start).coerceAtLeast(0L)
        if (elapsed <= 0L || isCriticalSystemApp(pkg)) return

        val result = RuleEngine.evaluatePackage(
            settings = currentSettings,
            packageName = pkg,
            nowMillis = System.currentTimeMillis(),
            addUsageMillis = elapsed
        )
        val updatedUsage = result.updatedUsageMap
        if (updatedUsage != null) {
            currentSettings = currentSettings.copy(appUsageMillis = updatedUsage)
            dataStoreManager.updateAppUsageMillis(updatedUsage)
        }
    }

    private suspend fun evaluateAndScheduleLocked(fgPackage: String, addUsageMillis: Long) {
        nextCheckJob?.cancel()
        nextCheckJob = null

        if (!isScreenOn || isCriticalSystemApp(fgPackage)) {
            sessionStartElapsedMs = 0L
            return
        }

        val result = RuleEngine.evaluatePackage(
            settings = currentSettings,
            packageName = fgPackage,
            nowMillis = System.currentTimeMillis(),
            addUsageMillis = addUsageMillis
        )

        val updatedUsage = result.updatedUsageMap
        if (updatedUsage != null) {
            currentSettings = currentSettings.copy(appUsageMillis = updatedUsage)
            dataStoreManager.updateAppUsageMillis(updatedUsage)
        }

        if (result.blocked) {
            sessionStartElapsedMs = 0L
            kickUserOut(result.blockReason ?: "Blocked!")
            return
        }

        sessionStartElapsedMs = if (result.hasTrackedLimit) SystemClock.elapsedRealtime() else 0L

        val wakeDelay = result.nextWakeDelayMillis
        if (wakeDelay != null && wakeDelay > 0L) {
            nextCheckJob = serviceScope.launch {
                delay(wakeDelay)
                stateMutex.withLock {
                    if (!isScreenOn || activePackage != fgPackage) return@withLock
                    val start = sessionStartElapsedMs
                    sessionStartElapsedMs = 0L
                    val elapsed = if (start > 0L) {
                        (SystemClock.elapsedRealtime() - start).coerceAtLeast(0L)
                    } else {
                        0L
                    }
                    evaluateAndScheduleLocked(fgPackage, addUsageMillis = elapsed)
                }
            }
        }
    }

    private fun kickUserOut(reason: String) {
        if (!isDelayOver(lastBackPressTimeStamp, KICK_COOLDOWN_MS)) return
        pressHome()
        mainHandler.post {
            Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        super.onAccessibilityEvent(event)

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()
            if (!packageName.isNullOrEmpty()) {
                serviceScope.launch {
                    stateMutex.withLock {
                        val previous = activePackage
                        if (previous != packageName) {
                            flushCurrentSessionLocked()
                            activePackage = packageName
                            evaluateAndScheduleLocked(packageName, addUsageMillis = 0L)
                        } else if (isScreenOn) {
                            // Same package reopened or window updated: check immediate block without resetting tracked slice
                            val start = sessionStartElapsedMs
                            val elapsed = if (start > 0L) {
                                (SystemClock.elapsedRealtime() - start).coerceAtLeast(0L)
                            } else {
                                0L
                            }
                            sessionStartElapsedMs = 0L
                            evaluateAndScheduleLocked(packageName, addUsageMillis = elapsed)
                        }
                    }
                }
            }
        }

        handleSelfProtection(event)
    }

    private fun handleSelfProtection(event: AccessibilityEvent) {
        if ((event.eventType and TARGET_EVENTS_MASK) == 0) return

        val rootNode = rootInActiveWindow ?: return
        val appPackage = rootNode.packageName?.toString() ?: ""

        val antiUninstallSafePackages = setOf(
            "com.android.chrome", "org.mozilla.firefox", "com.brave.browser", "com.opera.browser",
            "com.whatsapp", "org.telegram.messenger", "com.discord"
        )

        if (appPackage == "com.mhm.curbstomp" || antiUninstallSafePackages.contains(appPackage)) {
            rootNode.recycle()
            return
        }

        val screenTexts = mutableListOf<String>()
        collectScreenTexts(rootNode, screenTexts)
        
        val hasAppName = screenTexts.any { it.lowercase(Locale.ROOT).contains("curbstomp") }
        val eventClassName = event.className?.toString() ?: ""
        val isDeviceAdminScreen = eventClassName.contains("DeviceAdmin", ignoreCase = true) ||
                eventClassName.contains("DevicePolicy", ignoreCase = true) ||
                screenTexts.any {
                    val lower = it.lowercase(Locale.ROOT)
                    lower.contains("device admin") || lower.contains("device administrator") || lower.contains("active admin")
                }

        val hasDeactivateAction = screenTexts.any {
            val lower = it.lowercase(Locale.ROOT)
            lower.contains("deactivate") || lower.contains("de-activate")
        }

        val hasUninstallAction = screenTexts.any {
            val lower = it.lowercase(Locale.ROOT)
            lower.contains("force stop") || lower.contains("kill") || lower.contains("clear data") || lower.contains("uninstall")
        }

        val hasAccessibilityAction = screenTexts.any {
            val lower = it.lowercase(Locale.ROOT)
            lower.contains("curbstomp needs access") || lower.contains("stop curbstomp") || lower.contains("turn off curbstomp")
        }

        val isActivating = System.currentTimeMillis() - currentSettings.deviceAdminActivationRequestedAt < 180000

        var shouldBlock = false
        if (hasAppName) {
            if (isDeviceAdminScreen) {
                if (currentSettings.antiUninstallEnabled && hasDeactivateAction && !isActivating) {
                    shouldBlock = true
                }
            } else if (hasAccessibilityAction) {
                shouldBlock = true
            } else {
                if (hasUninstallAction) {
                    shouldBlock = true
                }
            }
        }

        if (shouldBlock) {
            pressHome()
            val msg = if (currentSettings.antiUninstallEnabled) {
                "Self-protection: Cannot disable accessibility, force stop, clear data, deactivate admin, or uninstall Curbstomp!"
            } else {
                "Self-protection: Cannot disable accessibility, force stop, clear data, or uninstall Curbstomp!"
            }
            mainHandler.post {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
        rootNode.recycle()
    }

    private fun collectScreenTexts(node: AccessibilityNodeInfo?, targetList: MutableList<String>) {
        node ?: return
        val text = node.text?.toString()
        if (!text.isNullOrEmpty()) {
            targetList.add(text)
        }
        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrEmpty()) {
            targetList.add(desc)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                collectScreenTexts(child, targetList)
                child.recycle()
            }
        }
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(systemStateReceiver)
            } catch (_: Exception) {}
            receiverRegistered = false
        }
        nextCheckJob?.cancel()
        nextCheckJob = null

        val pkg = activePackage
        val start = sessionStartElapsedMs
        sessionStartElapsedMs = 0L
        if (pkg != null && start > 0L && !isCriticalSystemApp(pkg)) {
            val elapsed = (SystemClock.elapsedRealtime() - start).coerceAtLeast(0L)
            if (elapsed > 0L) {
                val snapshot = currentSettings
                val now = System.currentTimeMillis()
                CoroutineScope(Dispatchers.IO).launch {
                    val result = RuleEngine.evaluatePackage(
                        settings = snapshot,
                        packageName = pkg,
                        nowMillis = now,
                        addUsageMillis = elapsed
                    )
                    result.updatedUsageMap?.let { dataStoreManager.updateAppUsageMillis(it) }
                }
            }
        }

        serviceScope.cancel()
        super.onDestroy()
    }
}
package com.mhm.curbstomp.services

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import com.mhm.curbstomp.CrashLogger
import com.mhm.curbstomp.data.models.Settings
import com.mhm.curbstomp.utils.DataStoreManager
import java.util.Calendar
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date

class CurbstompService : BaseBlockingService() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var crashLogger: CrashLogger
    private var currentSettings: Settings = Settings()
    
    private var currentForegroundPackage: String? = null
    private var defaultLauncherPackage: String? = null
    
    // Ticker job
    private var tickerJob: Job? = null

    companion object {
        private const val TARGET_EVENTS_MASK = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
    }

    override fun onCreate() {
        super.onCreate()
        crashLogger = CrashLogger(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        serviceInfo = info

        serviceScope.launch {
            dataStoreManager.settings.collectLatest { settings ->
                currentSettings = settings
                cleanupExpiredInstantFocus(settings)
            }
        }
        
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply { 
            addCategory(android.content.Intent.CATEGORY_HOME) 
        }
        val resolveInfo = packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        defaultLauncherPackage = resolveInfo?.activityInfo?.packageName

        startUsageTicker()
    }

    private suspend fun cleanupExpiredInstantFocus(settings: Settings) {
        val currentTime = System.currentTimeMillis()
        val expired = settings.instantFocusSessions.filter { currentTime > it.endTimeMillis }
        if (expired.isNotEmpty()) {
            val active = settings.instantFocusSessions.filter { currentTime <= it.endTimeMillis }
            dataStoreManager.updateInstantFocusSessions(active)
        }
    }

    private fun startUsageTicker() {
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                handleTick()
            }
        }
    }

    private suspend fun handleTick() {
        val fgPackage = currentForegroundPackage ?: return
        if (fgPackage == "com.mhm.curbstomp") return
        
        // Add 1 second of usage and check limits
        checkLimits(fgPackage, addUsage = 1000L)
    }

    private fun isCriticalSystemApp(packageName: String): Boolean {
        if (packageName == "com.mhm.curbstomp" || packageName == "com.android.systemui" || packageName == defaultLauncherPackage) {
            return true
        }

        try {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
            if (telecomManager?.defaultDialerPackage == packageName) return true
        } catch(e: Exception) {}

        try {
            if (android.provider.Telephony.Sms.getDefaultSmsPackage(this) == packageName) return true
        } catch(e: Exception) {}

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

    // Returns true if blocked
    private suspend fun checkLimits(fgPackage: String, addUsage: Long = 0L): Boolean {
        if (isCriticalSystemApp(fgPackage)) return false
        
        if (currentSettings.extraHardenEnabled && fgPackage == "com.android.settings") {
            kickUserOut("Extra Harden: Settings Blocked!")
            return true
        }

        val calendar = Calendar.getInstance()
        val currentDay = calendar.get(Calendar.DAY_OF_WEEK)
        val currentTimeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val currentTimeMillis = System.currentTimeMillis()
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())

        var kicked = false
        var usageMap = currentSettings.appUsageMillis.toMutableMap()
        var updated = false

        // 1. Check Instant Focus
        for (focus in currentSettings.instantFocusSessions) {
            if (focus.isActive && currentTimeMillis in focus.startTimeMillis..focus.endTimeMillis) {
                val inList = focus.appLimits.any { it.packageName == fgPackage }
                val shouldBlock = if (focus.isWhitelist) !inList else inList
                
                if (shouldBlock) {
                    kickUserOut(if (focus.isWhitelist) "Instant Focus: App Not Allowed!" else "Instant Focus: Blocked!")
                    kicked = true
                    break
                }
            }
        }

        if (kicked) {
            if (updated) dataStoreManager.updateAppUsageMillis(usageMap)
            return true
        }

        // 2. Check Rules
        val prevDay = if (currentDay == 1) 7 else currentDay - 1

        for (rule in currentSettings.rules) {
            if (!rule.isActive) continue

            val crossesMidnight = rule.startTime > rule.endTime
            var ruleActiveNow = false

            if (crossesMidnight) {
                if (currentTimeStr >= rule.startTime && rule.daysOfWeek.contains(currentDay)) {
                    ruleActiveNow = true
                } else if (currentTimeStr <= rule.endTime && rule.daysOfWeek.contains(prevDay)) {
                    ruleActiveNow = true
                }
            } else {
                if (rule.startTime == rule.endTime) {
                     // 24 hours rule
                     if (rule.daysOfWeek.contains(currentDay)) ruleActiveNow = true
                } else if (currentTimeStr in rule.startTime..rule.endTime && rule.daysOfWeek.contains(currentDay)) {
                    ruleActiveNow = true
                }
            }

            if (ruleActiveNow) {
                val activeDateStr = if (crossesMidnight && currentTimeStr <= rule.endTime) {
                    val cal = Calendar.getInstance()
                    cal.add(Calendar.DAY_OF_YEAR, -1)
                    SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(cal.time)
                } else {
                    dateStr
                }
                
                val appLimit = rule.appLimits.find { it.packageName == fgPackage }
                
                if (rule.isWhitelist) {
                    if (appLimit == null) {
                        kickUserOut("Rule ${rule.name}: App Not Allowed!")
                        kicked = true
                        break
                    } else {
                        val key = "${rule.id}_${activeDateStr}_$fgPackage"
                        var usedMillis = usageMap[key] ?: 0L
                        if (addUsage > 0) {
                            usedMillis += addUsage
                            usageMap[key] = usedMillis
                            updated = true
                        }
                        if (usedMillis >= appLimit.maxUsageMillis) {
                            kickUserOut("Rule ${rule.name}: Time Limit Reached!")
                            kicked = true
                            break
                        }
                    }
                } else {
                    if (appLimit != null) {
                        val key = "${rule.id}_${activeDateStr}_$fgPackage"
                        var usedMillis = usageMap[key] ?: 0L
                        if (addUsage > 0) {
                            usedMillis += addUsage
                            usageMap[key] = usedMillis
                            updated = true
                        }
                        if (usedMillis >= appLimit.maxUsageMillis) {
                            kickUserOut("Rule ${rule.name}: App Blocked!")
                            kicked = true
                            break
                        }
                    }
                }
            }
        }

        if (updated) {
            dataStoreManager.updateAppUsageMillis(usageMap)
        }
        
        return kicked
    }

    private fun isTimeInRange(current: String, start: String, end: String): Boolean {
        if (start == end) return true
        if (start < end) {
            return current in start..end
        }
        // crosses midnight (e.g. 22:00 to 06:00)
        return current >= start || current <= end
    }

    private fun kickUserOut(reason: String) {
        pressHome()
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        super.onAccessibilityEvent(event)

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()
            if (packageName != null) {
                currentForegroundPackage = packageName
                // Immediate check to prevent the "1 second delay" visual flash of the app
                serviceScope.launch {
                    checkLimits(packageName, addUsage = 0L)
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
            Handler(Looper.getMainLooper()).post {
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
        super.onDestroy()
        tickerJob?.cancel()
        serviceScope.cancel()
    }
}
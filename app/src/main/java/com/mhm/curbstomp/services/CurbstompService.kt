package com.mhm.curbstomp.services

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
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

class CurbstompService : BaseBlockingService() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateMutex = Mutex()

    @Volatile
    private var currentSettings: Settings = Settings()

    @Volatile
    private var isScreenOn: Boolean = true

    @Volatile
    private var defaultLauncherPackage: String? = null

    @Volatile
    private var defaultDialerPackage: String? = null

    @Volatile
    private var defaultSmsPackage: String? = null

    @Volatile
    private var lastSystemPkgRefreshMs: Long = 0L

    @Volatile
    private var selfProtectionPackages: Set<String> = emptySet()

    private var activePackage: String? = null
    private var sessionStartElapsedMs: Long = 0L
    private var nextCheckJob: Job? = null
    private var receiverRegistered: Boolean = false
    private var lastSelfProtectionContentScanMs: Long = 0L

    companion object {
        private const val TARGET_EVENTS_MASK =
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        private const val KICK_COOLDOWN_MS = 500L
        private const val CONTENT_SCAN_DEBOUNCE_MS = 200L
        private const val SYSTEM_PKG_CACHE_TTL_MS = 5 * 60_000L

        private const val FLAG_APP_NAME = 1 shl 0
        private const val FLAG_DEVICE_ADMIN = 1 shl 1
        private const val FLAG_DEACTIVATE = 1 shl 2
        private const val FLAG_UNINSTALL = 1 shl 3
        private const val FLAG_ACCESSIBILITY = 1 shl 4

        private val CRITICAL_FALLBACK_PACKAGES = setOf(
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

        private val STATIC_SELF_PROTECTION_PACKAGES = setOf(
            "com.android.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.vending"
        )
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
            eventTypes = TARGET_EVENTS_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT
            notificationTimeout = 100
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
            ContextCompat.registerReceiver(
                this,
                systemStateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }

        refreshSystemPackages(force = true)

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

    private fun refreshSystemPackages(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastSystemPkgRefreshMs < SYSTEM_PKG_CACHE_TTL_MS) return
        lastSystemPkgRefreshMs = now

        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
            defaultLauncherPackage = packageManager
                .resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        } catch (_: Exception) {}

        try {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            defaultDialerPackage = telecomManager?.defaultDialerPackage
        } catch (_: Exception) {}

        try {
            defaultSmsPackage = Telephony.Sms.getDefaultSmsPackage(this)
        } catch (_: Exception) {}

        val targets = HashSet<String>(STATIC_SELF_PROTECTION_PACKAGES)
        val selfUri = Uri.parse("package:$packageName")
        val probeIntents = arrayOf(
            Intent(AndroidSettings.ACTION_SETTINGS),
            Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, selfUri),
            Intent(Intent.ACTION_DELETE, selfUri),
            Intent(Intent.ACTION_UNINSTALL_PACKAGE, selfUri),
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        )
        for (probe in probeIntents) {
            try {
                val resolved = packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
                for (ri in resolved) {
                    val pkg = ri.activityInfo?.packageName
                    if (!pkg.isNullOrEmpty() && pkg != packageName) {
                        targets.add(pkg)
                    }
                }
            } catch (_: Exception) {}
        }
        selfProtectionPackages = targets
    }

    private fun isSelfProtectionTarget(pkg: String, eventType: Int): Boolean {
        if (pkg.isEmpty() || pkg == packageName || pkg == "com.android.systemui") return false
        if (pkg == defaultLauncherPackage) {
            // Only inspect launcher on window-state transitions (e.g. in-launcher uninstall dialog),
            // never on continuous home-screen scroll content updates.
            return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        }
        if (selfProtectionPackages.contains(pkg)) return true
        return pkg.startsWith("com.android.settings") ||
            pkg.contains("packageinstaller") ||
            pkg.startsWith("com.oplus.") ||
            pkg.startsWith("com.coloros.") ||
            pkg.startsWith("com.heytap.") ||
            pkg.startsWith("com.oppo.") ||
            pkg.startsWith("com.realme.") ||
            pkg.startsWith("com.miui.security") ||
            pkg.startsWith("com.samsung.android.lool")
    }

    private suspend fun cleanupExpiredInstantFocus(settings: Settings) {
        val currentTime = System.currentTimeMillis()
        val active = RuleEngine.pruneExpiredInstantFocus(settings.instantFocusSessions, currentTime)
        if (active !== settings.instantFocusSessions) {
            dataStoreManager.updateInstantFocusSessions(active)
        }
    }

    private fun isCriticalSystemApp(packageName: String): Boolean {
        if (packageName == this.packageName ||
            packageName == "com.android.systemui" ||
            packageName == defaultLauncherPackage
        ) {
            return true
        }
        refreshSystemPackages(force = false)
        if (packageName == defaultDialerPackage || packageName == defaultSmsPackage) {
            return true
        }
        return CRITICAL_FALLBACK_PACKAGES.contains(packageName)
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
        val eventType = event.eventType
        if ((eventType and TARGET_EVENTS_MASK) == 0) return

        // Fast-path filter BEFORE touching rootInActiveWindow (zero Binder IPC for normal user apps)
        val eventPkg = event.packageName?.toString() ?: ""
        if (!isSelfProtectionTarget(eventPkg, eventType)) return

        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val now = SystemClock.uptimeMillis()
            if (now - lastSelfProtectionContentScanMs < CONTENT_SCAN_DEBOUNCE_MS) return
            lastSelfProtectionContentScanMs = now
        }

        val rootNode = rootInActiveWindow ?: return
        try {
            val rootPkg = rootNode.packageName?.toString() ?: ""
            if (!isSelfProtectionTarget(rootPkg, eventType)) return

            var initialFlags = 0
            val eventClassName = event.className
            if (eventClassName != null &&
                (eventClassName.contains("DeviceAdmin", ignoreCase = true) ||
                    eventClassName.contains("DevicePolicy", ignoreCase = true))
            ) {
                initialFlags = initialFlags or FLAG_DEVICE_ADMIN
            }

            val settings = currentSettings
            val isActivating =
                System.currentTimeMillis() - settings.deviceAdminActivationRequestedAt < 180_000L

            val flags = scanNodeTree(rootNode, initialFlags, settings.antiUninstallEnabled, isActivating)
            if (shouldBlockFromFlags(flags, settings.antiUninstallEnabled, isActivating)) {
                if (!isDelayOver(lastBackPressTimeStamp, KICK_COOLDOWN_MS)) return
                pressHome()
                val msg = if (settings.antiUninstallEnabled) {
                    "Self-protection: Cannot disable accessibility, force stop, clear data, deactivate admin, or uninstall Curbstomp!"
                } else {
                    "Self-protection: Cannot disable accessibility, force stop, clear data, or uninstall Curbstomp!"
                }
                mainHandler.post {
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        } finally {
            safeRecycle(rootNode)
        }
    }

    private fun shouldBlockFromFlags(
        flags: Int,
        antiUninstallEnabled: Boolean,
        isActivating: Boolean
    ): Boolean {
        if ((flags and FLAG_APP_NAME) == 0) return false
        return when {
            (flags and FLAG_DEVICE_ADMIN) != 0 ->
                antiUninstallEnabled && (flags and FLAG_DEACTIVATE) != 0 && !isActivating
            (flags and FLAG_ACCESSIBILITY) != 0 -> true
            else -> (flags and FLAG_UNINSTALL) != 0
        }
    }

    private fun scanNodeTree(
        node: AccessibilityNodeInfo,
        currentFlags: Int,
        antiUninstallEnabled: Boolean,
        isActivating: Boolean
    ): Int {
        var flags = updateFlagsFromText(node.text, currentFlags)
        flags = updateFlagsFromText(node.contentDescription, flags)

        // Short-circuit only when DeviceAdmin screen cannot flip a tentative uninstall match:
        // Accessibility matches unconditionally; DeviceAdmin+Deactivate matches unconditionally;
        // Uninstall match still needs full tree check only if FLAG_DEVICE_ADMIN isn't yet known.
        if ((flags and FLAG_APP_NAME) != 0 && (flags and FLAG_ACCESSIBILITY) != 0) {
            return flags
        }
        if ((flags and FLAG_APP_NAME) != 0 &&
            (flags and FLAG_DEVICE_ADMIN) != 0 &&
            antiUninstallEnabled &&
            (flags and FLAG_DEACTIVATE) != 0 &&
            !isActivating
        ) {
            return flags
        }

        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i) ?: continue
            try {
                flags = scanNodeTree(child, flags, antiUninstallEnabled, isActivating)
                if ((flags and FLAG_APP_NAME) != 0 && (flags and FLAG_ACCESSIBILITY) != 0) {
                    return flags
                }
            } finally {
                safeRecycle(child)
            }
        }
        return flags
    }

    private fun updateFlagsFromText(cs: CharSequence?, initialFlags: Int): Int {
        if (cs.isNullOrEmpty()) return initialFlags
        var flags = initialFlags
        if ((flags and FLAG_APP_NAME) == 0 && cs.contains("curbstomp", ignoreCase = true)) {
            flags = flags or FLAG_APP_NAME
        }
        if ((flags and FLAG_DEVICE_ADMIN) == 0 &&
            (cs.contains("device admin", ignoreCase = true) ||
                cs.contains("device administrator", ignoreCase = true) ||
                cs.contains("active admin", ignoreCase = true))
        ) {
            flags = flags or FLAG_DEVICE_ADMIN
        }
        if ((flags and FLAG_DEACTIVATE) == 0 &&
            (cs.contains("deactivate", ignoreCase = true) ||
                cs.contains("de-activate", ignoreCase = true))
        ) {
            flags = flags or FLAG_DEACTIVATE
        }
        if ((flags and FLAG_UNINSTALL) == 0 &&
            (cs.contains("force stop", ignoreCase = true) ||
                cs.contains("kill", ignoreCase = true) ||
                cs.contains("clear data", ignoreCase = true) ||
                cs.contains("uninstall", ignoreCase = true))
        ) {
            flags = flags or FLAG_UNINSTALL
        }
        if ((flags and FLAG_ACCESSIBILITY) == 0 &&
            (cs.contains("curbstomp needs access", ignoreCase = true) ||
                cs.contains("stop curbstomp", ignoreCase = true) ||
                cs.contains("turn off curbstomp", ignoreCase = true))
        ) {
            flags = flags or FLAG_ACCESSIBILITY
        }
        return flags
    }

    @Suppress("DEPRECATION")
    private fun safeRecycle(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            node.recycle()
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
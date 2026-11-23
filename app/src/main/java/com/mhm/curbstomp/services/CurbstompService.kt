package com.mhm.curbstomp.services

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.mhm.curbstomp.R
import com.mhm.curbstomp.appContainer
import com.mhm.curbstomp.data.models.Settings
import com.mhm.curbstomp.domain.RuleEngine
import com.mhm.curbstomp.domain.SelfProtectionDetector
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

/**
 * Event-driven AccessibilityService that enforces scheduled rules, Instant Focus sessions,
 * Extra Harden, and anti-tamper self-protection with zero idle polling.
 */
class CurbstompService : BaseBlockingService() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateMutex = Mutex()
    private val packageResolver by lazy { SystemPackageResolver(this) }

    @Volatile
    private var currentSettings: Settings = Settings()

    @Volatile
    private var isScreenOn: Boolean = true

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
        private const val MAX_NODE_SCAN_DEPTH = 32
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
                                val pkg = activePackage ?: return@withLock
                                evaluateAndScheduleLocked(pkg, addUsageMillis = 0L)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = TARGET_EVENTS_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT
            notificationTimeout = 100
        }

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        isScreenOn = pm?.isInteractive ?: true

        registerSystemStateReceiver()
        packageResolver.refresh(force = true)

        serviceScope.launch {
            settingsRepository.settings.collectLatest { settings ->
                val previous = currentSettings
                currentSettings = settings
                cleanupExpiredInstantFocus(settings)

                val policyChanged = previous.rules != settings.rules ||
                    previous.instantFocusSessions != settings.instantFocusSessions ||
                    previous.extraHardenEnabled != settings.extraHardenEnabled
                if (policyChanged && isScreenOn) {
                    stateMutex.withLock {
                        val pkg = activePackage ?: return@withLock
                        flushCurrentSessionLocked()
                        evaluateAndScheduleLocked(pkg, addUsageMillis = 0L)
                    }
                }
            }
        }
    }

    private fun registerSystemStateReceiver() {
        if (receiverRegistered) return
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

    private suspend fun cleanupExpiredInstantFocus(settings: Settings) {
        val active = RuleEngine.pruneExpiredInstantFocus(
            settings.instantFocusSessions,
            System.currentTimeMillis()
        )
        if (active !== settings.instantFocusSessions) {
            settingsRepository.updateInstantFocusSessions(active)
        }
    }

    private suspend fun flushCurrentSessionLocked() {
        val pkg = activePackage ?: return
        val start = sessionStartElapsedMs
        if (start <= 0L) return
        sessionStartElapsedMs = 0L
        val elapsed = (SystemClock.elapsedRealtime() - start).coerceAtLeast(0L)
        if (elapsed <= 0L || packageResolver.isCriticalSystemApp(pkg)) return

        val result = RuleEngine.evaluatePackage(
            settings = currentSettings,
            packageName = pkg,
            nowMillis = System.currentTimeMillis(),
            addUsageMillis = elapsed
        )
        result.updatedUsageMap?.let { updatedUsage ->
            currentSettings = currentSettings.copy(appUsageMillis = updatedUsage)
            settingsRepository.updateAppUsageMillis(updatedUsage)
        }
    }

    private suspend fun evaluateAndScheduleLocked(fgPackage: String, addUsageMillis: Long) {
        nextCheckJob?.cancel()
        nextCheckJob = null

        if (!isScreenOn || packageResolver.isCriticalSystemApp(fgPackage)) {
            sessionStartElapsedMs = 0L
            return
        }

        val result = RuleEngine.evaluatePackage(
            settings = currentSettings,
            packageName = fgPackage,
            nowMillis = System.currentTimeMillis(),
            addUsageMillis = addUsageMillis
        )

        result.updatedUsageMap?.let { updatedUsage ->
            currentSettings = currentSettings.copy(appUsageMillis = updatedUsage)
            settingsRepository.updateAppUsageMillis(updatedUsage)
        }

        if (result.blocked) {
            sessionStartElapsedMs = 0L
            kickUserOut(result.blockReason ?: getString(R.string.toast_blocked_default))
            return
        }

        sessionStartElapsedMs = if (result.hasTrackedLimit) SystemClock.elapsedRealtime() else 0L

        val wakeDelay = result.nextWakeDelayMillis
        if (wakeDelay != null && wakeDelay > 0L) {
            nextCheckJob = serviceScope.launch {
                delay(wakeDelay)
                stateMutex.withLock {
                    if (!isScreenOn || activePackage != fgPackage) return@withLock
                    val elapsed = consumeElapsedSessionMs()
                    evaluateAndScheduleLocked(fgPackage, addUsageMillis = elapsed)
                }
            }
        }
    }

    private fun consumeElapsedSessionMs(): Long {
        val start = sessionStartElapsedMs
        sessionStartElapsedMs = 0L
        return if (start > 0L) (SystemClock.elapsedRealtime() - start).coerceAtLeast(0L) else 0L
    }

    private fun kickUserOut(reason: String, longToast: Boolean = false) {
        if (!isCooldownElapsed(KICK_COOLDOWN_MS)) return
        pressHome()
        val duration = if (longToast) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        mainHandler.post {
            Toast.makeText(this, reason, duration).show()
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
                        if (activePackage != packageName) {
                            flushCurrentSessionLocked()
                            activePackage = packageName
                            evaluateAndScheduleLocked(packageName, addUsageMillis = 0L)
                        } else if (isScreenOn) {
                            val elapsed = consumeElapsedSessionMs()
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
        if (!packageResolver.isSelfProtectionTarget(eventPkg, eventType)) return

        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val now = SystemClock.uptimeMillis()
            if (now - lastSelfProtectionContentScanMs < CONTENT_SCAN_DEBOUNCE_MS) return
            lastSelfProtectionContentScanMs = now
        }

        val rootNode = rootInActiveWindow ?: return
        try {
            val rootPkg = rootNode.packageName?.toString() ?: ""
            if (!packageResolver.isSelfProtectionTarget(rootPkg, eventType)) return

            val settings = currentSettings
            val initialFlags = SelfProtectionDetector.initialFlagsForClassName(event.className)
            val isActivating = SelfProtectionDetector.isWithinDeviceAdminGraceWindow(
                settings.deviceAdminActivationRequestedAt,
                System.currentTimeMillis()
            )

            val flags = scanNodeTree(rootNode, initialFlags, settings.antiUninstallEnabled, isActivating)
            if (SelfProtectionDetector.shouldBlock(flags, settings.antiUninstallEnabled, isActivating)) {
                val msg = getString(
                    if (settings.antiUninstallEnabled) {
                        R.string.toast_self_protection_full
                    } else {
                        R.string.toast_self_protection_standard
                    }
                )
                kickUserOut(msg, longToast = true)
            }
        } finally {
            safeRecycle(rootNode)
        }
    }

    private fun scanNodeTree(
        node: AccessibilityNodeInfo,
        currentFlags: Int,
        antiUninstallEnabled: Boolean,
        isActivating: Boolean,
        depth: Int = 0
    ): Int {
        var flags = SelfProtectionDetector.updateFlagsFromText(node.text, currentFlags)
        flags = SelfProtectionDetector.updateFlagsFromText(node.contentDescription, flags)
        if (depth >= MAX_NODE_SCAN_DEPTH ||
            SelfProtectionDetector.canShortCircuit(flags, antiUninstallEnabled, isActivating)
        ) {
            return flags
        }

        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i) ?: continue
            try {
                flags = scanNodeTree(child, flags, antiUninstallEnabled, isActivating, depth + 1)
                if (SelfProtectionDetector.canShortCircuit(flags, antiUninstallEnabled, isActivating)) {
                    return flags
                }
            } finally {
                safeRecycle(child)
            }
        }
        return flags
    }

    @Suppress("DEPRECATION")
    private fun safeRecycle(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            node.recycle()
        }
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(systemStateReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }
        nextCheckJob?.cancel()
        nextCheckJob = null

        val pkg = activePackage
        val elapsed = consumeElapsedSessionMs()
        if (pkg != null && elapsed > 0L && !packageResolver.isCriticalSystemApp(pkg)) {
            val snapshot = currentSettings
            val now = System.currentTimeMillis()
            val repo = settingsRepository
            appContainer.applicationScope.launch {
                val result = RuleEngine.evaluatePackage(
                    settings = snapshot,
                    packageName = pkg,
                    nowMillis = now,
                    addUsageMillis = elapsed
                )
                result.updatedUsageMap?.let { repo.updateAppUsageMillis(it) }
            }
        }

        serviceScope.cancel()
        super.onDestroy()
    }
}
package com.mhm.curbstomp.services

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.net.toUri

/**
 * Resolves and caches system-critical packages (launcher, dialer, SMS) and self-protection
 * target packages (OEM Settings, Package Installer, Device Admin screens) with a 5-minute TTL.
 *
 * All hot-path queries ([isCriticalSystemApp], [isSelfProtectionTarget]) operate purely on
 * in-memory snapshots to avoid Binder IPC during accessibility events.
 */
class SystemPackageResolver(context: Context) {

    private val appContext = context.applicationContext
    private val ownPackageName = appContext.packageName

    @Volatile
    private var defaultLauncherPackage: String? = null

    @Volatile
    private var defaultDialerPackage: String? = null

    @Volatile
    private var defaultSmsPackage: String? = null

    @Volatile
    private var selfProtectionPackages: Set<String> = STATIC_SELF_PROTECTION_PACKAGES

    @Volatile
    private var settingsPackages: Set<String> = STATIC_SETTINGS_PACKAGES

    @Volatile
    private var lastRefreshElapsedMs: Long = 0L

    companion object {
        private const val CACHE_TTL_MS = 5 * 60_000L
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

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

        private val STATIC_SETTINGS_PACKAGES = setOf("com.android.settings")

        private val STATIC_SELF_PROTECTION_PACKAGES = setOf(
            "com.android.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.vending"
        )
    }

    fun refresh(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastRefreshElapsedMs < CACHE_TTL_MS) return
        lastRefreshElapsedMs = now

        val pm = appContext.packageManager

        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
            defaultLauncherPackage = pm
                .resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        } catch (_: Exception) {
        }

        try {
            val telecomManager = appContext.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            defaultDialerPackage = telecomManager?.defaultDialerPackage
        } catch (_: Exception) {
        }

        try {
            defaultSmsPackage = Telephony.Sms.getDefaultSmsPackage(appContext)
        } catch (_: Exception) {
        }

        val resolvedSettings = HashSet<String>(STATIC_SETTINGS_PACKAGES)
        try {
            val settingsActivities = pm.queryIntentActivities(
                Intent(AndroidSettings.ACTION_SETTINGS),
                PackageManager.MATCH_DEFAULT_ONLY
            )
            for (ri in settingsActivities) {
                val pkg = ri.activityInfo?.packageName
                if (!pkg.isNullOrEmpty() && pkg != ownPackageName) {
                    resolvedSettings.add(pkg)
                }
            }
        } catch (_: Exception) {
        }
        settingsPackages = resolvedSettings

        val targets = HashSet<String>(STATIC_SELF_PROTECTION_PACKAGES)
        targets.addAll(resolvedSettings)
        val selfUri = "package:$ownPackageName".toUri()
        @Suppress("DEPRECATION")
        val probeIntents = arrayOf(
            Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, selfUri),
            Intent(Intent.ACTION_DELETE, selfUri),
            Intent(Intent.ACTION_UNINSTALL_PACKAGE, selfUri),
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        )
        for (probe in probeIntents) {
            try {
                val resolved = pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
                for (ri in resolved) {
                    val pkg = ri.activityInfo?.packageName
                    if (!pkg.isNullOrEmpty() && pkg != ownPackageName) {
                        targets.add(pkg)
                    }
                }
            } catch (_: Exception) {
            }
        }
        selfProtectionPackages = targets
    }

    fun isSettingsApp(packageName: String): Boolean {
        return settingsPackages.contains(packageName)
    }

    fun isCriticalSystemApp(packageName: String): Boolean {
        if (packageName == ownPackageName ||
            packageName == SYSTEM_UI_PACKAGE ||
            packageName == defaultLauncherPackage
        ) {
            return true
        }
        refresh(force = false)
        if (packageName == defaultDialerPackage || packageName == defaultSmsPackage) {
            return true
        }
        return CRITICAL_FALLBACK_PACKAGES.contains(packageName)
    }

    fun isSelfProtectionTarget(packageName: String, eventType: Int): Boolean {
        if (packageName.isEmpty() || packageName == ownPackageName || packageName == SYSTEM_UI_PACKAGE) {
            return false
        }
        if (packageName == defaultLauncherPackage) {
            // Only inspect the launcher on window-state transitions (e.g. in-launcher uninstall dialog),
            // never on continuous home-screen scroll content updates.
            return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        }
        if (selfProtectionPackages.contains(packageName)) return true
        return packageName.startsWith("com.android.settings") ||
            packageName.contains("packageinstaller") ||
            packageName.startsWith("com.oplus.") ||
            packageName.startsWith("com.coloros.") ||
            packageName.startsWith("com.heytap.") ||
            packageName.startsWith("com.oppo.") ||
            packageName.startsWith("com.realme.") ||
            packageName.startsWith("com.miui.security") ||
            packageName.startsWith("com.samsung.android.lool")
    }
}

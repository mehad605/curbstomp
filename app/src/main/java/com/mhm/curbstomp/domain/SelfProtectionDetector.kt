package com.mhm.curbstomp.domain

/**
 * Pure, zero-allocation bitmask detector for anti-tamper screen inspection.
 *
 * Combines:
 * 1. **Locale-independent signals** (`event.className`, package name `"com.mhm.curbstomp"`,
 *    and AOSP/OEM `viewIdResourceName` entry names) so anti-tamper works across non-English locales.
 * 2. **Text / contentDescription fallbacks** for OEM ROMs that rename resource IDs.
 *
 * Decoupled from `AccessibilityNodeInfo` so every self-protection rule is JVM unit-testable.
 */
object SelfProtectionDetector {

    const val FLAG_APP_NAME = 1 shl 0
    const val FLAG_DEVICE_ADMIN = 1 shl 1
    const val FLAG_DEACTIVATE = 1 shl 2
    const val FLAG_UNINSTALL = 1 shl 3
    const val FLAG_ACCESSIBILITY = 1 shl 4
    const val FLAG_DEVICE_ADMIN_DETAIL = 1 shl 5

    const val DEVICE_ADMIN_ACTIVATION_GRACE_MS = 180_000L

    private const val APP_PACKAGE_NAME = "com.mhm.curbstomp"

    fun initialFlagsForClassName(className: CharSequence?): Int {
        if (className.isNullOrEmpty()) return 0
        var flags = 0
        if (className.contains("DeviceAdminAdd", ignoreCase = true)) {
            flags = flags or FLAG_DEVICE_ADMIN or FLAG_DEVICE_ADMIN_DETAIL
        } else if (className.contains("DeviceAdmin", ignoreCase = true) ||
            className.contains("DevicePolicy", ignoreCase = true)
        ) {
            flags = flags or FLAG_DEVICE_ADMIN
        }
        if (className.contains("UninstallerActivity", ignoreCase = true) ||
            className.contains("UninstallAppProgress", ignoreCase = true) ||
            className.contains("DeletePackage", ignoreCase = true)
        ) {
            flags = flags or FLAG_UNINSTALL
        }
        if (className.contains("ToggleAccessibilityService", ignoreCase = true) ||
            className.contains("AccessibilityServiceSettings", ignoreCase = true)
        ) {
            flags = flags or FLAG_ACCESSIBILITY
        }
        return flags
    }

    /**
     * Inspects an Android `viewIdResourceName` (e.g. `"com.android.settings:id/force_stop_button"`)
     * using only the entry name after `"/"` — completely independent of the device language.
     */
    fun updateFlagsFromViewId(viewIdResourceName: CharSequence?, initialFlags: Int): Int {
        if (viewIdResourceName.isNullOrEmpty()) return initialFlags
        val slashIdx = viewIdResourceName.lastIndexOf('/')
        val entry = if (slashIdx >= 0 && slashIdx + 1 < viewIdResourceName.length) {
            viewIdResourceName.subSequence(slashIdx + 1, viewIdResourceName.length)
        } else {
            viewIdResourceName
        }

        var flags = initialFlags
        if ((flags and FLAG_UNINSTALL) == 0 &&
            (entry.contains("force_stop", ignoreCase = true) ||
                entry.contains("uninstall", ignoreCase = true) ||
                entry.contains("clear_data", ignoreCase = true) ||
                entry.contains("clear_storage", ignoreCase = true))
        ) {
            flags = flags or FLAG_UNINSTALL
        }
        if ((flags and FLAG_DEVICE_ADMIN_DETAIL) == 0 &&
            (entry.contentEquals("admin_policies", ignoreCase = true) ||
                entry.contentEquals("admin_description", ignoreCase = true) ||
                entry.contentEquals("admin_warning", ignoreCase = true))
        ) {
            flags = flags or FLAG_DEVICE_ADMIN or FLAG_DEVICE_ADMIN_DETAIL
        } else if ((flags and FLAG_DEVICE_ADMIN) == 0 &&
            (entry.contentEquals("admin_name", ignoreCase = true) ||
                entry.contentEquals("restricted_action", ignoreCase = true))
        ) {
            flags = flags or FLAG_DEVICE_ADMIN
        }
        return flags
    }

    fun updateFlagsFromText(cs: CharSequence?, initialFlags: Int): Int {
        if (cs.isNullOrEmpty()) return initialFlags
        var flags = initialFlags
        if ((flags and FLAG_APP_NAME) == 0 &&
            (cs.contains("curbstomp", ignoreCase = true) ||
                cs.contains(APP_PACKAGE_NAME, ignoreCase = true))
        ) {
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

    /**
     * Returns true when [flags] are sufficient to stop scanning the rest of the node tree early.
     * Note: an uninstall match alone cannot short-circuit because a later node might identify
     * the screen as the Device Admin activation prompt.
     */
    fun canShortCircuit(
        flags: Int,
        antiUninstallEnabled: Boolean,
        isActivatingDeviceAdmin: Boolean
    ): Boolean {
        if ((flags and FLAG_APP_NAME) == 0) return false
        if ((flags and FLAG_ACCESSIBILITY) != 0) return true
        return isDeviceAdminTamper(flags, antiUninstallEnabled, isActivatingDeviceAdmin)
    }

    fun shouldBlock(
        flags: Int,
        antiUninstallEnabled: Boolean,
        isActivatingDeviceAdmin: Boolean
    ): Boolean {
        if ((flags and FLAG_APP_NAME) == 0) return false
        return when {
            (flags and FLAG_DEVICE_ADMIN) != 0 ->
                isDeviceAdminTamper(flags, antiUninstallEnabled, isActivatingDeviceAdmin)
            (flags and FLAG_ACCESSIBILITY) != 0 -> true
            else -> (flags and FLAG_UNINSTALL) != 0
        }
    }

    private fun isDeviceAdminTamper(
        flags: Int,
        antiUninstallEnabled: Boolean,
        isActivatingDeviceAdmin: Boolean
    ): Boolean {
        if (!antiUninstallEnabled || isActivatingDeviceAdmin) return false
        if ((flags and FLAG_DEVICE_ADMIN) == 0) return false
        // Block either explicit deactivation text OR any DeviceAdminAdd detail screen for Curbstomp
        // once Device Admin is already enabled outside the activation grace window (locale-independent).
        return (flags and FLAG_DEACTIVATE) != 0 || (flags and FLAG_DEVICE_ADMIN_DETAIL) != 0
    }

    fun isWithinDeviceAdminGraceWindow(
        activationRequestedAtMillis: Long,
        nowMillis: Long
    ): Boolean {
        if (activationRequestedAtMillis <= 0L) return false
        val elapsed = nowMillis - activationRequestedAtMillis
        return elapsed in 0 until DEVICE_ADMIN_ACTIVATION_GRACE_MS
    }
}

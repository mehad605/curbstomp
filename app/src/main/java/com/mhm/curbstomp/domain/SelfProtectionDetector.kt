package com.mhm.curbstomp.domain

/**
 * Pure, zero-allocation bitmask detector for anti-tamper screen text inspection.
 * Decoupled from `AccessibilityNodeInfo` so every self-protection rule is unit-testable.
 */
object SelfProtectionDetector {

    const val FLAG_APP_NAME = 1 shl 0
    const val FLAG_DEVICE_ADMIN = 1 shl 1
    const val FLAG_DEACTIVATE = 1 shl 2
    const val FLAG_UNINSTALL = 1 shl 3
    const val FLAG_ACCESSIBILITY = 1 shl 4

    const val DEVICE_ADMIN_ACTIVATION_GRACE_MS = 180_000L

    fun initialFlagsForClassName(className: CharSequence?): Int {
        if (className.isNullOrEmpty()) return 0
        return if (className.contains("DeviceAdmin", ignoreCase = true) ||
            className.contains("DevicePolicy", ignoreCase = true)
        ) {
            FLAG_DEVICE_ADMIN
        } else {
            0
        }
    }

    fun updateFlagsFromText(cs: CharSequence?, initialFlags: Int): Int {
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
        return (flags and FLAG_DEVICE_ADMIN) != 0 &&
            antiUninstallEnabled &&
            (flags and FLAG_DEACTIVATE) != 0 &&
            !isActivatingDeviceAdmin
    }

    fun shouldBlock(
        flags: Int,
        antiUninstallEnabled: Boolean,
        isActivatingDeviceAdmin: Boolean
    ): Boolean {
        if ((flags and FLAG_APP_NAME) == 0) return false
        return when {
            (flags and FLAG_DEVICE_ADMIN) != 0 ->
                antiUninstallEnabled && (flags and FLAG_DEACTIVATE) != 0 && !isActivatingDeviceAdmin
            (flags and FLAG_ACCESSIBILITY) != 0 -> true
            else -> (flags and FLAG_UNINSTALL) != 0
        }
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

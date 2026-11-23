package com.mhm.curbstomp.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfProtectionDetectorTest {

    @Test
    fun normalSettingsScreenWithoutAppNameIsNeverBlocked() {
        var flags = SelfProtectionDetector.initialFlagsForClassName("com.android.settings.Settings")
        flags = SelfProtectionDetector.updateFlagsFromText("App info", flags)
        flags = SelfProtectionDetector.updateFlagsFromText("Force stop", flags)
        flags = SelfProtectionDetector.updateFlagsFromText("Uninstall", flags)

        assertFalse(SelfProtectionDetector.shouldBlock(flags, antiUninstallEnabled = true, isActivatingDeviceAdmin = false))
    }

    @Test
    fun blocksAppInfoForceStopAndUninstallForCurbstomp() {
        var flags = 0
        flags = SelfProtectionDetector.updateFlagsFromText("Curbstomp", flags)
        flags = SelfProtectionDetector.updateFlagsFromText("Force stop", flags)

        assertTrue(SelfProtectionDetector.shouldBlock(flags, antiUninstallEnabled = false, isActivatingDeviceAdmin = false))
        // Uninstall flag alone must NOT short-circuit in case a later node reveals Device Admin activation
        assertFalse(SelfProtectionDetector.canShortCircuit(flags, antiUninstallEnabled = true, isActivatingDeviceAdmin = true))
    }

    @Test
    fun blocksAndShortCircuitsAccessibilityTurnOffPrompt() {
        var flags = 0
        flags = SelfProtectionDetector.updateFlagsFromText("Stop Curbstomp?", flags)

        assertTrue(SelfProtectionDetector.canShortCircuit(flags, antiUninstallEnabled = false, isActivatingDeviceAdmin = false))
        assertTrue(SelfProtectionDetector.shouldBlock(flags, antiUninstallEnabled = false, isActivatingDeviceAdmin = false))
    }

    @Test
    fun deviceAdminDeactivationBlockedUnlessWithinActivationGraceWindow() {
        var flags = SelfProtectionDetector.initialFlagsForClassName("com.android.settings.DeviceAdminAdd")
        flags = SelfProtectionDetector.updateFlagsFromText("Curbstomp", flags)
        flags = SelfProtectionDetector.updateFlagsFromText("Deactivate this device admin app", flags)

        // Blocked when anti-uninstall is active and not activating
        assertTrue(SelfProtectionDetector.canShortCircuit(flags, antiUninstallEnabled = true, isActivatingDeviceAdmin = false))
        assertTrue(SelfProtectionDetector.shouldBlock(flags, antiUninstallEnabled = true, isActivatingDeviceAdmin = false))

        // Allowed during initial Device Admin activation grace window
        assertFalse(SelfProtectionDetector.shouldBlock(flags, antiUninstallEnabled = true, isActivatingDeviceAdmin = true))

        // Allowed when anti-uninstall is disabled
        assertFalse(SelfProtectionDetector.shouldBlock(flags, antiUninstallEnabled = false, isActivatingDeviceAdmin = false))
    }

    @Test
    fun deviceAdminActivationScreenWithUninstallTextIsAllowedDuringGraceWindow() {
        var flags = SelfProtectionDetector.initialFlagsForClassName("com.android.settings.DeviceAdminAdd")
        flags = SelfProtectionDetector.updateFlagsFromText("Curbstomp", flags)
        flags = SelfProtectionDetector.updateFlagsFromText("Required to prevent the app from being uninstalled", flags)

        assertFalse(SelfProtectionDetector.shouldBlock(flags, antiUninstallEnabled = false, isActivatingDeviceAdmin = true))
        assertFalse(SelfProtectionDetector.shouldBlock(flags, antiUninstallEnabled = true, isActivatingDeviceAdmin = true))
    }

    @Test
    fun deviceAdminGraceWindowBoundaries() {
        val requestedAt = 1_000_000L
        assertFalse(SelfProtectionDetector.isWithinDeviceAdminGraceWindow(0L, requestedAt))
        assertTrue(SelfProtectionDetector.isWithinDeviceAdminGraceWindow(requestedAt, requestedAt))
        assertTrue(SelfProtectionDetector.isWithinDeviceAdminGraceWindow(requestedAt, requestedAt + 179_999L))
        assertFalse(SelfProtectionDetector.isWithinDeviceAdminGraceWindow(requestedAt, requestedAt + 180_000L))
        assertFalse(SelfProtectionDetector.isWithinDeviceAdminGraceWindow(requestedAt, requestedAt - 1L))
    }
}

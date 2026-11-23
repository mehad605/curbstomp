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

    @Test
    fun blocksForceStopAndUninstallViaLocaleIndependentViewIds() {
        // Non-English UI (e.g., Bengali / Arabic labels) where only resource IDs and app name exist
        var flags = 0
        flags = SelfProtectionDetector.updateFlagsFromText("Curbstomp", flags)
        flags = SelfProtectionDetector.updateFlagsFromViewId("com.android.settings:id/force_stop_button", flags)
        flags = SelfProtectionDetector.updateFlagsFromText("জোর করে বন্ধ করুন", flags)

        assertTrue(SelfProtectionDetector.shouldBlock(flags, antiUninstallEnabled = false, isActivatingDeviceAdmin = false))
    }

    @Test
    fun blocksPackageInstallerUninstallClassNameInAnyLocale() {
        var flags = SelfProtectionDetector.initialFlagsForClassName("com.android.packageinstaller.UninstallerActivity")
        flags = SelfProtectionDetector.updateFlagsFromText("com.mhm.curbstomp", flags)
        flags = SelfProtectionDetector.updateFlagsFromText("إلغاء التثبيت؟", flags)

        assertTrue(SelfProtectionDetector.shouldBlock(flags, antiUninstallEnabled = false, isActivatingDeviceAdmin = false))
    }

    @Test
    fun blocksToggleAccessibilityServiceClassNameInAnyLocale() {
        var flags = SelfProtectionDetector.initialFlagsForClassName(
            "com.android.settings.accessibility.ToggleAccessibilityServicePreferenceFragment"
        )
        flags = SelfProtectionDetector.updateFlagsFromText("Curbstomp", flags)
        flags = SelfProtectionDetector.updateFlagsFromText("停止しますか？", flags)

        assertTrue(SelfProtectionDetector.canShortCircuit(flags, antiUninstallEnabled = false, isActivatingDeviceAdmin = false))
        assertTrue(SelfProtectionDetector.shouldBlock(flags, antiUninstallEnabled = false, isActivatingDeviceAdmin = false))
    }

    @Test
    fun blocksDeviceAdminAddDetailInAnyLocaleWhenActiveButAllowsSettingsList() {
        // DeviceAdminAdd detail screen in non-English locale when anti-uninstall is already active
        var detailFlags = SelfProtectionDetector.initialFlagsForClassName("com.android.settings.DeviceAdminAdd")
        detailFlags = SelfProtectionDetector.updateFlagsFromText("Curbstomp", detailFlags)
        detailFlags = SelfProtectionDetector.updateFlagsFromText("إلغاء تنشيط مسؤول الجهاز", detailFlags)
        assertTrue(SelfProtectionDetector.shouldBlock(detailFlags, antiUninstallEnabled = true, isActivatingDeviceAdmin = false))
        assertFalse(SelfProtectionDetector.shouldBlock(detailFlags, antiUninstallEnabled = true, isActivatingDeviceAdmin = true))

        // General DeviceAdminSettings list screen listing Curbstomp alongside other apps must NOT block
        var listFlags = SelfProtectionDetector.initialFlagsForClassName("com.android.settings.DeviceAdminSettings")
        listFlags = SelfProtectionDetector.updateFlagsFromText("Curbstomp", listFlags)
        listFlags = SelfProtectionDetector.updateFlagsFromText("Find My Device", listFlags)
        assertFalse(SelfProtectionDetector.shouldBlock(listFlags, antiUninstallEnabled = true, isActivatingDeviceAdmin = false))
    }
}

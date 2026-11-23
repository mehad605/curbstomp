package com.mhm.curbstomp.services

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings as AndroidSettings
import android.view.accessibility.AccessibilityEvent
import androidx.test.core.app.ApplicationProvider
import com.mhm.curbstomp.Curbstomp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemPackageResolverTest {

    @Test
    fun resolvesCriticalAppsOemSettingsAndSelfProtectionTargets() {
        val app = ApplicationProvider.getApplicationContext<Curbstomp>()
        val shadowPm = shadowOf(app.packageManager)

        // Register a custom OEM Settings handler and launcher using modern ShadowPackageManager APIs
        val oemSettingsComponent = ComponentName("com.oem.customsettings", "com.oem.customsettings.MainSettings")
        shadowPm.addActivityIfNotPresent(oemSettingsComponent)
        shadowPm.addIntentFilterForActivity(
            oemSettingsComponent,
            IntentFilter(AndroidSettings.ACTION_SETTINGS).apply { addCategory(Intent.CATEGORY_DEFAULT) }
        )

        val launcherComponent = ComponentName("com.oem.launcher", "com.oem.launcher.Home")
        shadowPm.addActivityIfNotPresent(launcherComponent)
        shadowPm.addIntentFilterForActivity(
            launcherComponent,
            IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
        )

        val resolver = SystemPackageResolver(app)
        resolver.refresh(force = true)

        // Critical apps never blocked
        assertTrue(resolver.isCriticalSystemApp(app.packageName))
        assertTrue(resolver.isCriticalSystemApp("com.android.systemui"))
        assertTrue(resolver.isCriticalSystemApp("com.oem.launcher"))
        assertTrue(resolver.isCriticalSystemApp("com.google.android.dialer"))
        assertFalse(resolver.isCriticalSystemApp("com.example.social"))

        // Settings detection includes both AOSP and resolved OEM Settings
        assertTrue(resolver.isSettingsApp("com.android.settings"))
        assertTrue(resolver.isSettingsApp("com.oem.customsettings"))
        assertFalse(resolver.isSettingsApp("com.example.social"))

        // Self-protection targets: normal apps & own package excluded (zero Binder IPC)
        assertFalse(resolver.isSelfProtectionTarget(app.packageName, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        assertFalse(resolver.isSelfProtectionTarget("com.android.systemui", AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        assertFalse(resolver.isSelfProtectionTarget("com.example.social", AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))

        // Settings, PackageInstaller, and OEM security packages are targeted
        assertTrue(resolver.isSelfProtectionTarget("com.android.settings", AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        assertTrue(resolver.isSelfProtectionTarget("com.oem.customsettings", AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        assertTrue(resolver.isSelfProtectionTarget("com.google.android.packageinstaller", AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        assertTrue(resolver.isSelfProtectionTarget("com.miui.securitycenter", AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))

        // Launcher is inspected ONLY on window state changes, never on continuous scroll content events
        assertTrue(resolver.isSelfProtectionTarget("com.oem.launcher", AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        assertFalse(resolver.isSelfProtectionTarget("com.oem.launcher", AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
    }
}

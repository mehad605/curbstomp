package com.mhm.curbstomp.ui.common

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils

/**
 * Helpers for checking and requesting Accessibility Service and Device Admin permissions.
 */
object PermissionUtils {

    fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val expectedComponentName = ComponentName(context, serviceClass)
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)

        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == expectedComponentName) {
                return true
            }
        }
        return false
    }

    fun isDeviceAdminActive(context: Context, adminClass: Class<*>): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(context, adminClass)
        return dpm.isAdminActive(adminComponent)
    }

    fun requestDeviceAdmin(context: Context, adminClass: Class<*>, explanation: String) {
        val componentName = ComponentName(context, adminClass)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, explanation)
        }
        context.startActivity(intent)
    }

    fun removeDeviceAdmin(context: Context, adminClass: Class<*>) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val component = ComponentName(context, adminClass)
        dpm.removeActiveAdmin(component)
    }
}

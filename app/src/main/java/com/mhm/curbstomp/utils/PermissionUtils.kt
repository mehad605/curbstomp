package com.mhm.curbstomp.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import com.mhm.curbstomp.services.CurbstompService

object PermissionUtils {
    fun openAccessibilityServiceScreen(context: Context, cls: Class<*>) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            val componentName = ComponentName(context, cls)
            intent.putExtra(":settings:fragment_args_key", componentName.flattenToString())
            val bundle = android.os.Bundle()
            bundle.putString(":settings:fragment_args_key", componentName.flattenToString())
            intent.putExtra(":settings:show_fragment_args", bundle)
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback to general Accessibility Settings
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

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

    fun hasAllRequiredPermissions(context: Context): Boolean {
        return isAccessibilityServiceEnabled(context, CurbstompService::class.java)
    }

    fun isDeviceAdminActive(context: Context, adminClass: Class<*>): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val adminComponent = ComponentName(context, adminClass)
        return dpm.isAdminActive(adminComponent)
    }

    fun requestDeviceAdmin(context: Context, adminClass: Class<*>) {
        val componentName = ComponentName(context, adminClass)
        val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
            putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required to prevent the app from being uninstalled or force-stopped.")
        }
        context.startActivity(intent)
    }
}
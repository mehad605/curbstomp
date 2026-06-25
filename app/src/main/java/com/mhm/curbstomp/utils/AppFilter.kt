package com.mhm.curbstomp.utils

import android.content.Context

object AppFilter {
    fun isRestrictedFromIgnoredList(
        context: Context, 
        packageName: String, 
        permanentlyRestrictedApps: List<String> = emptyList()
    ): Boolean {
        return false
    }
}

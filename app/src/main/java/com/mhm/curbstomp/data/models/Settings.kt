package com.mhm.curbstomp.data.models

data class Settings(
    val rules: List<Rule> = emptyList(),
    val instantFocusSessions: List<InstantFocus> = emptyList(),
    val passwordHash: String? = null,
    val antiUninstallEnabled: Boolean = false,
    val extraHardenEnabled: Boolean = false,
    val deviceAdminActivationRequestedAt: Long = 0L,
    // Key format: RuleId_YYYYMMDD_PackageName or InstantFocusId_PackageName
    // Value: milliseconds used
    val appUsageMillis: Map<String, Long> = emptyMap()
)

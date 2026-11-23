package com.mhm.curbstomp.domain

import com.mhm.curbstomp.data.models.InstantFocus
import com.mhm.curbstomp.data.models.Rule

/**
 * Pure, centralized anti-cheating policy deciding whether a rule or focus edit weakens
 * an active restriction and therefore requires password verification.
 *
 * Centralizing this logic prevents UI screens from drifting or accidentally bypassing protection.
 */
object StrictModePolicy {

    /**
     * Returns true if updating [oldRule] to [newRule] weakens an active rule
     * (changing mode, shortening the time window, removing scheduled days, allowing more apps,
     * or increasing per-app usage allowances).
     */
    fun requiresPasswordForRuleEdit(oldRule: Rule, newRule: Rule): Boolean {
        if (!oldRule.isActive) return false
        if (oldRule.isWhitelist != newRule.isWhitelist) return true

        val oldDuration = RuleEngine.windowDurationMinutes(oldRule.startTime, oldRule.endTime)
        val newDuration = RuleEngine.windowDurationMinutes(newRule.startTime, newRule.endTime)
        if (oldDuration != null && newDuration != null && newDuration < oldDuration) {
            return true
        }

        // Removing any scheduled day from an active rule weakens it
        if (!newRule.daysOfWeek.containsAll(oldRule.daysOfWeek)) {
            return true
        }

        val oldLimitsByPkg = oldRule.appLimits.associateBy { it.packageName }
        val newLimitsByPkg = newRule.appLimits.associateBy { it.packageName }

        return if (newRule.isWhitelist) {
            // Whitelist: adding an allowed app or increasing its allowed time weakens restriction
            newRule.appLimits.any { newLimit ->
                val oldLimit = oldLimitsByPkg[newLimit.packageName]
                oldLimit == null || newLimit.maxUsageMillis > oldLimit.maxUsageMillis
            }
        } else {
            // Blacklist: removing a blocked app or increasing its allowed time weakens restriction
            oldRule.appLimits.any { oldLimit ->
                val newLimit = newLimitsByPkg[oldLimit.packageName]
                newLimit == null || newLimit.maxUsageMillis > oldLimit.maxUsageMillis
            }
        }
    }

    /**
     * Returns true if editing [oldFocus] to [newDurationMins] / [newIsWhitelist] / [newPackages]
     * weakens an active Instant Focus session.
     */
    fun requiresPasswordForFocusEdit(
        oldFocus: InstantFocus,
        newDurationMins: Long,
        newIsWhitelist: Boolean,
        newPackages: Set<String>
    ): Boolean {
        if (!oldFocus.isActive) return false

        val oldDurationMins = (oldFocus.endTimeMillis - oldFocus.startTimeMillis) / 60_000L
        if (newDurationMins < oldDurationMins) return true
        if (oldFocus.isWhitelist != newIsWhitelist) return true

        return requiresPasswordForFocusAppsEdit(oldFocus, newPackages)
    }

    /**
     * Returns true if changing the app set of [oldFocus] to [newPackages] weakens it.
     */
    fun requiresPasswordForFocusAppsEdit(
        oldFocus: InstantFocus,
        newPackages: Set<String>
    ): Boolean {
        if (!oldFocus.isActive) return false
        val oldPackages = oldFocus.appLimits.map { it.packageName }.toSet()
        return if (oldFocus.isWhitelist) {
            // Whitelist: adding apps allows more apps -> weakens focus
            newPackages.any { it !in oldPackages }
        } else {
            // Blacklist: removing apps unblocks apps -> weakens focus
            oldPackages.any { it !in newPackages }
        }
    }
}

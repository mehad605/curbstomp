package com.mhm.curbstomp.data.repository

import com.mhm.curbstomp.data.local.SettingsStore
import com.mhm.curbstomp.data.models.AppLimit
import com.mhm.curbstomp.data.models.InstantFocus
import com.mhm.curbstomp.data.models.Rule
import com.mhm.curbstomp.data.models.Settings
import com.mhm.curbstomp.domain.RuleEngine
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for reading and mutating Curbstomp's persisted [Settings].
 * Encapsulates atomic read-modify-write operations so UI and services never race on snapshots.
 */
class SettingsRepository(private val store: SettingsStore) {

    val settings: Flow<Settings> = store.data

    suspend fun upsertRule(rule: Rule) {
        val (todayKey, yesterdayKey) = RuleEngine.dateKeysFor(System.currentTimeMillis())
        store.updateData { current ->
            val updated = current.rules.toMutableList()
            val index = updated.indexOfFirst { it.id == rule.id }
            if (index != -1) updated[index] = rule else updated.add(rule)
            val pruned = RuleEngine.pruneStaleUsage(current.appUsageMillis, updated, todayKey, yesterdayKey)
            current.copy(rules = updated, appUsageMillis = pruned)
        }
    }

    suspend fun deleteRule(ruleId: String) {
        val (todayKey, yesterdayKey) = RuleEngine.dateKeysFor(System.currentTimeMillis())
        store.updateData { current ->
            val updated = current.rules.filterNot { it.id == ruleId }
            val pruned = RuleEngine.pruneStaleUsage(current.appUsageMillis, updated, todayKey, yesterdayKey)
            current.copy(rules = updated, appUsageMillis = pruned)
        }
    }

    suspend fun setRuleActive(ruleId: String, isActive: Boolean) {
        store.updateData { current ->
            val updated = current.rules.map { rule ->
                if (rule.id == ruleId) rule.copy(isActive = isActive) else rule
            }
            current.copy(rules = updated)
        }
    }

    suspend fun upsertInstantFocus(focus: InstantFocus) {
        store.updateData { current ->
            val updated = current.instantFocusSessions.toMutableList()
            val index = updated.indexOfFirst { it.id == focus.id }
            if (index != -1) updated[index] = focus else updated.add(focus)
            current.copy(instantFocusSessions = updated)
        }
    }

    suspend fun updateInstantFocusSessions(sessions: List<InstantFocus>) {
        store.updateData { it.copy(instantFocusSessions = sessions) }
    }

    suspend fun pauseInstantFocus(focusId: String, nowMillis: Long = System.currentTimeMillis()) {
        store.updateData { current ->
            val updated = current.instantFocusSessions.map { session ->
                if (session.id == focusId) {
                    val remaining = (session.endTimeMillis - nowMillis).coerceAtLeast(0L)
                    session.copy(isPaused = true, remainingMillis = remaining)
                } else {
                    session
                }
            }
            current.copy(instantFocusSessions = updated)
        }
    }

    suspend fun resumeInstantFocus(focusId: String, nowMillis: Long = System.currentTimeMillis()) {
        store.updateData { current ->
            val updated = current.instantFocusSessions.map { session ->
                if (session.id == focusId) {
                    session.copy(isPaused = false, endTimeMillis = nowMillis + session.remainingMillis)
                } else {
                    session
                }
            }
            current.copy(instantFocusSessions = updated)
        }
    }

    suspend fun stopInstantFocus(focusId: String, nowMillis: Long = System.currentTimeMillis()) {
        store.updateData { current ->
            val updated = current.instantFocusSessions.map { session ->
                if (session.id == focusId) {
                    session.copy(isActive = false, isPaused = false, endTimeMillis = nowMillis)
                } else {
                    session
                }
            }
            current.copy(instantFocusSessions = updated)
        }
    }

    suspend fun addInstantFocusTime(focusId: String, additionalMinutes: Long) {
        if (additionalMinutes <= 0L) return
        val deltaMillis = additionalMinutes * 60_000L
        store.updateData { current ->
            val updated = current.instantFocusSessions.map { session ->
                if (session.id == focusId) {
                    if (session.isPaused) {
                        session.copy(remainingMillis = session.remainingMillis + deltaMillis)
                    } else {
                        session.copy(endTimeMillis = session.endTimeMillis + deltaMillis)
                    }
                } else {
                    session
                }
            }
            current.copy(instantFocusSessions = updated)
        }
    }

    suspend fun updateInstantFocusApps(focusId: String, packageNames: List<String>) {
        val limits = packageNames.map { AppLimit(it, 0L) }
        store.updateData { current ->
            val updated = current.instantFocusSessions.map { session ->
                if (session.id == focusId) session.copy(appLimits = limits) else session
            }
            current.copy(instantFocusSessions = updated)
        }
    }

    suspend fun updatePasswordHash(hash: String?) {
        store.updateData { it.copy(passwordHash = hash) }
    }

    suspend fun setAntiUninstallEnabled(enabled: Boolean) {
        store.updateData { it.copy(antiUninstallEnabled = enabled) }
    }

    suspend fun setExtraHardenEnabled(enabled: Boolean) {
        store.updateData { it.copy(extraHardenEnabled = enabled) }
    }

    suspend fun markDeviceAdminActivationRequested(timestampMillis: Long = System.currentTimeMillis()) {
        store.updateData { it.copy(deviceAdminActivationRequestedAt = timestampMillis) }
    }

    suspend fun updateAppUsageMillis(usageMap: Map<String, Long>) {
        store.updateData { it.copy(appUsageMillis = usageMap) }
    }
}

package com.mhm.curbstomp.domain

import com.mhm.curbstomp.data.models.InstantFocus
import com.mhm.curbstomp.data.models.Rule
import com.mhm.curbstomp.data.models.Settings
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Pure, locale-independent schedule, focus, and usage evaluation engine.
 * Shared by [com.mhm.curbstomp.services.CurbstompService] and UI screens so enforcement and display never diverge.
 */
object RuleEngine {

    data class EvaluationResult(
        val blocked: Boolean,
        val blockReason: String? = null,
        val updatedUsageMap: Map<String, Long>? = null,
        val hasTrackedLimit: Boolean = false,
        val nextWakeDelayMillis: Long? = null
    )

    fun parseTimeToMinutes(timeStr: String): Int? {
        val colon = timeStr.indexOf(':')
        if (colon <= 0 || colon >= timeStr.length - 1) return null
        val hour = parseAsciiInt(timeStr, 0, colon) ?: return null
        val minute = parseAsciiInt(timeStr, colon + 1, timeStr.length) ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    /**
     * Formats a 24-hour `"HH:mm"` string as 12-hour ASCII `"hh:mm AM/PM"`.
     */
    fun formatTime12Hour(time24: String): String {
        val totalMin = parseTimeToMinutes(time24) ?: return time24
        val hour24 = totalMin / 60
        val minute = totalMin % 60
        val amPm = if (hour24 >= 12) "PM" else "AM"
        val hour12 = when (val h = hour24 % 12) {
            0 -> 12
            else -> h
        }
        return String.format(Locale.US, "%02d:%02d %s", hour12, minute, amPm)
    }

    /**
     * Computes window length in minutes, handling overnight spans (e.g., 23:00 -> 06:00).
     */
    fun windowDurationMinutes(startTime: String, endTime: String): Int? {
        val start = parseTimeToMinutes(startTime) ?: return null
        val end = parseTimeToMinutes(endTime) ?: return null
        return when {
            start == end -> 24 * 60
            end > start -> end - start
            else -> (end + 24 * 60) - start
        }
    }

    private fun parseAsciiInt(s: String, start: Int, end: Int): Int? {
        if (start >= end) return null
        var value = 0
        for (i in start until end) {
            val c = s[i]
            if (c !in '0'..'9') return null
            value = value * 10 + (c - '0')
        }
        return value
    }

    fun formatDateKey(calendar: Calendar): String {
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val chars = CharArray(8)
        chars[0] = ('0'.code + (year / 1000) % 10).toChar()
        chars[1] = ('0'.code + (year / 100) % 10).toChar()
        chars[2] = ('0'.code + (year / 10) % 10).toChar()
        chars[3] = ('0'.code + year % 10).toChar()
        chars[4] = ('0'.code + (month / 10) % 10).toChar()
        chars[5] = ('0'.code + month % 10).toChar()
        chars[6] = ('0'.code + (day / 10) % 10).toChar()
        chars[7] = ('0'.code + day % 10).toChar()
        return String(chars)
    }

    fun dateKeysFor(nowMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): Pair<String, String> {
        val cal = Calendar.getInstance(timeZone)
        cal.timeInMillis = nowMillis
        val today = formatDateKey(cal)
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = formatDateKey(cal)
        return today to yesterday
    }

    /**
     * Returns the active YYYYMMDD usage key if [rule] is currently active at the given time,
     * or null if the rule is not active right now.
     */
    fun activeRuleDateKey(
        rule: Rule,
        dayOfWeek: Int,
        minuteOfDay: Int,
        todayKey: String,
        yesterdayKey: String
    ): String? {
        if (!rule.isActive) return null
        val startMin = parseTimeToMinutes(rule.startTime) ?: return null
        val endMin = parseTimeToMinutes(rule.endTime) ?: return null

        return when {
            startMin == endMin -> {
                if (rule.daysOfWeek.contains(dayOfWeek)) todayKey else null
            }
            startMin < endMin -> {
                if (minuteOfDay in startMin..endMin && rule.daysOfWeek.contains(dayOfWeek)) {
                    todayKey
                } else {
                    null
                }
            }
            else -> {
                val prevDay = if (dayOfWeek == Calendar.SUNDAY) Calendar.SATURDAY else dayOfWeek - 1
                when {
                    minuteOfDay >= startMin && rule.daysOfWeek.contains(dayOfWeek) -> todayKey
                    minuteOfDay <= endMin && rule.daysOfWeek.contains(prevDay) -> yesterdayKey
                    else -> null
                }
            }
        }
    }

    fun isRuleActiveNow(
        rule: Rule,
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Boolean {
        val cal = Calendar.getInstance(timeZone)
        cal.timeInMillis = nowMillis
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val todayKey = formatDateKey(cal)
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterdayKey = formatDateKey(cal)
        return activeRuleDateKey(rule, dayOfWeek, minuteOfDay, todayKey, yesterdayKey) != null
    }

    /**
     * Returns the YYYYMMDD date key to use when reading current-window usage stats for [rule].
     */
    fun statsDateKeyForRule(
        rule: Rule,
        minuteOfDay: Int,
        todayKey: String,
        yesterdayKey: String
    ): String {
        val startMin = parseTimeToMinutes(rule.startTime) ?: return todayKey
        val endMin = parseTimeToMinutes(rule.endTime) ?: return todayKey
        return if (startMin > endMin && minuteOfDay <= endMin) yesterdayKey else todayKey
    }

    /**
     * Removes stopped or expired InstantFocus sessions while preserving paused sessions.
     */
    fun pruneExpiredInstantFocus(
        sessions: List<InstantFocus>,
        nowMillis: Long
    ): List<InstantFocus> {
        var needsPrune = false
        for (session in sessions) {
            if (!session.isActive || (!session.isPaused && nowMillis > session.endTimeMillis)) {
                needsPrune = true
                break
            }
        }
        if (!needsPrune) return sessions
        return sessions.filter { it.isActive && (it.isPaused || nowMillis <= it.endTimeMillis) }
    }

    /**
     * Returns the first active (or paused) non-expired InstantFocus session for UI display.
     */
    fun findDisplayInstantFocus(
        sessions: List<InstantFocus>,
        nowMillis: Long
    ): InstantFocus? {
        return sessions.firstOrNull {
            it.isActive && (it.isPaused || nowMillis < it.endTimeMillis)
        }
    }

    /**
     * Removes stale usage entries older than yesterday or belonging to deleted rules.
     * Returns the original [usageMap] reference if no entries needed removal.
     */
    fun pruneStaleUsage(
        usageMap: Map<String, Long>,
        rules: List<Rule>,
        todayKey: String,
        yesterdayKey: String
    ): Map<String, Long> {
        if (usageMap.isEmpty()) return usageMap
        val validRuleIds = HashSet<String>(rules.size * 2)
        for (rule in rules) {
            validRuleIds.add(rule.id)
        }

        var hasStale = false
        for (key in usageMap.keys) {
            if (!isUsageKeyValid(key, validRuleIds, todayKey, yesterdayKey)) {
                hasStale = true
                break
            }
        }
        if (!hasStale) return usageMap

        val pruned = LinkedHashMap<String, Long>(usageMap.size)
        for ((key, value) in usageMap) {
            if (isUsageKeyValid(key, validRuleIds, todayKey, yesterdayKey)) {
                pruned[key] = value
            }
        }
        return pruned
    }

    private fun isUsageKeyValid(
        key: String,
        validRuleIds: Set<String>,
        todayKey: String,
        yesterdayKey: String
    ): Boolean {
        val firstSep = key.indexOf('_')
        if (firstSep <= 0) return false
        val secondSep = key.indexOf('_', firstSep + 1)
        if (secondSep != firstSep + 9) return false
        val ruleId = key.substring(0, firstSep)
        if (!validRuleIds.contains(ruleId)) return false
        val dateKey = key.substring(firstSep + 1, secondSep)
        return dateKey == todayKey || dateKey == yesterdayKey
    }

    /**
     * Evaluates whether [packageName] is blocked at [nowMillis], optionally accruing [addUsageMillis],
     * pruning stale usage keys, and computing the exact delay until the next wake-up needed while
     * the user remains in [packageName] (null if no timer is needed).
     */
    fun evaluatePackage(
        settings: Settings,
        packageName: String,
        nowMillis: Long,
        addUsageMillis: Long = 0L,
        timeZone: TimeZone = TimeZone.getDefault()
    ): EvaluationResult {
        if (settings.extraHardenEnabled && packageName == "com.android.settings") {
            return EvaluationResult(
                blocked = true,
                blockReason = "Extra Harden: Settings Blocked!"
            )
        }

        // 1. Check Instant Focus (paused sessions do NOT block)
        var minNextWake: Long? = null
        for (focus in settings.instantFocusSessions) {
            if (!focus.isActive || focus.isPaused) continue
            val inList = focus.appLimits.any { it.packageName == packageName }
            val shouldBlock = if (focus.isWhitelist) !inList else inList
            if (shouldBlock) {
                if (nowMillis in focus.startTimeMillis..focus.endTimeMillis) {
                    return EvaluationResult(
                        blocked = true,
                        blockReason = if (focus.isWhitelist) {
                            "Instant Focus: App Not Allowed!"
                        } else {
                            "Instant Focus: Blocked!"
                        }
                    )
                } else if (focus.startTimeMillis > nowMillis) {
                    val delay = focus.startTimeMillis - nowMillis
                    minNextWake = minDelay(minNextWake, delay)
                }
            }
        }

        // 2. Check Scheduled Rules
        val cal = Calendar.getInstance(timeZone)
        cal.timeInMillis = nowMillis
        val currentDay = cal.get(Calendar.DAY_OF_WEEK)
        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val todayKey = formatDateKey(cal)
        val yestCal = cal.clone() as Calendar
        yestCal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterdayKey = formatDateKey(yestCal)

        val prunedUsage = pruneStaleUsage(settings.appUsageMillis, settings.rules, todayKey, yesterdayKey)
        var usageMutable: MutableMap<String, Long>? = if (prunedUsage !== settings.appUsageMillis) {
            prunedUsage.toMutableMap()
        } else {
            null
        }

        var hasTrackedLimit = false

        for (rule in settings.rules) {
            if (!rule.isActive) continue

            val activeDateKey = activeRuleDateKey(rule, currentDay, minuteOfDay, todayKey, yesterdayKey)
            val appLimit = rule.appLimits.find { it.packageName == packageName }

            if (activeDateKey != null) {
                if (rule.isWhitelist) {
                    if (appLimit == null) {
                        return EvaluationResult(
                            blocked = true,
                            blockReason = "Rule ${rule.name}: App Not Allowed!",
                            updatedUsageMap = usageMutable
                        )
                    } else {
                        val key = "${rule.id}_${activeDateKey}_$packageName"
                        val baseUsed = (usageMutable ?: prunedUsage)[key] ?: 0L
                        val usedMillis = if (addUsageMillis > 0L) {
                            val next = baseUsed + addUsageMillis
                            val map = usageMutable ?: prunedUsage.toMutableMap().also { usageMutable = it }
                            map[key] = next
                            next
                        } else {
                            baseUsed
                        }
                        if (usedMillis >= appLimit.maxUsageMillis) {
                            return EvaluationResult(
                                blocked = true,
                                blockReason = "Rule ${rule.name}: Time Limit Reached!",
                                updatedUsageMap = usageMutable
                            )
                        }
                        hasTrackedLimit = true
                        val remaining = (appLimit.maxUsageMillis - usedMillis).coerceAtLeast(1L)
                        minNextWake = minDelay(minNextWake, remaining)
                        val ruleEndDelay = millisUntilActiveRuleEnds(rule, cal, nowMillis)
                        minNextWake = minDelay(minNextWake, ruleEndDelay)
                    }
                } else if (appLimit != null) {
                    val key = "${rule.id}_${activeDateKey}_$packageName"
                    val baseUsed = (usageMutable ?: prunedUsage)[key] ?: 0L
                    val usedMillis = if (addUsageMillis > 0L) {
                        val next = baseUsed + addUsageMillis
                        val map = usageMutable ?: prunedUsage.toMutableMap().also { usageMutable = it }
                        map[key] = next
                        next
                    } else {
                        baseUsed
                    }
                    if (usedMillis >= appLimit.maxUsageMillis) {
                        return EvaluationResult(
                            blocked = true,
                            blockReason = "Rule ${rule.name}: App Blocked!",
                            updatedUsageMap = usageMutable
                        )
                    }
                    hasTrackedLimit = true
                    val remaining = (appLimit.maxUsageMillis - usedMillis).coerceAtLeast(1L)
                    minNextWake = minDelay(minNextWake, remaining)
                    val ruleEndDelay = millisUntilActiveRuleEnds(rule, cal, nowMillis)
                    minNextWake = minDelay(minNextWake, ruleEndDelay)
                }
            } else {
                val affectsPackage = rule.isWhitelist || appLimit != null
                if (affectsPackage && rule.daysOfWeek.isNotEmpty()) {
                    val nextStartDelay = millisUntilNextRuleStart(rule, cal, nowMillis)
                    minNextWake = minDelay(minNextWake, nextStartDelay)
                }
            }
        }

        return EvaluationResult(
            blocked = false,
            updatedUsageMap = usageMutable,
            hasTrackedLimit = hasTrackedLimit,
            nextWakeDelayMillis = minNextWake
        )
    }

    private fun minDelay(current: Long?, candidate: Long?): Long? {
        if (candidate == null || candidate <= 0L) return current
        return if (current == null || candidate < current) candidate else current
    }

    private fun millisUntilNextRuleStart(rule: Rule, nowCal: Calendar, nowMillis: Long): Long? {
        val startMin = parseTimeToMinutes(rule.startTime) ?: return null
        val endMin = parseTimeToMinutes(rule.endTime) ?: return null
        val effectiveStartMin = if (startMin == endMin) 0 else startMin

        val candidate = nowCal.clone() as Calendar
        candidate.set(Calendar.SECOND, 0)
        candidate.set(Calendar.MILLISECOND, 0)

        for (dayOffset in 0..7) {
            if (dayOffset > 0) {
                candidate.timeInMillis = nowMillis
                candidate.add(Calendar.DAY_OF_YEAR, dayOffset)
                candidate.set(Calendar.SECOND, 0)
                candidate.set(Calendar.MILLISECOND, 0)
            }
            val dow = candidate.get(Calendar.DAY_OF_WEEK)
            if (!rule.daysOfWeek.contains(dow)) continue

            candidate.set(Calendar.HOUR_OF_DAY, effectiveStartMin / 60)
            candidate.set(Calendar.MINUTE, effectiveStartMin % 60)
            val startMillis = candidate.timeInMillis
            if (startMillis > nowMillis) {
                return startMillis - nowMillis
            }
        }
        return null
    }

    private fun millisUntilActiveRuleEnds(rule: Rule, nowCal: Calendar, nowMillis: Long): Long? {
        val startMin = parseTimeToMinutes(rule.startTime) ?: return null
        val endMin = parseTimeToMinutes(rule.endTime) ?: return null

        val candidate = nowCal.clone() as Calendar
        candidate.set(Calendar.SECOND, 0)
        candidate.set(Calendar.MILLISECOND, 0)

        if (startMin == endMin) {
            candidate.add(Calendar.DAY_OF_YEAR, 1)
            candidate.set(Calendar.HOUR_OF_DAY, 0)
            candidate.set(Calendar.MINUTE, 0)
            val diff = candidate.timeInMillis - nowMillis
            return if (diff > 0L) diff else null
        }

        val currentMin = nowCal.get(Calendar.HOUR_OF_DAY) * 60 + nowCal.get(Calendar.MINUTE)
        val endExclusiveMin = endMin + 1
        if (startMin > endMin && currentMin >= startMin) {
            candidate.add(Calendar.DAY_OF_YEAR, 1)
        }
        if (endExclusiveMin >= 1440) {
            candidate.add(Calendar.DAY_OF_YEAR, 1)
            candidate.set(Calendar.HOUR_OF_DAY, 0)
            candidate.set(Calendar.MINUTE, 0)
        } else {
            candidate.set(Calendar.HOUR_OF_DAY, endExclusiveMin / 60)
            candidate.set(Calendar.MINUTE, endExclusiveMin % 60)
        }
        val diff = candidate.timeInMillis - nowMillis
        return if (diff > 0L) diff else null
    }
}

package com.mhm.curbstomp.data.models

import java.util.UUID

data class InstantFocus(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Instant Focus",
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val appLimits: List<AppLimit>,
    val isActive: Boolean = true,
    val isWhitelist: Boolean = false,
    val isPaused: Boolean = false,
    val remainingMillis: Long = 0L
)

package com.mhm.curbstomp.data.models

import java.util.UUID

data class Rule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New Rule",
    // Format: "HH:mm" (24-hour)
    val startTime: String,
    val endTime: String,
    // Days of week from Calendar (e.g., Calendar.MONDAY)
    val daysOfWeek: List<Int>,
    val appLimits: List<AppLimit>,
    val isWhitelist: Boolean = false,
    val isActive: Boolean = true
)

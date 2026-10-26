package dev.multiprompt.companion.ui

import java.time.Instant
import java.time.ZoneId

internal fun sessionActivityLabel(activitySeconds: Long, nowSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val elapsed = (nowSeconds - activitySeconds).coerceAtLeast(0)
    val today = Instant.ofEpochSecond(nowSeconds).atZone(zone).toLocalDate()
    val day = Instant.ofEpochSecond(activitySeconds).atZone(zone).toLocalDate()
    return when {
        elapsed < 60 -> "Now"
        elapsed < 3600 -> "${elapsed / 60}m"
        day == today -> "${elapsed / 3600}h"
        day == today.minusDays(1) -> "Yesterday"
        else -> "${java.time.temporal.ChronoUnit.DAYS.between(day, today).coerceAtLeast(0)}d"
    }
}

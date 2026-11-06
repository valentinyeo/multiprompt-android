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

internal fun lastBatchStart(roles: List<String>): Int? {
    if (roles.isEmpty()) return null
    val lastIsUser = roles.last() == "user"
    var start = roles.size - 1
    while (start > 0 && (roles[start - 1] == "user") == lastIsUser) start--
    return start
}

internal fun batchTimeLabel(timestamp: String?, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
    val instant = timestamp?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    val time = instant.atZone(zone)
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val clock = time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    return when (time.toLocalDate()) {
        today -> "Today $clock"
        today.minusDays(1) -> "Yesterday $clock"
        else -> time.format(java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm", java.util.Locale.ENGLISH))
    }
}

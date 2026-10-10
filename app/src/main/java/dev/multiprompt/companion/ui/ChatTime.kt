package dev.multiprompt.companion.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Friendly local time for a transcript message, like the desktop Chat View: "11:27" today,
 * "Yesterday 18:04", "Mon 18:04" within the last week, "3 Oct 18:04" this year, else with the year.
 * Returns the input unchanged when it is not an ISO instant.
 */
fun formatChatTime(
    iso: String,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val instant = runCatching { Instant.parse(iso.trim()) }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(iso.trim()).toInstant() }.getOrNull()
        ?: return iso
    val local = instant.atZone(zone)
    val today: LocalDate = now.atZone(zone).toLocalDate()
    val date = local.toLocalDate()
    val clock = DateTimeFormatter.ofPattern("HH:mm", locale).format(local)
    val daysAgo = today.toEpochDay() - date.toEpochDay()
    return when {
        daysAgo <= 0L -> clock
        daysAgo == 1L -> "Yesterday $clock"
        daysAgo in 2L..6L -> DateTimeFormatter.ofPattern("EEE HH:mm", locale).format(local)
        date.year == today.year -> DateTimeFormatter.ofPattern("d MMM HH:mm", locale).format(local)
        else -> DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", locale).format(local)
    }
}

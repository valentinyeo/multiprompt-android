package dev.multiprompt.companion.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Fast, offline parsing for the common reminder phrases used by inbox triage. */
object ReminderTimeParser {
    private val relativePattern = Regex(
        "^(?:in\\s+)?(\\d+|one|two|three|four|five|six|seven|eight|nine|ten|thirty)\\s*" +
            "(m|min|mins|minute|minutes|h|hr|hrs|hour|hours|d|day|days|w|week|weeks)$",
    )
    private val isoPattern = Regex("^(\\d{4}-\\d{2}-\\d{2})(?:[ t]+(.+))?$")
    private val clockPattern = Regex("^(?:at\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?$")
    private val weekdayPattern = Regex(
        "^(?:next\\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)(?:\\s+(?:at\\s+)?(.+))?$",
    )

    fun laterToday(now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime = when {
        now.isBefore(atTime(now, 17, 0)) -> atTime(now, 17, 0)
        now.isBefore(atTime(now, 21, 0)) -> atTime(now, 21, 0)
        else -> tomorrowMorning(now)
    }

    fun tomorrowMorning(now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime =
        atTime(now.plusDays(1), 9, 0)

    fun inDaysMorning(days: Long, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime =
        atTime(now.plusDays(days), 9, 0)

    fun parse(input: String, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? {
        val value = input.lowercase(Locale.US).trim().replace(Regex("\\s+"), " ")
        if (value.isBlank()) return null

        when (value) {
            "later", "later today" -> return laterToday(now)
            "tonight", "this evening" -> return nextOccurrence(now, LocalTime.of(21, 0))
            "tomorrow", "tomorrow morning" -> return tomorrowMorning(now)
            "day after tomorrow", "in two days" -> return inDaysMorning(2, now)
        }

        parseNamedDay(value, "tomorrow", 1, now)?.let { return it }
        parseNamedDay(value, "day after tomorrow", 2, now)?.let { return it }
        parseNamedDay(value, "in two days", 2, now)?.let { return it }

        relativePattern.matchEntire(value)?.let { match ->
            val amount = number(match.groupValues[1]) ?: return null
            return when (match.groupValues[2]) {
                "m", "min", "mins", "minute", "minutes" -> now.plusMinutes(amount)
                "h", "hr", "hrs", "hour", "hours" -> now.plusHours(amount)
                "d", "day", "days" -> now.plusDays(amount)
                "w", "week", "weeks" -> now.plusWeeks(amount)
                else -> null
            }
        }

        isoPattern.matchEntire(value)?.let { match ->
            val date = try {
                LocalDate.parse(match.groupValues[1], DateTimeFormatter.ISO_LOCAL_DATE)
            } catch (_: DateTimeParseException) {
                return null
            }
            val time = match.groupValues[2].takeIf(String::isNotBlank)?.let(::parseClock)
                ?: LocalTime.of(9, 0)
            return date.atTime(time).atZone(now.zone).takeIf { it.isAfter(now) }
        }

        weekdayPattern.matchEntire(value)?.let { match ->
            val day = runCatching { DayOfWeek.valueOf(match.groupValues[1].uppercase(Locale.US)) }
                .getOrNull() ?: return null
            val time = match.groupValues[2].takeIf(String::isNotBlank)?.let(::parseClock)
                ?: LocalTime.of(9, 0)
            var target = now.with(TemporalAdjusters.next(day)).toLocalDate().atTime(time).atZone(now.zone)
            if (!target.isAfter(now)) target = target.plusWeeks(1)
            return target
        }

        parseClock(value)?.let { return nextOccurrence(now, it) }
        return null
    }

    fun format(time: ZonedDateTime): String = time.format(
        DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a", Locale.getDefault()),
    )

    private fun parseNamedDay(
        value: String,
        phrase: String,
        days: Long,
        now: ZonedDateTime,
    ): ZonedDateTime? {
        if (!value.startsWith("$phrase ")) return null
        val remainder = value.removePrefix(phrase).trim()
        val time = parseClock(remainder) ?: return null
        return now.plusDays(days).toLocalDate().atTime(time).atZone(now.zone)
    }

    private fun parseClock(value: String): LocalTime? {
        val match = clockPattern.matchEntire(value.trim()) ?: return null
        var hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].ifBlank { "0" }.toIntOrNull() ?: return null
        val meridiem = match.groupValues[3]
        if (minute !in 0..59) return null
        if (meridiem.isNotBlank()) {
            if (hour !in 1..12) return null
            if (hour == 12) hour = 0
            if (meridiem == "pm") hour += 12
        } else if (hour !in 0..23) {
            return null
        }
        return LocalTime.of(hour, minute)
    }

    private fun nextOccurrence(now: ZonedDateTime, time: LocalTime): ZonedDateTime {
        val today = now.toLocalDate().atTime(time).atZone(now.zone)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    private fun atTime(value: ZonedDateTime, hour: Int, minute: Int): ZonedDateTime = value
        .withHour(hour)
        .withMinute(minute)
        .withSecond(0)
        .withNano(0)

    private fun number(value: String): Long? = value.toLongOrNull() ?: when (value) {
        "one" -> 1
        "two" -> 2
        "three" -> 3
        "four" -> 4
        "five" -> 5
        "six" -> 6
        "seven" -> 7
        "eight" -> 8
        "nine" -> 9
        "ten" -> 10
        "thirty" -> 30
        else -> null
    }
}

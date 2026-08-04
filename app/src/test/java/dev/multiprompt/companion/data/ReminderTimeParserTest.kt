package dev.multiprompt.companion.data

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderTimeParserTest {
    private val zone = ZoneId.of("Europe/Berlin")

    @Test
    fun laterTodayMovesFromFiveToNineThenTomorrowMorning() {
        assertEquals(at(2026, 8, 4, 17), ReminderTimeParser.laterToday(at(2026, 8, 4, 10)))
        assertEquals(at(2026, 8, 4, 21), ReminderTimeParser.laterToday(at(2026, 8, 4, 18)))
        assertEquals(at(2026, 8, 5, 9), ReminderTimeParser.laterToday(at(2026, 8, 4, 22)))
    }

    @Test
    fun parsesRelativeDurations() {
        val now = at(2026, 8, 4, 10)
        assertEquals(now.plusMinutes(30), ReminderTimeParser.parse("in 30 minutes", now))
        assertEquals(now.plusHours(2), ReminderTimeParser.parse("2h", now))
        assertEquals(now.plusDays(5), ReminderTimeParser.parse("in five days", now))
    }

    @Test
    fun parsesNamedDaysAndTimes() {
        val now = at(2026, 8, 4, 10)
        assertEquals(at(2026, 8, 5, 15), ReminderTimeParser.parse("tomorrow at 3pm", now))
        assertEquals(at(2026, 8, 6, 9), ReminderTimeParser.parse("in two days", now))
        assertEquals(at(2026, 8, 7, 9, 30), ReminderTimeParser.parse("Friday 9:30", now))
    }

    @Test
    fun parsesClockAndIsoDate() {
        val now = at(2026, 8, 4, 22)
        assertEquals(at(2026, 8, 5, 21, 30), ReminderTimeParser.parse("9:30 pm", now))
        assertEquals(at(2026, 8, 8, 17), ReminderTimeParser.parse("2026-08-08 17:00", now))
    }

    @Test
    fun rejectsUnknownAndPastDate() {
        val now = at(2026, 8, 4, 10)
        assertNull(ReminderTimeParser.parse("after lunch maybe", now))
        assertNull(ReminderTimeParser.parse("2026-08-03 10:00", now))
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)
}

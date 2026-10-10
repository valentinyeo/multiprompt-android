package dev.multiprompt.companion.ui

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTimeTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = Instant.parse("2026-10-10T12:00:00Z")
    private fun fmt(iso: String) = formatChatTime(iso, now, zone, Locale.ENGLISH)

    @Test fun todayShowsLocalClock() = assertEquals("11:27", fmt("2026-10-10T09:27:27.461Z"))
    @Test fun yesterdayIsLabelled() = assertEquals("Yesterday 18:04", fmt("2026-10-09T16:04:00Z"))
    @Test fun lastWeekShowsWeekday() = assertEquals("Mon 18:04", fmt("2026-10-05T16:04:00Z"))
    @Test fun olderThisYearShowsDate() = assertEquals("3 Jun 18:04", fmt("2026-06-03T16:04:00Z"))
    @Test fun otherYearShowsYear() = assertEquals("3 Jun 2025 18:04", fmt("2025-06-03T16:04:00Z"))
    @Test fun localMidnightBoundaryUsesLocalDay() = assertEquals("Yesterday 00:30", fmt("2026-10-08T22:30:00Z"))
    @Test fun garbageIsReturnedUnchanged() = assertEquals("soon", fmt("soon"))
}

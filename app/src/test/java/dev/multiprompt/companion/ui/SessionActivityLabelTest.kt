package dev.multiprompt.companion.ui

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionActivityLabelTest {
    @Test fun shortTimesAndYesterdayFollowLocalDay() {
        val now = Instant.parse("2026-10-26T14:00:00Z").epochSecond
        val zone = ZoneId.of("UTC")
        assertEquals("Now", sessionActivityLabel(now + 10, now, zone))
        assertEquals("38m", sessionActivityLabel(now - 38 * 60, now, zone))
        assertEquals("2h", sessionActivityLabel(now - 2 * 3600, now, zone))
        assertEquals("Yesterday", sessionActivityLabel(now - 24 * 3600, now, zone))
        assertEquals("2d", sessionActivityLabel(now - 48 * 3600, now, zone))
        assertEquals("Yesterday", sessionActivityLabel(now - 2 * 3600, now, ZoneId.of("Australia/Sydney")))
    }
}

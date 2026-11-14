package dev.multiprompt.companion.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateScheduleTest {
    private val now = 10_000_000_000L
    private val fifteenMinutes = 15 * 60 * 1000L

    @Test fun intervalIsFifteenMinutes() {
        assertEquals(fifteenMinutes, UpdateSchedule.CHECK_INTERVAL_MS)
    }

    @Test fun firstRunChecks() {
        assertTrue(UpdateSchedule.shouldCheckNow(0L, now))
    }

    @Test fun returningUnderFifteenMinutesRunsNoCheck() {
        assertFalse(UpdateSchedule.shouldCheckNow(now - fifteenMinutes + 1, now))
        assertFalse(UpdateSchedule.shouldCheckNow(now, now))
    }

    @Test fun returningAtExactlyFifteenMinutesChecks() {
        assertTrue(UpdateSchedule.shouldCheckNow(now - fifteenMinutes, now))
    }

    @Test fun returningAfterFifteenMinutesChecks() {
        assertTrue(UpdateSchedule.shouldCheckNow(now - fifteenMinutes - 1, now))
        assertTrue(UpdateSchedule.shouldCheckNow(now - 3_600_000L, now))
    }

    @Test fun clockMovedBackwardsChecks() {
        assertTrue(UpdateSchedule.shouldCheckNow(now + 1, now))
        assertTrue(UpdateSchedule.shouldCheckNow(now + 86_400_000L, now))
    }
}

package dev.multiprompt.companion.account

import org.junit.Assert.*
import org.junit.Test

class VisitDwellTest {
    @Test fun nineSecondsRecordsNothingTenSecondsRecordsOneVisitWithFrontStartTime() {
        val startedAt = 1_800_000_000_000L
        val dwell = VisitDwell(startedAt, 500)
        assertNull(dwell.visitAt(500))
        assertNull(dwell.visitAt(9_500))
        assertNull(dwell.visitAt(10_499))
        assertEquals(startedAt, dwell.visitAt(10_500))
        assertNull(dwell.visitAt(20_500))
    }

    @Test fun newFrontPeriodStartsFreshDwellAndUsesMonotonicElapsedTime() {
        val first = VisitDwell(1_800_000_000_000L, 100)
        assertNull(first.visitAt(9_100))
        val next = VisitDwell(1_799_000_000_000L, 9_100)
        assertNull(next.visitAt(18_100))
        assertEquals(1_799_000_000_000L, next.visitAt(19_100))
    }
}

package dev.multiprompt.companion.terminal

import org.junit.Assert.*
import org.junit.Test

class TerminalInputTrackerTest {
    @Test fun modifierQueriesDoNotStampAndProcessedInputResetDoes() {
        var stamps = 0
        val tracker = TerminalInputTracker { stamps++ }
        repeat(3) {
            assertFalse(tracker.isCtrlActive())
            assertFalse(tracker.isAltActive())
            assertFalse(tracker.isShiftActive())
        }
        assertEquals(0, stamps)
        tracker.clearTransients()
        assertEquals(1, stamps)
        tracker.clearTransients()
        assertEquals(2, stamps)
    }
}

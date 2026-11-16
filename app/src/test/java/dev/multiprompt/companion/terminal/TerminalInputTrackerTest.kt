package dev.multiprompt.companion.terminal

import org.junit.Assert.*
import org.junit.Test

class TerminalInputTrackerTest {
    @Test fun togglesStayActiveUntilInputAndCanBeCancelled() {
        var stamps = 0
        val tracker = TerminalInputTracker { stamps++ }
        tracker.toggleCtrl()
        tracker.toggleAlt()
        repeat(3) {
            assertTrue(tracker.isCtrlActive())
            assertTrue(tracker.isAltActive())
            assertFalse(tracker.isShiftActive())
        }
        assertEquals(0, stamps)
        tracker.toggleCtrl()
        tracker.toggleAlt()
        assertFalse(tracker.isCtrlActive())
        assertFalse(tracker.isAltActive())
        tracker.toggleCtrl()
        tracker.toggleAlt()
        tracker.clearTransients()
        assertFalse(tracker.isCtrlActive())
        assertFalse(tracker.isAltActive())
        assertEquals(1, stamps)
    }

    @Test fun modifiersAffectOnlyTheNextProcessedInput() {
        var stamps = 0
        val tracker = TerminalInputTracker { stamps++ }
        fun type(letter: Char): ByteArray {
            val bytes = TerminalKeys.character(letter.code, ctrl = tracker.isCtrlActive(), alt = tracker.isAltActive())
            tracker.clearTransients()
            return bytes
        }
        tracker.toggleCtrl()
        assertArrayEquals(byteArrayOf(0x03), type('c'))
        assertArrayEquals(byteArrayOf(0x64), type('d'))
        tracker.toggleAlt()
        assertArrayEquals(byteArrayOf(0x1b, 0x78), type('x'))
        assertArrayEquals(byteArrayOf(0x6c), type('l'))
        assertFalse(tracker.isCtrlActive())
        assertFalse(tracker.isAltActive())
        assertEquals(4, stamps)
    }

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

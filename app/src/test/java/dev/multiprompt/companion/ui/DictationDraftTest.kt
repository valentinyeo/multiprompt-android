package dev.multiprompt.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DictationDraftTest {
    @Test
    fun cancelWithAnEmptyBoxLeavesItEmpty() {
        val before = DictationDraft.captured("")

        assertEquals("buy milk", before.withSpoken("buy milk"))
        assertEquals("", before.restored())
    }

    @Test
    fun cancelWithAnExistingDraftRestoresThatDraft() {
        val before = DictationDraft.captured("Fix the failing test")

        assertEquals("Fix the failing test buy milk", before.withSpoken("buy milk"))
        assertEquals("Fix the failing test", before.restored())
    }

    @Test
    fun cancelAfterInterimTextDropsTheInterimText() {
        val before = DictationDraft.captured("Review this")

        assertEquals("Review this buy milk", before.withSpoken("buy milk"))
        assertEquals("Review this", before.restored())
    }

    @Test
    fun cancelAfterAFinalSegmentDropsTheFinalText() {
        val before = DictationDraft.captured("")

        assertEquals("buy milk and eggs.", before.withSpoken("buy milk and eggs."))
        assertEquals("", before.restored())
    }

    @Test
    fun restoreKeepsTheDraftExactlyAsItWasWritten() {
        val before = DictationDraft.captured("First line\n\n")

        assertEquals("First line", before.appendBase)
        assertEquals("First line\n\n", before.restored())
    }
}

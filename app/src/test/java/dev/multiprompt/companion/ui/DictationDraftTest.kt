package dev.multiprompt.companion.ui

import dev.multiprompt.companion.dictation.DictationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun spokenTextTouchesTheBoxOnlyWhileDictationIsActive() {
        val before = DictationDraft.captured("Fix the failing test")

        assertEquals(
            "Fix the failing test buy milk",
            DictationDraft.spokenPrompt(before, "buy milk", DictationStatus.LISTENING, canceled = false),
        )
        assertEquals(
            "Fix the failing test buy milk",
            DictationDraft.spokenPrompt(before, "  buy milk  ", DictationStatus.FINISHING, canceled = false),
        )
        assertNull(DictationDraft.spokenPrompt(before, "buy milk", DictationStatus.IDLE, canceled = false))
        assertNull(DictationDraft.spokenPrompt(before, "buy milk", DictationStatus.FAILED, canceled = false))
        assertNull(DictationDraft.spokenPrompt(before, "buy milk", DictationStatus.LISTENING, canceled = true))
        assertNull(DictationDraft.spokenPrompt(before, "  ", DictationStatus.LISTENING, canceled = false))
    }

    @Test
    fun aTranscriptCollectedJustBeforeCancelCannotReapplySpokenText() {
        val before = DictationDraft.captured("Fix the failing test")

        assertEquals(
            "Fix the failing test buy milk",
            DictationDraft.spokenPrompt(before, "buy milk", DictationStatus.LISTENING, canceled = false),
        )
        assertNull(DictationDraft.spokenPrompt(before, "buy milk", DictationStatus.LISTENING, canceled = true))
        assertEquals("Fix the failing test", before.restored())
    }
}

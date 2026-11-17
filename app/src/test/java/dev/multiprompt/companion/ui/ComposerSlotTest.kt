package dev.multiprompt.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerSlotTest {
    @Test
    fun workingWithEmptyDraftShowsStop() {
        assertEquals(ComposerSlot.STOP, composerSlot(working = true, busy = false, draftEmpty = true))
    }

    @Test
    fun typingTurnsStopBackIntoSend() {
        assertEquals(ComposerSlot.SEND, composerSlot(working = true, busy = false, draftEmpty = false))
    }

    @Test
    fun idleChatAlwaysShowsSend() {
        assertEquals(ComposerSlot.SEND, composerSlot(working = false, busy = false, draftEmpty = true))
        assertEquals(ComposerSlot.SEND, composerSlot(working = false, busy = false, draftEmpty = false))
    }

    @Test
    fun aSendInFlightKeepsTheSendSlot() {
        assertEquals(ComposerSlot.SEND, composerSlot(working = true, busy = true, draftEmpty = true))
    }

    @Test
    fun oneSlotShowsExactlyOneButtonForEveryCombination() {
        for (working in listOf(false, true)) for (busy in listOf(false, true)) for (empty in listOf(false, true)) {
            val stop = composerSlot(working, busy, empty) == ComposerSlot.STOP
            assertEquals(working && !busy && empty, stop)
        }
    }
}

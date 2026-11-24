package dev.multiprompt.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionFooterSummaryTest {
    @Test
    fun liveWorkReplacesThePreviousReply() {
        assertEquals(
            "Working: Reading CI logs",
            SessionPreview.line("⏺ Previous reply.\n✻ Reading CI logs… (3s · esc to interrupt)\nOpus 5.5 medium | hypertask"),
        )
    }

    @Test
    fun modelAndShellOnlyHaveNoInventedReply() {
        for (raw in listOf(
            "[CAVEMAN] Opus 5.5 medium hypertask",
            "gpt-6.1-sol medium | ceo",
            "valentin@vps:~/projects$",
            "❯ fix this",
            "$ git status",
        )) {
            assertEquals(raw, "", SessionPreview.line(raw))
        }
    }

    @Test
    fun newReplyReplacesEarlierWork() {
        assertEquals("Shipped safely.", SessionPreview.line("✻ Reading logs…\n⏺ Shipped safely. Extra details.\n❯\nOpus 5.5 medium | ceo"))
    }
}

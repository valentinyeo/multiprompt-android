package dev.multiprompt.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionPreviewTest {
    @Test
    fun plainLineIsKept() {
        assertEquals("Ready for your next prompt", SessionPreview.line("Ready for your next prompt"))
    }

    @Test
    fun lastNonBlankLineWins() {
        assertEquals("Tests are green", SessionPreview.line("Building\n\nTests are green\n\n  \n"))
    }

    @Test
    fun boxDrawingAndPromptGlyphsAreStripped() {
        assertEquals("", SessionPreview.line("╭──────╮\n│ ❯ fix the inbox │\n╰──────╯"))
    }

    @Test
    fun terminalChromeLinesAreSkipped() {
        val raw = "Done, pushed the branch\n? for shortcuts\n⏵⏵ bypass permissions on (shift+tab to cycle)"
        assertEquals("Done, pushed the branch", SessionPreview.line(raw))
    }

    @Test
    fun whitespaceIsCollapsed() {
        assertEquals("a b c", SessionPreview.line("a    b \t c"))
    }

    @Test
    fun lastReplyStartsWithItsFirstSentenceNotTheFooter() {
        val raw = """
            ⏺ Old reply.
            ❯ What changed?
            ⏺ Lauren Tan profile is done,
              checked and pushed. More details follow.
            ✻ Baked for 27s
            ❯
            [CAVEMAN] Opus 5.5 medium hypertask
        """.trimIndent()
        assertEquals("Lauren Tan profile is done, checked and pushed.", SessionPreview.line(raw))
    }

    @Test
    fun liveWorkReplacesThePreviousReply() {
        assertEquals("Working: Reading CI logs", SessionPreview.line("⏺ Previous reply.\n✻ Reading CI logs… (3s · esc to interrupt)\nOpus 5.5 medium | hypertask"))
    }

    @Test
    fun modelAndShellOnlyHaveNoInventedReply() {
        for (raw in listOf("[CAVEMAN] Opus 5.5 medium hypertask", "gpt-6.1-sol medium | ceo", "valentin@vps:~/projects$", "❯ fix this", "$ git status")) {
            assertEquals(raw, "", SessionPreview.line(raw))
        }
    }

    @Test
    fun newReplyReplacesEarlierWork() {
        assertEquals("Shipped safely.", SessionPreview.line("✻ Reading logs…\n⏺ Shipped safely. Extra details.\n❯\nOpus 5.5 medium | ceo"))
    }

    @Test
    fun emptyInputGivesEmptyPreview() {
        assertEquals("", SessionPreview.line(""))
        assertEquals("", SessionPreview.line("╭──╮\n│  │\n╰──╯"))
    }
}

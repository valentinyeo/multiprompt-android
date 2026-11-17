package dev.multiprompt.companion.ui

import dev.multiprompt.companion.model.RowParts
import dev.multiprompt.companion.model.TmuxSession
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionPreviewTest {
    @Test
    fun plainPaneTextIsNotAReply() {
        for (raw in listOf("Ready for your next prompt", "/rename Big Boss Man", "/rename HTPR-8827\n/rename Big Boss Man")) {
            assertEquals(raw, "", SessionPreview.line(raw))
        }
    }

    @Test
    fun lastReplyWins() {
        assertEquals("Tests are green", SessionPreview.line("⏺ Building\n\n⏺ Tests are green\n\n  \n"))
    }

    @Test
    fun toolCallsAreNotReplies() {
        assertEquals("Checked the logs.", SessionPreview.line("⏺ Checked the logs.\n⏺ Bash(ls -la)\n  ⎿ done"))
    }

    @Test
    fun boxDrawingAndPromptGlyphsAreStripped() {
        assertEquals("", SessionPreview.line("╭──────╮\n│ ❯ fix the inbox │\n╰──────╯"))
    }

    @Test
    fun terminalChromeLinesAreSkipped() {
        val raw = "⏺ Done, pushed the branch\n? for shortcuts\n⏵⏵ bypass permissions on (shift+tab to cycle)"
        assertEquals("Done, pushed the branch", SessionPreview.line(raw))
    }

    @Test
    fun terminalReplyCodesAreNotShown() {
        val raw = "⏺ House rules reloaded.\n^[[?61;1;6;7;21;22;23;24;28;32;42;52c^[[>0;10;1c"
        assertEquals("House rules reloaded.", SessionPreview.line(raw))
    }

    @Test
    fun terminalReplyCodesAreCutFromText() {
        assertEquals("Ready", SessionPreview.line("\u001b[?62;22c⏺ Ready"))
    }

    @Test
    fun whitespaceIsCollapsed() {
        assertEquals("a b c", SessionPreview.line("⏺ a    b \t c"))
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

    private fun session(
        parts: RowParts? = RowParts("", "", "", 100, 90),
        preview: String = "",
        replyWaiting: Boolean? = true,
        activity: Long = 100,
    ) = TmuxSession("host", "live-1", 1, 0, activity, preview = preview, replyAtEpochSeconds = 90, replyWaiting = replyWaiting, rowParts = parts)

    @Test
    fun waitingQuestionWinsOverStepAndReply() {
        val parts = RowParts("Ship it?", "Fix the bug", "Done.", 100, 90)
        assertEquals("Ship it?", SessionRowLine.line(session(parts, preview = "⏺ Old screen reply")))
    }

    @Test
    fun workingChatShowsItsCurrentStep() {
        val parts = RowParts("", "Fix the bug", "Done.", 100, 90)
        assertEquals("Fix the bug", SessionRowLine.line(session(parts, replyWaiting = false)))
    }

    @Test
    fun liveScreenActivityBeatsTheStoredStep() {
        val parts = RowParts("", "Fix the bug", "Done.", 100, 90)
        val raw = "⏺ Previous reply.\n✻ Reading CI logs… (3s · esc to interrupt)"
        assertEquals("Working: Reading CI logs", SessionRowLine.line(session(parts, preview = raw)))
    }

    @Test
    fun workingChatWithoutAStepShowsItsLastReply() {
        val parts = RowParts("", "", "Done.", 100, 90)
        assertEquals("Done.", SessionRowLine.line(session(parts, replyWaiting = false)))
    }

    @Test
    fun workingChatWithNothingStoredFallsBackToScreenText() {
        val parts = RowParts("", "", "", 100, 90)
        assertEquals("Old screen reply", SessionRowLine.line(session(parts, preview = "⏺ Old screen reply", replyWaiting = false)))
    }

    @Test
    fun idleChatShowsItsLastReply() {
        val parts = RowParts("", "Fix the bug", "Done.", 100, 90)
        assertEquals("Done.", SessionRowLine.line(session(parts)))
        assertEquals("Done.", SessionRowLine.line(session(parts, replyWaiting = null)))
    }

    @Test
    fun idleChatWithoutAReplyFallsBackToScreenText() {
        assertEquals("Old screen reply", SessionRowLine.line(session(preview = "⏺ Old screen reply")))
    }

    @Test
    fun staleStoredPartsFallBackToScreenText() {
        val parts = RowParts("Ship it?", "Fix the bug", "Done.", 100, 90)
        assertEquals("Old screen reply", SessionRowLine.line(session(parts, preview = "⏺ Old screen reply", activity = 150)))
    }

    @Test
    fun plainTmuxChatKeepsTheScreenText() {
        assertEquals("Old screen reply", SessionRowLine.line(session(parts = null, preview = "⏺ Old screen reply")))
        assertEquals("", SessionRowLine.line(session(parts = null)))
    }
}

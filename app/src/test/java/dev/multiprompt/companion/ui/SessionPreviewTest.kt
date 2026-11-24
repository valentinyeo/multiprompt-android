package dev.multiprompt.companion.ui

import dev.multiprompt.companion.model.AgentKind
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
    fun waitingChatShowsTheQuestionBox() {
        val raw = """
            ⏺ Let me ask about the account.
              Two routes are available.
            ────────────────────────────────────────
            Which route should we use?

            ❯ 1. Create a new account
              2. Use the current account
              3. Type something.
            ────────────────────────────────────────
            Enter to select · Tab/Arrow keys to navigate · Esc to cancel
        """.trimIndent()
        assertEquals("Which route should we use?", SessionPreview.line(raw, agent = AgentKind.CLAUDE))
    }

    @Test
    fun waitingQuestionBeatsThePreviousReply() {
        val raw = """
            ⏺ Previous reply.
            ────────────────────────────────────────
            Should I push this branch?
            ────────────────────────────────────────
            ❯
            [CAVEMAN] Opus 5.5 medium hypertask
        """.trimIndent()
        assertEquals("Should I push this branch?", SessionPreview.line(raw, agent = AgentKind.CLAUDE))
    }

    @Test
    fun workingChatShowsItsStepNotTheAnsweredQuestion() {
        val raw = """
            ⏺ Let me ask about the route.
            ────────────────────────────────────────
            Which route should we use?
            ❯ 1. Create a new account
              2. Use the current account
            ────────────────────────────────────────
            ✻ Reading CI logs… (3s · esc to interrupt)
        """.trimIndent()
        assertEquals("Working: Reading CI logs", SessionPreview.line(raw, agent = AgentKind.CLAUDE))
    }

    @Test
    fun hostedWorkingSignalSuppressesTheAnsweredQuestion() {
        val raw = """
            ⏺ Let me ask about the route.
            ────────────────────────────────────────
            Which route should we use?
            ❯ 1. Create a new account
            ────────────────────────────────────────
        """.trimIndent()
        assertEquals("Let me ask about the route.", SessionPreview.line(raw, agent = AgentKind.CLAUDE, replyWaiting = false))
        assertEquals("Which route should we use?", SessionPreview.line(raw, agent = AgentKind.CLAUDE, replyWaiting = true))
    }

    @Test
    fun staleQuestionDoesNotStickAfterTheAnswer() {
        val raw = """
            ⏺ Old reply.
            ❯ What changed?
            ⏺ Lauren Tan profile is done,
              checked and pushed. More details follow.
            ✻ Baked for 27s
            ❯
            [CAVEMAN] Opus 5.5 medium hypertask
        """.trimIndent()
        assertEquals(
            "Lauren Tan profile is done, checked and pushed.",
            SessionPreview.line(raw, agent = AgentKind.CLAUDE),
        )
    }

    @Test
    fun answeredQuestionBoxDoesNotStickAfterTheAnswer() {
        val raw = """
            ⏺ Let me ask about the route.
            ────────────────────────────────────────
            Which route should we use?
            ❯ 1. Create a new account
              2. Use the current account
            ────────────────────────────────────────
            Enter to select · Esc to cancel
            ⏺ Created the new account.
            ✻ Baked for 5s
            ❯
            [CAVEMAN] Opus 5.5 medium hypertask
        """.trimIndent()
        assertEquals("Created the new account.", SessionPreview.line(raw, agent = AgentKind.CLAUDE))
    }

    @Test
    fun wrappedReplyEndingInAQuestionMarkIsNotTheQuestion() {
        val raw = """
            ⏺ Lauren Tan profile is done,
              want me to push it?
            ❯
        """.trimIndent()
        assertEquals("Lauren Tan profile is done, want me to push it?", SessionPreview.line(raw, agent = AgentKind.CLAUDE))
    }

    @Test
    fun composerIsNeverTheRow() {
        assertEquals("Ready.", SessionPreview.line("⏺ Ready.\n╭──────╮\n│ ❯ fix the inbox │\n╰──────╯\n❯"))
        assertEquals("Done.", SessionPreview.line("⏺ Done.\n❯ Ship this on main?\n", agent = AgentKind.CLAUDE))
        assertEquals("Done.", SessionPreview.line("⏺ Done.\n› Ask Codex", agent = AgentKind.CODEX))
    }

    @Test
    fun waitingQuestionInTheLastReplyBeatsItsIntroductorySentence() {
        for (footer in listOf("", "✻ Baked for 5s\n")) {
            val raw = "⏺ The changes are ready. Should I push this branch?\n${footer}❯"
            assertEquals("Should I push this branch?", SessionPreview.line(raw, agent = AgentKind.CLAUDE))
        }
    }

    @Test
    fun wrappedComposerQuestionNeverBecomesTheWaitingQuestion() {
        for (prompt in listOf("❯", "›", ">")) {
            val raw = "⏺ Ready.\n$prompt Please explain the change\nand should we ship it?\n"
            assertEquals("Ready.", SessionPreview.line(raw, agent = AgentKind.CODEX))
        }
    }

    @Test
    fun userAnswerInvalidatesTheOldQuestionBeforeTheNextReply() {
        val raw = "⏺ Previous reply\nWhich route should we use?\n❯ Use the new account.\n❯"
        assertEquals("Previous reply", SessionPreview.line(raw, agent = AgentKind.CLAUDE))
    }

    @Test
    fun toolExecutionInvalidatesTheOldQuestion() {
        val raw = "⏺ Previous reply.\nWhich route should we use?\n⏺ Bash(create-account)\n  ⎿ running\n❯"
        assertEquals("Previous reply.", SessionPreview.line(raw, agent = AgentKind.CLAUDE))
    }

    @Test
    fun waitingQuestionBeatsTheSelectedOptionAndAnEmptyComposer() {
        val raw = "⏺ Previous reply.\nWhich route should we use?\n❯ 1. Create a new account?\n  2. Use the current account\n❯"
        assertEquals("Which route should we use?", SessionPreview.line(raw, agent = AgentKind.CLAUDE))
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
    fun emptyInputGivesEmptyPreview() {
        assertEquals("", SessionPreview.line(""))
        assertEquals("", SessionPreview.line("╭──╮\n│  │\n╰──╯"))
    }
}

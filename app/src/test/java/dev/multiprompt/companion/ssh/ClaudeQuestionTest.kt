package dev.multiprompt.companion.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ClaudeQuestionTest {
    private val screen = """
        Previous chat
        ────────────────────────────────
        ☐ Account
        Which account should we use?

        ❯ 1. Create a new account
             Keep work separate.
          2. Use the current account
             Keep the current setup.
          3. Type something.
        ────────────────────────────────
        Enter to select · Tab/Arrow keys to navigate · Esc to cancel
    """.trimIndent()

    @Test
    fun activeSingleSelectQuestionExposesConcreteAnswersWithDescriptions() {
        val question = requireNotNull(ClaudeQuestion.parse(screen))
        assertEquals("☐ Account Which account should we use?", question.title)
        assertEquals(listOf(ClaudeQuestion.Option(1, "Create a new account Keep work separate."),
            ClaudeQuestion.Option(2, "Use the current account Keep the current setup.")), question.options)
        assertEquals(question, ClaudeQuestion.parse(TmuxText.leftAligned(screen)))
        assertEquals("tmux send-keys -t 'proof:' 2", TmuxCommands.questionAnswer("proof", question, screen, 2))
    }

    @Test
    fun oldQuestionsAndDifferentQuestionsCannotReceiveKeys() {
        val question = requireNotNull(ClaudeQuestion.parse(screen))
        listOf("", screen + "\n❯ Next request", screen.replace("Which account", "Which profile"),
            screen.replace("❯ 1.", "  1.").replace("  3.", "❯ 3.")).forEach {
            assertFalse(runCatching { TmuxCommands.questionAnswer("proof", question, it, 2) }.isSuccess)
        }
        listOf(0, 3, 10).forEach {
            assertFalse(runCatching { TmuxCommands.questionAnswer("proof", question, screen, it) }.isSuccess)
        }
    }

    @Test
    fun prosePermissionsModelsAndMultiSelectAreNotSingleSelectQuestions() {
        assertNull(ClaudeQuestion.parse("1. First choice\n2. Second choice"))
        assertNull(ClaudeQuestion.parse(screen.replace("Type something.", "No")))
        assertNull(ClaudeQuestion.parse(screen.replace("Enter to select", "Space to toggle")))
        assertNull(ClaudeQuestion.parse(screen.replace("❯ 1.", "❯ 1. ☐")))
        assertNull(ClaudeQuestion.parse(screen.replace("3. Type something.", "4. Type something.")))
        assertNotNull(ClaudeQuestion.parse(screen.replace("Enter to select", "enter to select")))
    }
}

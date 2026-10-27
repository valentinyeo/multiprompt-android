package dev.multiprompt.companion.ssh

import dev.multiprompt.companion.reader.PendingQuestion
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredQuestionAnswerTest {
    private val question = PendingQuestion("call", 0, "Which route?", "Route", listOf(
        PendingQuestion.Option("First", ""), PendingQuestion.Option("Second", "")), false, true, "claude")
    private val screen = "Which route?\n❯ 1. First\n2. Second\n3. Type something.\nEnter to select · Esc to cancel"

    @Test fun singleAnswerUsesTheProviderKeyWithoutChangingItsLabel() {
        assertTrue(TmuxCommands.questionAnswer("proof", question, screen, setOf(1), "").endsWith(" 2"))
    }

    @Test fun otherIsQuotedLiteralInputNotShellOrProviderCommands() {
        val command = TmuxCommands.questionAnswer("proof", question, screen, emptySet(), "a'b; $(false)")
        assertTrue(command.contains(" -l -t "))
        assertTrue(command.contains(TmuxParser.shellQuote("a'b; $(false)")))
        assertTrue(command.endsWith(" Enter"))
    }

    @Test fun multipleAnswersToggleTheExactOptionsThenSubmit() {
        val command = TmuxCommands.questionAnswer("proof", question.copy(multiSelect = true), screen, setOf(0, 1), "")
        assertTrue(command.contains(" Space"))
        assertTrue(command.contains(" Down Space"))
        assertTrue(command.endsWith(" Enter"))
    }

    @Test(expected = IllegalStateException::class)
    fun staleQuestionCannotTypeIntoTheNewPrompt() {
        TmuxCommands.questionAnswer("proof", question, "New prompt", setOf(0), "")
    }
}

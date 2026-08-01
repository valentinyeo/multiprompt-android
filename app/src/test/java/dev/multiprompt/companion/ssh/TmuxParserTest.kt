package dev.multiprompt.companion.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxParserTest {
    @Test
    fun parsesStructuredTmuxRowsAndSortsNewestFirst() {
        val separator = TmuxParser.FIELD_SEPARATOR
        val output = buildString {
            append("older${separator}1${separator}0${separator}100\n")
            append("codex-main${separator}3${separator}2${separator}200\n")
        }

        val result = TmuxParser.parse("de", output)

        assertEquals(listOf("codex-main", "older"), result.map { it.name })
        assertEquals(2, result.first().attachedClients)
        assertEquals("Codex", result.first().agent.label)
    }

    @Test
    fun ignoresMalformedRows() {
        val result = TmuxParser.parse("de", "broken\nname\u001fnope\u001f0\u001f1\n")
        assertTrue(result.isEmpty())
    }

    @Test
    fun commandKeepsStderrVisible() {
        // Swallowing stderr turned every tmux failure into a silent empty list.
        val command = TmuxParser.command()
        assertTrue(command.startsWith("tmux list-sessions -F "))
        assertTrue("2>/dev/null" !in command && "|| true" !in command)
    }

    @Test
    fun shellQuoteCannotInjectCommands() {
        assertEquals("'a'\"'\"'; reboot'", TmuxParser.shellQuote("a'; reboot"))
    }
}

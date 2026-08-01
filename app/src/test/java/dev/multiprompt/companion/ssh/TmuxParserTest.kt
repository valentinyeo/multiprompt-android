package dev.multiprompt.companion.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxParserTest {
    @Test
    fun parsesStructuredTmuxRowsAndSortsNewestFirst() {
        val separator = TmuxParser.FIELD_SEPARATOR
        val output = buildString {
            append("${TmuxParser.START_MARKER}\n")
            append("older${separator}1${separator}0${separator}100\n")
            append("codex-main${separator}3${separator}2${separator}200\n")
            append("${TmuxParser.END_MARKER}\n")
        }

        val result = TmuxParser.parse("de", output)

        assertEquals(listOf("codex-main", "older"), result.map { it.name })
        assertEquals(2, result.first().attachedClients)
        assertEquals("Codex", result.first().agent.label)
        assertTrue(TmuxParser.hasEnvelope(output))
    }

    @Test
    fun ignoresMalformedRows() {
        val result = TmuxParser.parse("de", "broken\nname::MP_FIELD::nope::MP_FIELD::0::MP_FIELD::1\n")
        assertTrue(result.isEmpty())
    }

    @Test
    fun commandFramesOutputAndKeepsStderrVisible() {
        val command = TmuxParser.command()
        assertTrue(command.contains("tmux list-sessions -F "))
        assertTrue(command.contains(TmuxParser.START_MARKER))
        assertTrue(command.contains(TmuxParser.END_MARKER))
        assertTrue("2>/dev/null" !in command && "|| true" !in command)
    }

    @Test
    fun reportsMissingTmuxAndRejectsUnframedOutput() {
        val output = "${TmuxParser.START_MARKER}\n${TmuxParser.ERROR_PREFIX}tmux_not_found\n${TmuxParser.END_MARKER}\n"

        assertEquals("tmux_not_found", TmuxParser.error(output))
        assertTrue(TmuxParser.hasEnvelope(output))
        assertTrue(!TmuxParser.hasEnvelope(""))
    }

    @Test
    fun prefersPaneTitleOverTmuxName() {
        val s = TmuxParser.FIELD_SEPARATOR
        val output = "hypertasks-10${s}1${s}1${s}200${s}vmi3202882${s}\u2733 Add feedback button\n"

        val session = TmuxParser.parse("de", output).single()

        assertEquals("\u2733 Add feedback button", session.displayName)
        assertEquals("hypertasks-10", session.name)
    }

    @Test
    fun ignoresDefaultPaneTitleThatIsJustTheHostname() {
        // tmux leaves pane_title as the hostname when no agent set one; showing it would
        // label every idle session with the same useless string.
        val s = TmuxParser.FIELD_SEPARATOR
        val output = "multiprompt-android${s}1${s}0${s}200${s}vmi3202882${s}vmi3202882\n"

        val session = TmuxParser.parse("de", output).single()

        assertEquals("multiprompt-android", session.displayName)
        assertEquals("", session.title)
    }

    @Test
    fun keepsSeparatorsInsidePaneTitle() {
        val s = TmuxParser.FIELD_SEPARATOR
        val output = "cl-1${s}1${s}0${s}200${s}box${s}fix a${s}b\n"

        assertEquals("fix a${s}b", TmuxParser.parse("de", output).single().title)
    }

    @Test
    fun shellQuoteCannotInjectCommands() {
        assertEquals("'a'\"'\"'; reboot'", TmuxParser.shellQuote("a'; reboot"))
    }
}

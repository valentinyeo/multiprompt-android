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
    fun commandAsksForNineFieldsInTheOrderTheParserExpects() {
        val format = TmuxParser.command().substringAfter("display-message -p -t \"\$mp_session:\" -F '").substringBefore("'")

        assertEquals(
            listOf("#{session_name}", "#{session_windows}", "#{session_attached}",
                "#{session_activity}", "#{window_width}", "#{window_height}", "#{session_path}",
                "#{host}", "#{pane_title}"),
            format.split(TmuxParser.FIELD_SEPARATOR),
        )
    }

    @Test
    fun commandFramesOutputAndKeepsStderrVisible() {
        val command = TmuxParser.command()
        assertTrue(command.contains("tmux list-sessions -F "))
        assertTrue(command.contains("tmux capture-pane -p -J -S -12"))
        assertTrue(command.contains(TmuxParser.START_MARKER))
        assertTrue(command.contains(TmuxParser.END_MARKER))
        assertTrue("tmux list-sessions -F '#{session_name}' 2>/dev/null" !in command)
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
        val output = row("hypertasks-10", "1", "1", "200", "100", "30",
            "/home/valentin/projects/hypertasks", "vmi3202882", "\u2733 Add feedback button")

        val session = TmuxParser.parse("de", output).single()

        assertEquals("\u2733 Add feedback button", session.displayName)
        assertEquals("hypertasks-10", session.name)
        assertEquals(100, session.columns)
        assertEquals(30, session.rows)
        assertEquals("/home/valentin/projects/hypertasks", session.workingDirectory)
    }

    @Test
    fun ignoresDefaultPaneTitleThatIsJustTheHostname() {
        // tmux leaves pane_title as the hostname when no agent set one; showing it would
        // label every idle session with the same useless string.
        val output = row("multiprompt-android", "1", "0", "200", "70", "50", "/tmp", "vmi3202882", "vmi3202882")

        val session = TmuxParser.parse("de", output).single()

        assertEquals("multiprompt-android", session.displayName)
        assertEquals("", session.title)
        assertEquals(70, session.columns)
    }

    @Test
    fun keepsSeparatorsInsidePaneTitle() {
        val s = TmuxParser.FIELD_SEPARATOR
        val output = row("cl-1", "1", "0", "200", "98", "51", "/tmp", "box", "fix a${s}b")

        assertEquals("fix a${s}b", TmuxParser.parse("de", output).single().title)
    }

    @Test
    fun decodesHexPreviewForThePrecedingSession() {
        val output = row("cl-1", "1", "0", "200", "98", "51", "/tmp", "box", "work") +
            "${TmuxParser.PREVIEW_PREFIX}68656c6c6f0a776f726c64\n"

        assertEquals("hello\nworld", TmuxParser.parse("de", output).single().preview)
    }

    /** Mirrors the field order in [TmuxParser.command], so a reordered format fails loudly here. */
    private fun row(vararg fields: String) = fields.joinToString(TmuxParser.FIELD_SEPARATOR) + "\n"

    @Test
    fun shellQuoteCannotInjectCommands() {
        assertEquals("'a'\"'\"'; reboot'", TmuxParser.shellQuote("a'; reboot"))
    }
}

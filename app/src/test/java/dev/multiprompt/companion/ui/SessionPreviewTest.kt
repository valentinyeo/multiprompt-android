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
        assertEquals("fix the inbox", SessionPreview.line("╭──────╮\n│ > fix the inbox │\n╰──────╯"))
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
    fun emptyInputGivesEmptyPreview() {
        assertEquals("", SessionPreview.line(""))
        assertEquals("", SessionPreview.line("╭──╮\n│  │\n╰──╯"))
    }
}

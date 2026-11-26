package dev.multiprompt.companion.update

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNoteLinesTest {
    @Test
    fun previewKeepsTheFirstThreeChangesOfTheOfferedRelease() {
        val notes = listOf(
            "- MUBO-1 First change",
            "- MUBO-2 Second change",
            "- MUBO-3 Third change",
            "- MUBO-4 Fourth change",
            "- MUBO-5 Fifth change",
        ).joinToString("\n")

        assertEquals(
            listOf("MUBO-1 First change", "MUBO-2 Second change", "MUBO-3 Third change"),
            ReleaseNoteLines.preview(notes),
        )
        assertEquals(5, ReleaseNoteLines.changes(notes).size)
    }

    @Test
    fun shortNotesKeepEveryChangeAndStayComplete() {
        val notes = "- MUBO-1 Only change\n- MUBO-2 Second change"

        assertEquals(listOf("MUBO-1 Only change", "MUBO-2 Second change"), ReleaseNoteLines.preview(notes))
        assertEquals(ReleaseNoteLines.changes(notes), ReleaseNoteLines.preview(notes))
    }

    @Test
    fun markdownAndHtmlAreRemoved() {
        val notes = "- **MUBO-1** Chat view needs no <b>screen</b> text\n* `MUBO-2` Code font"

        assertEquals(
            listOf("MUBO-1 Chat view needs no screen text", "MUBO-2 Code font"),
            ReleaseNoteLines.changes(notes),
        )
    }

    @Test
    fun repeatedHeadersAndTicketOnlyFillerAreDropped() {
        val notes = """
            ## What's new
            - MUBO-1 Real change
            MUBO-2
            Release notes
        """.trimIndent()

        assertEquals(listOf("MUBO-1 Real change"), ReleaseNoteLines.changes(notes))
    }

    @Test
    fun repeatedChangesAppearOnce() {
        val notes = "- MUBO-1 Same change\n- MUBO-1 Same change\n- MUBO-2 Other change"

        assertEquals(listOf("MUBO-1 Same change", "MUBO-2 Other change"), ReleaseNoteLines.changes(notes))
    }

    @Test
    fun blankNotesOfferNoChanges() {
        assertEquals(emptyList<String>(), ReleaseNoteLines.changes(""))
        assertEquals(emptyList<String>(), ReleaseNoteLines.changes("  \n\n "))
    }
}

package dev.multiprompt.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionNoteTest {
    @Test
    fun parsesSenderTicketAndBody() {
        val note = SessionNote.parse("[from Hax Runner 10] IGSH-975 native journey is now fully green.\nSee https://example.com/run")!!
        assertEquals("Hax Runner 10", note.sender)
        assertEquals("IGSH-975", note.ticket)
        assertEquals("IGSH-975 native journey is now fully green.\nSee https://example.com/run", note.body)
    }

    @Test
    fun ticketIsOptional() {
        val note = SessionNote.parse("[from Chief Of Staff] ping")!!
        assertNull(note.ticket)
        assertEquals("Chief Of Staff to Mini Boss CEO", SessionNote.label(note, "Mini Boss CEO"))
    }

    @Test
    fun labelCarriesTicket() {
        val note = SessionNote.parse("[from Hax Runner 10] IGSH-975 done")!!
        assertEquals("Hax Runner 10 to Mini Boss CEO · IGSH-975", SessionNote.label(note, "Mini Boss CEO"))
        assertEquals("Hax Runner 10 · IGSH-975", SessionNote.label(note, ""))
    }

    @Test
    fun ordinaryMessagesAreNotNotes() {
        assertNull(SessionNote.parse("please check the build"))
        assertNull(SessionNote.parse("I wrote [from Hax Runner 10] in the middle"))
        assertNull(SessionNote.parse("[from Hax Runner 10]"))
        assertNull(SessionNote.parse("[from ] empty sender"))
        assertNull(SessionNote.parse("> quoted\n\nreply"))
    }
}

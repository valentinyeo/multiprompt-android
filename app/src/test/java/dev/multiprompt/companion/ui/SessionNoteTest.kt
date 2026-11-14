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

    @Test
    fun parsesCrossSessionEnvelope() {
        val note = SessionNote.parse(" \n<cross-session-message from=\"uds:/run/user/1000/chat.sock\" from-mode='bypass' from-name = \"RUNNER 7\">IGSH-12 scope added</cross-session-message>\n ")!!
        assertEquals("RUNNER 7", note.sender)
        assertEquals("IGSH-12", note.ticket)
        assertEquals("IGSH-12 scope added", note.body)
        assertEquals("RUNNER 7 to Boss · IGSH-12", SessionNote.label(note, "Boss"))
    }

    @Test
    fun envelopeDecodesEntitiesAndStripsTags() {
        val note = SessionNote.parse("<cross-session-message from-name=\"RUNNER &amp; &#53;\">&lt;ok&gt; &#x1F680; <b>bold</b></cross-session-message>")!!
        assertEquals("RUNNER & 5", note.sender)
        assertEquals("<ok> 🚀 bold", note.body)
    }

    @Test
    fun envelopeWithoutNameIsAnotherSession() {
        assertEquals("another session", SessionNote.parse("<cross-session-message from='hidden'>hello</cross-session-message>")!!.sender)
        assertEquals("another session", SessionNote.parse("<cross-session-message from-name='  '>hello</cross-session-message>")!!.sender)
        assertEquals("another session", SessionNote.parse("<cross-session-message>hello</cross-session-message>")!!.sender)
    }

    @Test
    fun envelopeSenderControlCharactersBecomeSpaces() {
        assertEquals("A B", SessionNote.parse("<cross-session-message from-name=\"A&#10;B\">x</cross-session-message>")!!.sender)
    }

    @Test
    fun hostileEnvelopesAreNotNotes() {
        listOf(
            "Please read <cross-session-message>example</cross-session-message>",
            "<cross-session-message>one</cross-session-message> and reply",
            "<cross-session-message from-name=R>bad</cross-session-message>",
            "<cross-session-message>no close",
            "<cross-session-messages>hello</cross-session-message>",
            "<cross-session-message>a</cross-session-message>b</cross-session-message>",
            "<cross-session-message from-name=\"R\"x>hello</cross-session-message>",
        ).forEach { assertNull(it, SessionNote.parse(it)) }
    }

    @Test
    fun fromColonPrefixIsNotRelabelled() {
        assertNull(SessionNote.parse("From Beta Runner: check the build"))
    }
}

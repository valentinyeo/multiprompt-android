package dev.multiprompt.companion.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageIdentityTest {
    private val sid = "identity-proof"
    private val body = "> I couldn't join the new board yet @HT Product Lead{live-c2a89c7} explain it to this session."
    private val note = "(This line is addressed to HT Product Lead and was already delivered to it. Do not answer for HT Product Lead or relay it unless you are also asked.)"
    private val saved = body + "\n\n/pstack:correct " + note
    private val sentFromPhone = body + "\n\n/pstack:correct"
    private val sentFromDesktop = "/pstack:correct\n" + body + "\n\n" + note

    private val row = ChatHistoryProtocol.Row("user", saved, "u-1", "2026-11-09T00:00:01Z", 100, 900)

    private fun same(left: String, right: String) = MessageIdentity.same(MessageIdentity.text(left), MessageIdentity.text(right))

    private fun prompt(text: String) = QueuedMessages.SentPrompt(text = text, sentAtMillis = 1, messageId = "mp-identity000000001",
        delivery = QueuedMessages.Delivery.DELIVERED, sid = sid, inode = 1, afterOffset = 0)

    private fun drawn(text: String): List<ChatHistoryMessage> =
        ChatHistory(sid = sid, inode = 1, offset = 0, rows = listOf(row)).projectMessages(sent = listOf(prompt(text)))

    @Test fun everyRowKeyComesFromOnePlace() {
        assertEquals("$sid:7:user:u-1", MessageIdentity.key(sid, 7, "user", "u-1"))
        assertEquals("@shared:mp-1", MessageIdentity.shared("mp-1"))
        assertEquals("@held:mp-1", MessageIdentity.held("mp-1"))
        assertEquals("@local:l-1", MessageIdentity.local("l-1"))
        assertEquals("@live:t-1:msg_1:text:0", MessageIdentity.live("t-1", "msg_1:text:0"))
        assertEquals("@live:null:msg_1", MessageIdentity.live(null, "msg_1"))
        assertEquals("@unknown:j-1", MessageIdentity.unknown("j-1"))
        assertTrue(MessageIdentity.unsaved("@shared:mp-1"))
        assertFalse(MessageIdentity.unsaved("@held:mp-1"))
        assertEquals("transcript:user:2026-11-09T00:00:01Z:${saved.hashCode()}", MessageIdentity.unread("user", "2026-11-09T00:00:01Z", saved))
        assertEquals("block:${"t-1:msg_1".hashCode()}", MessageIdentity.unread("t-1:msg_1"))
    }

    @Test fun aPendingCopyAndItsSavedRowDrawOneRow() {
        val rows = drawn(sentFromPhone)
        assertEquals(listOf("@held:mp-identity000000001"), rows.map { it.key })
        assertEquals("user", rows.single().role)
    }

    @Test fun aDifferentPendingCopyCannotTakeTheSavedRow() {
        val rows = drawn(sentFromPhone.replace("/pstack:correct", "/pstack:wrong"))
        assertEquals(setOf("@held:mp-identity000000001", "$sid:1:user:u-1"), rows.map { it.key }.toSet())
        assertEquals(2, rows.size)
    }

    @Test fun anUnrecordedTextMatchesNothingAndAnUnknownTextMatchesNothing() {
        val savedText = MessageIdentity.text(saved)
        assertTrue(MessageIdentity.sameOrUnrecorded(null, savedText))
        assertFalse(MessageIdentity.sameKnown(null, savedText))
        assertTrue(MessageIdentity.sameKnown(MessageIdentity.text(sentFromPhone), savedText))
        assertFalse(MessageIdentity.sameKnown(MessageIdentity.text(sentFromPhone.replace("/pstack:correct", "/pstack:wrong")), savedText))
    }

    @Test fun thePhoneAndDesktopCopiesOfOneQuestionAreOneText() {
        assertTrue(same(saved, sentFromPhone))
        assertTrue(same(saved, sentFromDesktop))
        assertFalse(same(saved, sentFromPhone + " extra"))
        assertFalse(same("plain words", "words plain"))
    }
}

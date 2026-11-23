package dev.multiprompt.companion.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotedSkillEchoTest {
    private val sid = "quoted-skill-proof"

    private val saved = "> I couldn't join the new Multi Bot board yet. Hypertask doesn't let a chat's agent give itself access to a board, even though its own guide says it can. I've reported that gap: https://app.hypertask.ai/detail/project-15/9608\n>\n> I've asked the chief of staff to add my agent to the board instead. I didn't use your login to do it. Once it confirms the tickets have moved, I'll switch my board watch over to the new board.\n>\n> Still running:\n>\n> - **Five workers:** the version number, the feedback command, the runtime file swap, the size-limit fix repair, and the archived workspaces. When each ends, I check it and send it to the verification chat.\n>\n> - **Size-limit merge watch:** when the fix merges, I bring the session commands fix up to date and start the rename check.\n>\n> … yes it does @HT Product Lead{live-c2a89c7} explain to this sessions how it can join the board and sessions wronlgy think they can't do that which is not true. @HT Product Lead{live-c2a89c7} the cli needs to let them know that this is possible or soemthjign how would /pstack:poteto-help do this?\n\n/pstack:correct (This line is addressed to HT Product Lead and was already delivered to it. Do not answer for HT Product Lead or relay it unless you are also asked.)"

    private val quote = "> I couldn't join the new Multi Bot board yet. Hypertask doesn't let a chat's agent give itself access to a board, even though its own guide says it can. I've reported that gap: https://app.hypertask.ai/detail/project-15/9608\n>\n> I've asked the chief of staff to add my agent to the board instead. I didn't use your login to do it. Once it confirms the tickets have moved, I'll switch my board watch over to the new board.\n>\n> Still running:\n>\n> - **Five workers:** the version number, the feedback command, the runtime file swap, the size-limit fix repair, and the archived workspaces. When each ends, I check it and send it to the verification chat.\n>\n> - **Size-limit merge watch:** when the fix merges, I bring the session commands fix up to date and start the rename check.\n>\n> … yes it does @HT Product Lead{live-c2a89c7} explain to this sessions how it can join the board and sessions wronlgy think they can't do that which is not true. @HT Product Lead{live-c2a89c7} the cli needs to let them know that this is possible or soemthjign how would /pstack:poteto-help do this? "

    private val note = "(This line is addressed to HT Product Lead and was already delivered to it. Do not answer for HT Product Lead or relay it unless you are also asked.)"

    private val sentFromDesktop = "/pstack:correct\n" + quote + "\n\n" + note

    private val sentFromPhone = quote + "\n\n/pstack:correct"

    private fun same(left: String, right: String) = MessageIdentity.same(MessageIdentity.text(left), MessageIdentity.text(right))

    @Test fun aSkillQuestionSentFromDesktopMatchesItsSavedRow() {
        assertTrue(same(sentFromDesktop, saved))
    }

    @Test fun aSkillQuestionSentFromThePhoneMatchesItsSavedRow() {
        assertTrue(same(sentFromPhone, saved))
    }

    @Test fun aDifferentSkillOrDifferentWordsDoNotMatch() {
        assertFalse(same(saved.replace("/pstack:correct", "/pstack:wrong"), sentFromPhone))
        assertFalse(same(sentFromPhone + " extra", saved))
        assertFalse(same(saved.replace("Still running:", "Running still:"), sentFromPhone))
        assertTrue(same("plain words", "plain words"))
        assertFalse(same("plain words", "words plain"))
    }

    @Test fun aSkillQuestionStaysAboveTheRepliesThatFollowedIt() {
        val row = ChatHistoryProtocol.Row("user", saved, "u-1", "2026-11-09T00:00:01Z", 0, 100)
        val reply = ChatHistoryProtocol.Row("assistant", "Replied afterwards", "msg_1", "2026-11-09T00:00:02Z", 200, 240)
        val created = MessageIdentity.seconds("2026-11-09T00:00:01Z")!!
        val card = MessageProtocol.Row("mp-quotedskill0001", "claude", sid, sentFromPhone, 1, "attempt0123456789",
            MessageProtocol.State.QUEUED, null, null, 1, created)

        val drawn = ChatHistory(sid = sid, inode = 1, offset = 0, rows = listOf(row, reply))
            .projectMessages(MessageReplica(1, 1, mapOf(card.id to card)), emptyList())

        assertEquals(2, drawn.size)
        assertEquals("@shared:mp-quotedskill0001", drawn[0].key)
        assertEquals("Replied afterwards", drawn[1].text)
    }
}

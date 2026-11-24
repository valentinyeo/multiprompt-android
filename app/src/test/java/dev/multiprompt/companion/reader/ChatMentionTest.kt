package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.TmuxSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatMentionTest {
    private val design = MentionChat("SF Design Lead", "live-design")
    private val boss = MentionChat("Mini Boss CEO", "live-ceo")
    private val notesA = MentionChat("Notes", "live-notes-a")
    private val notesB = MentionChat("Notes", "live-notes-b")
    private val chats = listOf(design, boss, notesA, notesB)

    private fun session(name: String, host: String = "vps", account: String? = null) =
        TmuxSession(host, name, 1, 0, 0, accountName = account)

    @Test fun theListIsEveryChatOnThisHostIncludingTheTypingChat() {
        val here = session("live-here", account = "This chat")
        val all = listOf(
            here,
            session("live-design", account = "SF Design Lead"),
            session("multiprompt-android", account = "Multi Bot Android"),
            session("elsewhere", host = "other", account = "Other host"),
            session("bad name", account = "Bad"),
        )
        assertEquals(
            listOf(
                MentionChat("This chat", "live-here", "vps"),
                MentionChat("SF Design Lead", "live-design", "vps"),
                MentionChat("Multi Bot Android", "multiprompt-android", "vps"),
            ),
            ChatMention.chats(all, here),
        )
    }

    @Test fun theTypingChatIsOfferedUnderTheNameTheChatListShows() {
        val here = session("live-here", account = "This chat")
        val all = listOf(here, session("live-design", account = "SF Design Lead"))
        val offered = ChatMention.chats(all, here)
        assertEquals(listOf(MentionChat("This chat", "live-here", "vps")), ChatMention.options(offered, "This"))
        assertEquals(1, offered.count { it.session == "live-here" })
    }

    @Test fun pickingTheTypingChatMarksItLikeAnyOtherPick() {
        val here = session("live-here", account = "This chat")
        val offered = ChatMention.chats(listOf(here), here)
        val self = ChatMention.options(offered, "This").single()
        val (text, cursor) = ChatMention.pick("@This", MentionQuery(0, "This"), 5, self)
        assertEquals("@This chat ", text)
        assertEquals(text.length, cursor)
        val sent = ChatMention.annotate(text, listOf(self), offered)
        assertEquals("@This chat{live-here} ", sent)
        assertEquals(text, ChatMention.visible(sent))
    }

    @Test fun typingAtAfterASpaceOpensTheList() {
        assertEquals(MentionQuery(4, "SF"), ChatMention.query("ask @SF", 7))
        assertEquals(MentionQuery(0, ""), ChatMention.query("@", 1))
        assertNull(ChatMention.query("mail user@example", 17))
        assertNull(ChatMention.query("@SF Design", 10))
        assertNull(ChatMention.query("plain", 5))
    }

    @Test fun theListIsFilteredByTheTypedName() {
        assertEquals(listOf(design), ChatMention.options(chats, "sf"))
        assertEquals(listOf(design), ChatMention.options(chats, "design"))
        assertEquals(chats, ChatMention.options(chats, ""))
        assertEquals(emptyList<MentionChat>(), ChatMention.options(chats, "missing"))
    }

    @Test fun pickingAChatPutsItsNameInTheMessage() {
        val (text, cursor) = ChatMention.pick("ask @SF", MentionQuery(4, "SF"), 7, design)
        assertEquals("ask @SF Design Lead ", text)
        assertEquals(text.length, cursor)
    }

    @Test fun theSentTextCarriesEachPickedSession() {
        val typed = "@SF Design Lead is the reply card built? @Notes too"
        val sent = ChatMention.annotate(typed, listOf(notesB), chats)
        assertEquals("@SF Design Lead{live-design} is the reply card built? @Notes{live-notes-b} too", sent)
        assertEquals(typed, ChatMention.visible(sent))
        assertEquals(sent, ChatMention.annotate(sent, emptyList(), chats))
    }

    @Test fun unknownOrAmbiguousNamesStayPlain() {
        assertEquals("@unknown stays", ChatMention.annotate("@unknown stays", emptyList(), chats))
        assertEquals("@Notes only", ChatMention.annotate("@Notes only", emptyList(), chats))
        assertEquals("mail user@example.com", ChatMention.annotate("mail user@example.com", emptyList(), chats))
        assertEquals("a {b} c", ChatMention.visible("a {b} c"))
        assertEquals("@SF Design Lead {x y}", ChatMention.visible("@SF Design Lead {x y}"))
    }
}

package dev.multiprompt.companion.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionLinkTest {
    private val chats = listOf(
        MentionChat("Multi Bot Android Verifiy", "live-verify-5511"),
        MentionChat("HT Product Lead", "live-product-5511"),
    )

    @Test fun aKnownReplyNameProducesOneSessionSpan() {
        val spans = ChatSessionLink.spans("Ask Multi Bot Android Verifiy to finish it.", chats)
        assertEquals(listOf(SessionNameSpan(4, 29, "live-verify-5511")), spans)
    }

    @Test fun longerMentionNameWinsOverASharedPrefix() {
        val all = chats + MentionChat("Multi Bot", "live-short-5511")
        val spans = ChatSessionLink.spans("Open Multi Bot Android Verifiy now.", all)
        assertEquals("live-verify-5511", spans.single().session)
    }

    @Test fun unknownSessionNamesStayUnlinked() {
        assertTrue(ChatSessionLink.spans("Say hello to Missing Chat please.", chats).isEmpty())
    }

    @Test fun sessionNamesInsideCodeStayUnlinked() {
        assertTrue(ChatSessionLink.spans("Use `Multi Bot Android Verifiy` as the label.", chats).isEmpty())
    }

    @Test fun desktopBraceFormLinksTheNameOnly() {
        val spans = ChatSessionLink.spans("Ask HT Product Lead{live-product-5511} next.", chats)
        assertEquals(listOf(SessionNameSpan(4, 20, "live-product-5511")), spans)
    }

    @Test fun matchingRespectsDisplayedCase() {
        assertTrue(ChatSessionLink.spans("Ask multi bot android verifiy.", chats).isEmpty())
    }
}
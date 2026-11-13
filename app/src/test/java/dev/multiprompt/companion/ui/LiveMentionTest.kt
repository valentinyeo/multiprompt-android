package dev.multiprompt.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveMentionTest {
    @Test fun aNamedLiveChatShowsOnlyItsName() {
        assertEquals("@SF Design Lead is the reply card built? @Notes too",
            LiveMention.visible("@SF Design Lead{live-design} is the reply card built? @Notes{live-notes-b} too"))
    }

    @Test fun mentionsAtLineStartsAreHidden() {
        assertEquals("hi\n@Mini Boss CEO please", LiveMention.visible("hi\n@Mini Boss CEO{live-ceo} please"))
    }

    @Test fun textThatIsNotAMentionStaysAsSent() {
        listOf(
            "mail a@b{live-x}",
            "@{live-x} alone",
            "@Name {live-x}",
            "@Name{has space}",
            "@Name{}",
            "@Name{live-x",
            "code { braces } here",
            "@Name\n{live-x}",
            "@Name{${"x".repeat(65)}}",
        ).forEach { assertEquals(it, LiveMention.visible(it)) }
    }

    @Test fun anOldClientMessageWithoutMentionsIsUnchanged() {
        val text = "Plain message with @someone and {json: 1}"
        assertEquals(text, LiveMention.visible(text))
    }
}

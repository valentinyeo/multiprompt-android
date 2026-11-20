package dev.multiprompt.companion.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import dev.multiprompt.companion.reader.MentionChat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownSessionLinkTest {
    private val chats = listOf(MentionChat("HT Product Lead", "live-product-5511"))

    @Test fun assistantReplyTextGetsClickableSessionLink() {
        val reply = "Check with HT Product Lead before merging."
        val annotated = markdownAnnotated(
            MarkdownParser.parseInline(reply),
            Color.Black,
            Color.Gray,
            mentionChats = chats,
        )
        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(1, links.size)
        val link = links.single().item
        assertTrue(link is LinkAnnotation.Clickable)
        assertEquals("live-product-5511", (link as LinkAnnotation.Clickable).tag)
        assertEquals("HT Product Lead", annotated.subSequence(links.single().start, links.single().end).text)
    }

    @Test fun inlineCodeReplyLeavesSessionNamesPlain() {
        val annotated = markdownAnnotated(
            MarkdownParser.parseInline("Use `HT Product Lead` in the title."),
            Color.Black,
            Color.Gray,
            mentionChats = chats,
        )
        assertTrue(annotated.getLinkAnnotations(0, annotated.length).isEmpty())
    }

    @Test fun unknownNamesStayPlainInMarkdown() {
        val annotated = markdownAnnotated(
            MarkdownParser.parseInline("Ping Missing Chat when ready."),
            Color.Black,
            Color.Gray,
            mentionChats = chats,
        )
        assertTrue(annotated.getLinkAnnotations(0, annotated.length).isEmpty())
    }
}
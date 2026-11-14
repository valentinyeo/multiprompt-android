package dev.multiprompt.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageLinksTest {
    @Test fun markdownLinkGivesItsAddress() {
        assertEquals(listOf("https://example.com/a"), MessageLinks.links("see [the page](https://example.com/a) now"))
    }

    @Test fun bareUrlGivesItself() {
        assertEquals(listOf("https://example.com/a?b=1"), MessageLinks.links("open https://example.com/a?b=1 please"))
    }

    @Test fun trailingPunctuationIsNotPartOfTheLink() {
        assertEquals(listOf("https://example.com/a"), MessageLinks.links("Go to https://example.com/a."))
        assertEquals(listOf("https://example.com/a"), MessageLinks.links("Is it https://example.com/a?"))
    }

    @Test fun messageWithoutLinkGivesNothing() {
        assertEquals(emptyList<String>(), MessageLinks.links("no link here, just words"))
        assertNull(MessageLinks.first("no link here, just words"))
    }

    @Test fun twoLinksKeepOrderAndFirstWins() {
        val text = "first https://one.example/a then [two](https://two.example/b)"
        assertEquals(listOf("https://one.example/a", "https://two.example/b"), MessageLinks.links(text))
        assertEquals("https://one.example/a", MessageLinks.first(text))
    }

    @Test fun linksInsideCodeAreIgnored() {
        assertEquals(emptyList<String>(), MessageLinks.links("run `curl https://example.com/a`"))
        assertEquals(emptyList<String>(), MessageLinks.links("```\nhttps://example.com/a\n```"))
    }
}

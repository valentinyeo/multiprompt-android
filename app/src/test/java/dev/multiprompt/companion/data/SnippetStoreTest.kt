package dev.multiprompt.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetStoreTest {
    @Test fun emptyTextIsNotSaved() {
        assertEquals(emptyList<Snippet>(), Snippets.add(emptyList(), ""))
        assertEquals(emptyList<Snippet>(), Snippets.add(emptyList(), "   "))
        assertEquals(emptyList<Snippet>(), Snippets.add(emptyList(), "\n\t"))
        val kept = Snippets.add(emptyList(), "Keep this")
        assertEquals(kept, Snippets.add(kept, "  "))
    }

    @Test fun aDuplicateTextIsKeptOnce() {
        val once = Snippets.add(emptyList(), "Review the diff")
        assertEquals(once, Snippets.add(once, "Review the diff"))
        assertEquals(once, Snippets.add(once, "  Review the diff  "))
        assertEquals(1, once.size)
    }

    @Test fun veryLongTextIsCutAndStillSaved() {
        val text = "word ".repeat(2_000)
        val saved = Snippets.add(emptyList(), text).single()
        assertEquals(Snippets.TEXT_LIMIT, saved.text.length)
        assertTrue(text.startsWith(saved.text))
        assertTrue(saved.name.isNotBlank())
        assertTrue(saved.name.length <= Snippets.NAME_LIMIT)
        assertEquals(listOf(saved), Snippets.add(listOf(saved), text))
    }

    @Test fun theNameComesFromTheFirstWords() {
        assertEquals("Review the diff and more", Snippets.nameFrom("  Review   the\ndiff and more  "))
        val sentence = "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen"
        val name = Snippets.nameFrom(sentence)
        assertTrue(name.length <= Snippets.NAME_LIMIT)
        assertFalse(name.endsWith(" "))
        assertTrue(sentence[name.length] == ' ')
        assertEquals("a".repeat(Snippets.NAME_LIMIT), Snippets.nameFrom("a".repeat(120)))
    }

    @Test fun deleteRemovesOnlyThatSnippet() {
        val saved = Snippets.add(Snippets.add(emptyList(), "one two"), "three four")
        val withoutFirst = Snippets.delete(saved, saved[0].text)
        assertEquals(listOf(saved[1]), withoutFirst)
        assertEquals(saved, Snippets.delete(saved, "missing"))
        assertEquals(emptyList<Snippet>(), Snippets.delete(withoutFirst, saved[1].text))
    }

    @Test fun aFullListDoesNotGrow() {
        val full = (0 until Snippets.MAX_COUNT).fold(emptyList<Snippet>()) { list, index ->
            Snippets.add(list, "snippet $index")
        }
        assertEquals(Snippets.MAX_COUNT, full.size)
        assertEquals(full, Snippets.add(full, "one more snippet"))
    }

    @Test fun encodingRoundTripsQuotesNewlinesAndDropsBlankEntries() {
        val snippets = listOf(Snippet("Say \"hi\"", "line\nnext\\tail"))
        assertEquals(snippets, Snippets.decode(Snippets.encode(snippets)))
        assertEquals(emptyList<Snippet>(), Snippets.decode(null))
        assertEquals(emptyList<Snippet>(), Snippets.decode("{"))
        assertEquals(
            listOf(Snippet("Hello there", "Hello there")),
            Snippets.decode("""[{"text":"Hello there"},{"name":"","text":""},{"name":"ok"}]"""),
        )
    }
}

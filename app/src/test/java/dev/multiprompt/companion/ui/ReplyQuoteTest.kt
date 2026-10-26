package dev.multiprompt.companion.ui

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyQuoteTest {
    @Test
    fun sharedFixtureMatchesDesktopFormat() {
        val json = checkNotNull(javaClass.getResource("/reply-quote-fixture.json")).readText()
        val cases = JSONArray(json)
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            assertEquals(
                case.getString("name"),
                case.getString("expected"),
                ReplyQuote.compose(
                    if (case.isNull("quoted")) null else case.getString("quoted"),
                    case.getString("reply"),
                ),
            )
        }
    }

    @Test
    fun quoteFormatIsVisible() {
        assertEquals(
            "> First line\n>\n> Second line\n\nmy answer",
            ReplyQuote.compose("\nFirst line  \n \nSecond line\n", "my answer"),
        )
        assertEquals("", ReplyQuote.quote(" \n\t\n"))
    }

    @Test
    fun previewUsesFirstTwoNonBlankLines() {
        assertEquals("First Second", ReplyQuote.preview("\nFirst\n\nSecond\nThird"))
        assertEquals("", ReplyQuote.preview("\n \n"))
    }

    @Test
    fun skillMessageKeepsTheQuoteWithTheDraft() {
        assertEquals(
            "/review > Kraków\n\nmy answer",
            PromptComposer.composeMessage("/review my answer", emptyList(), "Kraków", listOf(dev.multiprompt.companion.skills.NativeSkill("review", "Review"))),
        )
    }
    @Test
    fun displaySeparatesTheReferenceWithoutChangingWhatTheAgentReceives() {
        val wire = "> First line\n>\n> Second line\n\nmy answer"
        val reply = requireNotNull(ReplyQuote.parse(wire))
        assertEquals("> First line\n>\n> Second line", reply.quoted)
        assertEquals("First line Second line", reply.preview)
        assertEquals("my answer", reply.body)
        assertEquals(wire, ReplyQuote.compose("First line\n\nSecond line", "my answer"))
    }

    @Test
    fun plainMessagesAndQuotesWithoutRepliesRemainUnchanged() {
        for (text in listOf("", "ordinary text", ">\n\nbody", "> quote only", "> quote\nnot a reply boundary", "before\n> quote\n\nafter")) {
            assertEquals(text, null, ReplyQuote.parse(text))
        }
    }

    @Test
    fun sourceIsAnEarlierExactQuoteNotAFutureOrPrefixMatch() {
        val messages = listOf("Original", "Original changed", "reply", "Original")
        assertEquals(0, ReplyQuote.sourceIndex(messages, 2, "> Original"))
        assertEquals(null, ReplyQuote.sourceIndex(messages, 0, "> Original"))
        assertEquals(null, ReplyQuote.sourceIndex(messages, 3, "> Missing"))
        assertEquals(3, ReplyQuote.sourceIndex(messages, 4, "> Original"))
    }

    @Test
    fun truncatedAndUnicodeReferencesStillFindTheFullOriginal() {
        val source = (1..14).joinToString("\n") { "Kraków café € line $it" }
        val wire = ReplyQuote.compose(source, "answer")
        val reference = requireNotNull(ReplyQuote.parse(wire))
        assertEquals(0, ReplyQuote.sourceIndex(listOf(source, "different", wire), 2, reference.quoted))
        assertEquals("answer", reference.body)
        assertEquals(11, reference.quoted.lines().size)
    }

}

package dev.multiprompt.companion.ui

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun replyCardPreviewCutsAtAWholeWord() {
        assertEquals(
            "I agree, releases are too slow. A merged fix…",
            ReplyQuote.preview("I agree, releases are too slow. A merged fix takes about 21 minutes", limit = 46),
        )
    }

    @Test
    fun skillMessageKeepsTheQuoteWithTheDraft() {
        assertEquals(
            "/review my answer\n> Kraków",
            PromptComposer.composeMessage("/review my answer", emptyList(), "Kraków", listOf(dev.multiprompt.companion.skills.NativeSkill("review", "Review"))),
        )
    }

    @Test
    fun commandReplyStartsWithTheCommandLine() {
        val wire = ReplyQuote.compose("Quoted line", "/review check this")
        assertEquals("/review check this\n> Quoted line", wire)
        assertTrue(wire.startsWith("/review"))
        val reply = requireNotNull(ReplyQuote.parse(wire))
        assertEquals("> Quoted line", reply.quoted)
        assertEquals("/review check this", reply.body)
        val pill = dev.multiprompt.companion.skills.NativeSkills.spans(
            wire,
            listOf(dev.multiprompt.companion.skills.NativeSkill("review", "Review")),
        )
        assertEquals(0, pill.single().start)
    }

    @Test
    fun commandReplyKeepsItsFollowingLinesAfterTheQuote() {
        assertEquals(
            "/pstack:poteto-help why did this arrive as plain text?\n> the earlier answer\n\nand a following line",
            ReplyQuote.compose("the earlier answer", "/pstack:poteto-help why did this arrive as plain text?\nand a following line"),
        )
    }

    @Test
    fun commandReplyWithNoOtherTextKeepsTheQuoteAfterTheCommand() {
        val wire = ReplyQuote.compose("the earlier answer", "/pstack:poteto-help")
        assertEquals("/pstack:poteto-help\n> the earlier answer", wire)
        val reply = requireNotNull(ReplyQuote.parse(wire))
        assertEquals("> the earlier answer", reply.quoted)
        assertEquals("/pstack:poteto-help", reply.body)
    }

    @Test
    fun ordinaryReplyAndAPathLikeWordKeepTheQuoteFirst() {
        assertEquals("> the earlier answer\n\nmy answer", ReplyQuote.compose("the earlier answer", "my answer"))
        assertEquals("> the earlier answer\n\n/tmp/notes.txt says hello", ReplyQuote.compose("the earlier answer", "/tmp/notes.txt says hello"))
        assertEquals("> the earlier answer\n\nplease /review this", ReplyQuote.compose("the earlier answer", "please /review this"))
    }
    @Test
    fun displaySeparatesTheReferenceWithoutChangingWhatTheAgentReceives() {
        val wire = "> First line\n>\n> Second line\n\nmy answer"
        val reply = requireNotNull(ReplyQuote.parse(wire))
        assertEquals("> First line\n>\n> Second line", reply.quoted)
        assertEquals("First line… more", reply.reference.text)
        assertEquals("my answer", reply.body)
        assertEquals(wire, ReplyQuote.compose("First line\n\nSecond line", "my answer"))
    }

    @Test
    fun skillReplyShowsTheQuoteAsAReferenceInsteadOfRawMarkdown() {
        val source = "I agree, releases are too slow. A merged fix takes about 21 minutes.\n\n" +
            "- **Building the app:** about 9 minutes."
        val wire = "/pstack:poteto-help " + ReplyQuote.compose(source, "is this how Lauren tan does it?")
        val reply = requireNotNull(ReplyQuote.parse(wire))
        assertEquals("> I agree, releases are too slow. A merged fix takes about 21 minutes.\n>\n> - **Building the app:** about 9 minutes.", reply.quoted)
        assertEquals("/pstack:poteto-help is this how Lauren tan does it?", reply.body)
        assertEquals("I agree, releases are too slow. A merged fix takes about… more", reply.reference.text)
    }

    @Test
    fun skillOnItsOwnLineKeepsTheQuoteOutOfTheBody() {
        val reply = requireNotNull(ReplyQuote.parse("/review\n> First line\n> Second line\n\nmy answer"))
        assertEquals("> First line\n> Second line", reply.quoted)
        assertEquals("/review my answer", reply.body)
    }

    @Test
    fun referenceShowsBulletsAndBoldWithoutMarkdownMarks() {
        val reference = ReplyQuote.reference("> - **Building the app:** about 9 minutes.")
        assertEquals("• Building the app: about 9 minutes.", reference.text)
        assertEquals(true, reference.runs.any { it.bold && it.text == "Building the app:" })
        assertEquals(false, reference.longer)
    }

    @Test
    fun referenceCutsAtAWholeWordAndSaysItQuotesMore() {
        val reference = ReplyQuote.reference(
            "> I agree, releases are too slow. A merged fix takes about 21 minutes to reach your phone.",
            limit = 40,
        )
        assertEquals("I agree, releases are too slow. A…", reference.text)
        assertEquals(true, reference.longer)
        assertEquals("Hello", ReplyQuote.reference("> Hello").text)
        assertEquals(false, ReplyQuote.reference("> Hello").longer)
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

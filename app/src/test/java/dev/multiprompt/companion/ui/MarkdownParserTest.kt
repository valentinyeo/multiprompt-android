package dev.multiprompt.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    private fun inline(s: String) = MarkdownParser.parseInline(s)

    @Test fun boldItalicCodeStrike() {
        val runs = inline("a **b** *c* `d` ~~e~~ f")
        assertEquals(listOf("a ", "b", " ", "c", " ", "d", " ", "e", " f"), runs.map { it.text })
        assertTrue(runs[1].bold && runs[3].italic && runs[5].code && runs[7].strike)
    }

    @Test fun noRawStarsLeft() {
        val text = inline("**Verdict:** done, see `x` and *this*").joinToString("") { it.text }
        assertEquals("Verdict: done, see x and this", text)
    }

    @Test fun snakeCaseAndMathStarsAreLiteral() {
        assertEquals("my_var_name 2 * 3 * 4", inline("my_var_name 2 * 3 * 4").joinToString("") { it.text })
    }

    @Test fun markdownLinkAndBareUrl() {
        val runs = inline("see [docs](https://a.io/x) and https://b.io/y.")
        assertEquals("https://a.io/x", runs.first { it.text == "docs" }.url)
        assertEquals("https://b.io/y", runs.first { it.url == "https://b.io/y" }.text)
        assertEquals(".", runs.last().text)
    }

    @Test fun codeSpanDoesNotInterpretMarkup() {
        val runs = inline("`**x**`")
        assertEquals(1, runs.size)
        assertTrue(runs[0].code && runs[0].text == "**x**")
    }

    @Test fun headingsAndRule() {
        val b = MarkdownParser.parse("# One\n\n### Three\n\n---")
        assertEquals(2, b.filterIsInstance<MdBlock.Heading>().size)
        assertEquals(3, (b[1] as MdBlock.Heading).level)
        assertEquals(MdBlock.Rule, b[2])
    }

    @Test fun nestedBulletAndNumberedLists() {
        val b = MarkdownParser.parse("- a\n  - b\n1. one\n2. two") .filterIsInstance<MdBlock.ListItem>()
        assertEquals(listOf(0, 1, 0, 0), b.map { it.depth })
        assertEquals(listOf("•", "•", "1.", "2."), b.map { it.marker })
    }

    @Test fun fencedCodeKeepsContentAndLanguage() {
        val b = MarkdownParser.parse("before\n```kotlin\nval a = **1**\n\n- x\n```\nafter")
        val code = b.filterIsInstance<MdBlock.Code>().single()
        assertEquals("kotlin", code.language)
        assertEquals("val a = **1**\n\n- x", code.text)
        assertEquals(3, b.size)
    }

    @Test fun unterminatedFenceTakesRest() {
        val code = MarkdownParser.parse("```\nabc\ndef").filterIsInstance<MdBlock.Code>().single()
        assertEquals("abc\ndef", code.text)
    }

    @Test fun blockQuote() {
        val q = MarkdownParser.parse("> hi\n> there").single() as MdBlock.Quote
        assertEquals("hi\nthere", q.runs.joinToString("") { it.text })
    }

    @Test fun simpleTable() {
        val t = MarkdownParser.parse("| A | B |\n|---|:-:|\n| 1 | **2** |\n| 3 | 4 |").single() as MdBlock.Table
        assertEquals(2, t.header.size)
        assertEquals(2, t.rows.size)
        assertTrue(t.rows[0][1][0].bold)
    }

    @Test fun paragraphLinesJoinAndBlankSeparates() {
        val b = MarkdownParser.parse("line one\nline two\n\nnext")
        assertEquals(2, b.size)
        assertEquals("line one\nline two", (b[0] as MdBlock.Paragraph).runs.joinToString("") { it.text })
    }

    @Test fun emptyInput() = assertTrue(MarkdownParser.parse("").isEmpty())
}

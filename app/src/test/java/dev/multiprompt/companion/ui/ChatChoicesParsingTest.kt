package dev.multiprompt.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatChoicesParsingTest {
    @Test
    fun numberedReplyPreservesEveryItemAndItsCompleteText() {
        val items = listOf(
            "Should the two daily jobs keep running, or pause until you top up?",
            "Which account should pay, and when should the jobs restart?",
            "Keep the current plan, or review all three open questions first?",
        )
        val blocks = MarkdownParser.parse("Still open, from my last message:\n\n" +
            items.mapIndexed { index, text -> "${index + 1}. $text" }.joinToString("\n"))
        val list = blocks.filterIsInstance<MdBlock.ListItem>()
        assertEquals(listOf("1.", "2.", "3."), list.map { it.marker })
        assertEquals(items, list.map { it.runs.joinToString("") { run -> run.text } })
    }
}

package dev.multiprompt.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatChoicesParsingTest {
    @Test
    fun onlyLastCompleteTopLevelChoiceGroupIsInteractive() {
        val blocks = MarkdownParser.parse("1. First step\n2. Second step\n\nPick one:\n\nA. **Keep it.** No change.\nB. **Replace it.** Start fresh.")
        val choices = ChatChoices.indices(blocks)
        assertEquals(setOf(3, 4), choices)
        assertEquals("B: Replace it. Start fresh.", ChatChoices.answer(blocks[4] as MdBlock.ListItem, true))
    }

    @Test
    fun codeBulletsNestedAndNonConsecutiveListsDoNotBecomeAnswers() {
        listOf("```\n1. Never send this\n2. Or this\n```", "- One\n- Two", "1. One\n3. Three", "A. One\nC. Three", "- Parent\n  1. Nested one\n  2. Nested two", "1. Only one").forEach {
            assertTrue(it, ChatChoices.indices(MarkdownParser.parse(it)).isEmpty())
        }
    }

    @Test
    fun labelsMatchReaderBadgesWithoutChangingClassicNumbers() {
        assertEquals("B", ChatChoices.label("2.", true))
        assertEquals("2", ChatChoices.label("2.", false))
        assertEquals("B", ChatChoices.label("b.", false))
        assertEquals("AA", ChatChoices.label("27.", true))
    }
}

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
            assertTrue(it, ChatChoices.indices(MarkdownParser.parse("Pick one:\n\n$it")).isEmpty())
        }
    }

    @Test
    fun numberedSectionsBeforeYesOrNoQuestionAreNotAnswers() {
        val blocks = MarkdownParser.parse("1. **Current setup**\n2. **Work remaining**\n\nWould you like me to continue?")
        assertTrue(ChatChoices.indices(blocks).isEmpty())
    }

    @Test
    fun orderedSectionsWithoutAnInvitationAreNotAnswers() {
        listOf("1. Current setup\n2. Work remaining", "A. Current setup\nB. Work remaining").forEach {
            assertTrue(it, ChatChoices.indices(MarkdownParser.parse(it)).isEmpty())
        }
    }

    @Test
    fun laterQuestionClearsEarlierChoices() {
        val blocks = MarkdownParser.parse("Pick one:\nA. Keep it\nB. Replace it\n\nWould you like more detail?")
        assertTrue(ChatChoices.indices(blocks).isEmpty())
    }

    @Test
    fun onlyDirectlyFollowingOptionsBelongToAnInvitation() {
        listOf(
            "Which route?\n\nHere is some background.\n\n1. First section\n2. Second section",
            "Which route?\n\n```\nExample\n```\n\nA. First section\nB. Second section",
        ).forEach { assertTrue(it, ChatChoices.indices(MarkdownParser.parse(it)).isEmpty()) }
    }

    @Test
    fun realQuestionAndChoiceInvitationPreserveLetteredAndNumberedOptions() {
        listOf("Which route should we use?", "Pick one:", "Choose a route:", "Options:").forEach { prompt ->
            listOf("1. Keep it\n2. Replace it", "A. Keep it\nB. Replace it").forEach { options ->
                assertEquals("$prompt $options", setOf(1, 2), ChatChoices.indices(MarkdownParser.parse("$prompt\n\n$options")))
            }
        }
    }

    @Test
    fun laterQuestionOwnsOnlyItsOwnOptions() {
        val blocks = MarkdownParser.parse("Pick one:\nA. Old first\nB. Old second\n\nWhich route now?\n1. New first\n2. New second")
        assertEquals(setOf(4, 5), ChatChoices.indices(blocks))
    }

    @Test
    fun labelsMatchReaderBadgesWithoutChangingClassicNumbers() {
        assertEquals("B", ChatChoices.label("2.", true))
        assertEquals("2", ChatChoices.label("2.", false))
        assertEquals("B", ChatChoices.label("b.", false))
        assertEquals("AA", ChatChoices.label("27.", true))
    }
}

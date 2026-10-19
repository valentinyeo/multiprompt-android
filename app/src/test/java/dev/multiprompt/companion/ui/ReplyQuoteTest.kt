package dev.multiprompt.companion.ui

import dev.multiprompt.companion.skills.Skill
import dev.multiprompt.companion.skills.SkillModel
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
        val skill = Skill("review", "Review", "Check it", "repo", "main")
        assertEquals(
            "> Kraków\n\nmy answer\n\n/review\n\n<skill name=\"review\">\nCheck it\n</skill>",
            SkillModel.composeMessage(ReplyQuote.compose("Kraków", "my answer"), skill),
        )
    }
}

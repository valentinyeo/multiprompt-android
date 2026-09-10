package dev.multiprompt.companion.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the skill model: index parsing, cache round-trip, frontmatter, injection. */
class SkillsTest {

    private val indexJson = """
    {
      "skills": [
        {"name": "zebra", "description": "last alphabetically", "file": "skills-harness/zebra/SKILL.md", "source": "harness"},
        {"name": "unslop", "description": "Cut AI tells from any writing.", "file": "unslop-SKILL.md", "source": "multiprompt"},
        {"name": "i-have-adhd", "description": "ADHD-shaped output.", "file": "i-have-adhd-SKILL.md", "source": "multiprompt"},
        {"name": "no-body", "description": "broken entry", "file": "", "source": "harness"}
      ]
    }
    """.trimIndent()

    private fun skill(name: String, source: String = "multiprompt") = Skill(
        name = name,
        description = "desc",
        body = "body of $name",
        repo = "valentinyeo/multiprompt-skills",
        ref = "main",
        source = source,
    )

    // --- index parsing ---------------------------------------------------------------------

    @Test
    fun parseIndexSkipsBrokenEntriesAndSortsByName() {
        val parsed = SkillModel.parseIndex(indexJson)
        assertEquals(listOf("i-have-adhd", "unslop", "zebra"), parsed.entries.map { it.name })
        assertEquals("multiprompt", parsed.entries.first { it.name == "unslop" }.source)
        assertEquals("harness", parsed.entries.first { it.name == "zebra" }.source)
    }

    @Test
    fun parseIndexHandlesEmptyAndGarbage() {
        assertEquals(0, SkillModel.parseIndex("{}").entries.size)
        assertEquals(0, SkillModel.parseIndex("not json").entries.size)
        assertEquals(0, SkillModel.parseIndex("""{"skills": []}""").entries.size)
    }

    // --- cache round-trip --------------------------------------------------------------------

    @Test
    fun cacheRoundTripPreservesSourceAndBodies() {
        val snapshot = SkillSnapshot(
            skills = listOf(skill("unslop"), skill("zebra", source = "harness")),
            fetchedAtEpochSeconds = 1757500000,
        )
        val restored = SkillModel.parseCache(SkillModel.cacheJson(snapshot))
        assertNotNull(restored)
        assertEquals(snapshot.fetchedAtEpochSeconds, restored!!.fetchedAtEpochSeconds)
        assertEquals(2, restored.skills.size)
        val unslop = restored.skills.first { it.name == "unslop" }
        assertEquals("multiprompt", unslop.source)
        assertEquals("body of unslop", unslop.body)
        assertEquals("harness", restored.skills.first { it.name == "zebra" }.source)
    }

    @Test
    fun parseCacheRejectsGarbage() {
        assertNull(SkillModel.parseCache("nonsense"))
    }

    // --- frontmatter ---------------------------------------------------------------------------

    @Test
    fun stripFrontmatterRemovesYamlHeader() {
        val md = "---\nname: unslop\ndescription: cut AI tells\n---\n\n# Unslop\n\nBody text."
        assertEquals("# Unslop\n\nBody text.", SkillModel.stripFrontmatter(md).trim())
    }

    @Test
    fun markdownWithoutFrontmatterIsUntouched() {
        assertEquals("# Plain", SkillModel.stripFrontmatter("# Plain").trim())
    }

    // --- injection -------------------------------------------------------------------------------

    @Test
    fun composeMessageInjectsSkillAlongsidePrompt() {
        val message = SkillModel.composeMessage("fix the login bug", skill("unslop"))
        assertTrue(message.startsWith("fix the login bug\n\n/unslop\n\n<skill name=\"unslop\">"))
        assertTrue(message.contains("body of unslop"))
        assertTrue(message.endsWith("</skill>"))
    }

    @Test
    fun composeMessageSupportsSkillOnlySends() {
        val message = SkillModel.composeMessage("", skill("i-have-adhd"))
        assertTrue(message.startsWith("/i-have-adhd\n\n<skill name=\"i-have-adhd\">"))
        assertTrue(message.contains("body of i-have-adhd"))
    }

    // --- source grouping ---------------------------------------------------------------------------

    @Test
    fun sourceFieldDistinguishesMultipromptFromHarness() {
        val snapshot = SkillSnapshot(
            skills = listOf(
                skill("unslop", source = "multiprompt"),
                skill("zebra", source = "harness"),
            ),
            fetchedAtEpochSeconds = 1,
        )
        assertEquals(1, snapshot.skills.count { it.source == "multiprompt" })
        assertEquals(1, snapshot.skills.count { it.source == "harness" })
    }
}

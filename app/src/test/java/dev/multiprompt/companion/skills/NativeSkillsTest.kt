package dev.multiprompt.companion.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSkillsTest {
    private val skills = listOf(
        NativeSkill("review", "Check the code"),
        NativeSkill("qa:test", "Run checks"),
        NativeSkill("deploy", "Publish safely"),
    )

    @Test fun parsesOnlyNativeCommandMetadata() {
        assertEquals(listOf(NativeSkill("review", "Check it")), NativeSkills.parse("""[{"name":"review","description":"Check it"}]"""))
        assertThrows(Exception::class.java) { NativeSkills.parse("not json") }
    }

    @Test fun skipsRowsWithAnInvalidNameInsteadOfFailingTheWholeList() {
        val json = """[{"name":"bad command"},{"name":"review","description":"Check it"},{"description":"no name"},"text",{"name":"-x"},{"name":"qa:test"}]"""
        assertEquals(listOf(NativeSkill("qa:test", ""), NativeSkill("review", "Check it")), NativeSkills.parse(json))
    }

    @Test fun claudeCommandsRankClearFirstAndANameMatchBeatsADescription() {
        for (name in listOf("clear", "compact", "model", "resume", "context")) {
            assertTrue(NativeSkills.claudeCommands.single { it.name == name }.description.isNotBlank())
        }
        val typed = NativeSkills.menu(
            listOf(NativeSkill("client", "A skill"), NativeSkill("paper", "Clear the log")),
            "cl",
        )
        assertEquals("clear", typed.first().name)
        assertTrue(typed.indexOfFirst { it.name == "client" } < typed.indexOfFirst { it.name == "paper" })
        val order = NativeSkills.menu(emptyList(), "co").map { it.name }
        assertEquals("code-review", order.first())
        assertTrue(order.indexOf("color") < order.indexOf("compact"))
        assertTrue(order.indexOf("compact") < order.indexOf("context"))
        assertTrue(order.indexOf("context") < order.indexOf("copy"))
        assertEquals(
            "clear",
            NativeSkills.menu(listOf(NativeSkill("clearly", "notes"), NativeSkill("notes", "clear the buffer")), "clear").first().name,
        )
        assertEquals(1, NativeSkills.menu(listOf(NativeSkill("clear", "user skill")), "cl").count { it.name == "clear" })
        assertEquals(emptyList<NativeSkill>(), NativeSkills.menu(emptyList(), "zzzz"))
    }

    @Test fun bareSlashListsSessionSkillsBeforeClaudeCommands() {
        val all = NativeSkills.menu(listOf(NativeSkill("review-native", "Review"), NativeSkill("personal-note", "Note")), "").map { it.name }
        assertEquals(listOf("personal-note", "review-native"), all.take(2))
        assertTrue("clear" in all)
    }

    @Test fun slashFiltersNameAndDescriptionWithoutChangingTheDraft() {
        assertEquals("", NativeSkills.query("/", 1))
        assertEquals("rev", NativeSkills.query("/rev", 4))
        assertEquals(listOf(skills[0]), NativeSkills.matches(skills, "REV"))
        assertEquals(listOf(skills[1]), NativeSkills.matches(skills, "checks"))
        assertEquals(emptyList<NativeSkill>(), NativeSkills.matches(skills, "missing"))
        for (text in listOf("ordinary", "http://example.com", "/review task", " /review")) {
            assertNull(NativeSkills.query(text, text.length))
        }
    }

    @Test fun plusAndTypingShareTheSameQueryAndNativeInsertion() {
        val opened = NativeSkills.open("keep this draft")
        assertEquals("", NativeSkills.query(opened, 1))
        assertEquals("/review keep this draft", NativeSkills.insert(opened, "review"))
        assertEquals("/review ", NativeSkills.insert("/rev", "review"))
        assertEquals("/qa:test same text", NativeSkills.insert("/old same text", "qa:test"))
        assertEquals("/review ", NativeSkills.insert(NativeSkills.open(""), "review"))
    }

    @Test fun movingInsideFirstTokenCanReopenWithoutDroppingTheRest() {
        assertEquals("re", NativeSkills.query("/review task", 3))
        assertEquals("/deploy task", NativeSkills.insert("/review task", "deploy"))
        assertEquals("/review task", NativeSkills.open("/review task"))
    }
}

package dev.multiprompt.companion.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    @Test fun slashFiltersNameAndDescriptionWithoutChangingTheDraft() {
        assertEquals("", NativeSkills.query("/", 1))
        assertEquals("rev", NativeSkills.query("/rev", 4))
        assertEquals(listOf(skills[0]), NativeSkills.matches(skills, "REV"))
        assertEquals(listOf(skills[1]), NativeSkills.matches(skills, "checks"))
        assertEquals(emptyList<NativeSkill>(), NativeSkills.matches(skills, "missing"))
        for (text in listOf("ordinary", "http://example.com", "/review task")) {
            assertNull(NativeSkills.query(text, text.length))
        }
        assertEquals("review", NativeSkills.query(" /review", " /review".length))
    }

    @Test fun plusAndTypingShareTheSameQueryAndNativeInsertion() {
        val opened = NativeSkills.open("keep this draft")
        assertEquals("", NativeSkills.query(opened, 1))
        assertEquals("/review keep this draft", NativeSkills.insert(opened, "review", 1).text)
        assertEquals("/review ", NativeSkills.insert("/rev", "review", 4).text)
        assertEquals(8, NativeSkills.insert("/rev", "review", 4).cursor)
        assertEquals("/qa:test same text", NativeSkills.insert("/old same text", "qa:test", 1).text)
        assertEquals("/review ", NativeSkills.insert(NativeSkills.open(""), "review", 1).text)
    }

    @Test fun movingInsideFirstTokenCanReopenWithoutDroppingTheRest() {
        assertEquals("re", NativeSkills.query("/review task", 3))
        assertEquals("/deploy task", NativeSkills.insert("/review task", "deploy", 3).text)
        assertEquals("/review task", NativeSkills.open("/review task"))
    }

    @Test fun slashWordMatchesOnlyAKnownSkill() {
        val known = listOf(NativeSkill("review", "Check"), NativeSkill("home/x", "Path skill"))
        assertEquals(listOf(SkillSpan(0, 7, "review")), NativeSkills.spans("/review", listOf(known[0])))
        assertEquals(listOf(SkillSpan(7, 14, "review")), NativeSkills.spans("please /review this", listOf(known[0])))
        assertEquals(listOf(SkillSpan(7, 14, "review")), NativeSkills.spans("please\n/review", listOf(known[0])))
        assertEquals(emptyList<SkillSpan>(), NativeSkills.spans("/home/x", listOf(known[0])))
        assertEquals(emptyList<SkillSpan>(), NativeSkills.spans("a/b", known))
        assertEquals(emptyList<SkillSpan>(), NativeSkills.spans("http://x/y", known))
        assertEquals(emptyList<SkillSpan>(), NativeSkills.spans("./run", known))
        assertEquals(emptyList<SkillSpan>(), NativeSkills.spans("/reviewer", listOf(known[0])))
        assertEquals(listOf(SkillSpan(0, 7, "home/x")), NativeSkills.spans("/home/x", listOf(known[1])))
        assertEquals("rev", NativeSkills.query("please /rev now", 11))
        assertNull(NativeSkills.query("http://x/y", "http://x/y".length))
        assertNull(NativeSkills.query("./run", "./run".length))
        assertNull(NativeSkills.query("a/b", "a/b".length))
        assertEquals("home/x", NativeSkills.query("/home/x", "/home/x".length))
        val picked = NativeSkills.insert("please /rev now", "review", 11)
        assertEquals("please /review now", picked.text)
        assertEquals(15, picked.cursor)
    }
}

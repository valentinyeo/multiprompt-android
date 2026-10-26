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
        assertThrows(Exception::class.java) { NativeSkills.parse("""[{"name":"bad command"}]""") }
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

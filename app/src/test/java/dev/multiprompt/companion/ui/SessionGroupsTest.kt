package dev.multiprompt.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionGroupsTest {
    private val groups = mapOf("r1" to "Runners", "lead" to null, "r2" to "Runners", "d1" to "Design", "x" to "")

    @Test fun ungroupedChatsComeFirstThenEachGroupInListOrder() {
        val split = SessionGroups.split(listOf("r1", "lead", "r2", "d1", "x")) { groups[it] }
        assertEquals(
            listOf(SessionGroup(null, listOf("lead", "x")), SessionGroup("Runners", listOf("r1", "r2")), SessionGroup("Design", listOf("d1"))),
            split,
        )
    }

    @Test fun noGroupsKeepsOneFlatList() {
        assertEquals(listOf(SessionGroup(null, listOf("a", "b"))), SessionGroups.split(listOf("a", "b")) { null })
        assertEquals(emptyList<SessionGroup<String>>(), SessionGroups.split(emptyList<String>()) { null })
    }

    @Test fun recentsHeadingAppearsOnLooseChatsOnlyWhenNamedGroupsExist() {
        val withGroups = SessionGroups.split(listOf("r1", "lead", "d1")) { groups[it] }
        assertEquals(listOf("Recents", "Runners", "Design"), withGroups.map { SessionGroups.heading(it, withGroups) })
        val flat = SessionGroups.split(listOf("a", "b")) { null }
        assertEquals(listOf(null), flat.map { SessionGroups.heading(it, flat) })
    }
}

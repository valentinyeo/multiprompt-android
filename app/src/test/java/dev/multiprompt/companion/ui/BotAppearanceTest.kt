package dev.multiprompt.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BotAppearanceTest {
    @Test fun workspaceOwnsColorButSessionStillOwnsFace() {
        for ((workspace, color) in listOf("inne" to 2, "Hypertask" to 5, "Software Factory" to 4, "Other" to 0)) {
            val first = BotAppearance.forSession("inne Chief of Staff", workspace)
            val second = BotAppearance.forSession("inne-cro", workspace)
            assertEquals(color, first.color)
            assertEquals(color, second.color)
            assertNotEquals(first.face, second.face)
        }
        assertEquals(2, BotAppearance.forSession("HT Product Lead", " INNE ").color)
    }

    @Test fun desktopCompatibleUtf8AssignmentHasFixedVectors() {
        assertEquals(BotAppearance(0, 0), BotAppearance.forSession("HT Product Lead"))
        assertEquals(BotAppearance(8, 0), BotAppearance.forSession("SF Design Lead"))
        assertEquals(BotAppearance(9, 0), BotAppearance.forSession("机器人"))
        assertNotEquals(BotAppearance.forSession("bot-0"), BotAppearance.forSession("bot-2"))
    }
}

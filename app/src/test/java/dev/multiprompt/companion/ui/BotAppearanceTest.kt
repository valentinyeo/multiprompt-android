package dev.multiprompt.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BotAppearanceTest {
    @Test fun workspaceOwnsColorButSessionStillOwnsFace() {
        for (color in 0..6) {
            val first = BotAppearance.forSession("inne Chief of Staff", color)
            val second = BotAppearance.forSession("inne-cro", color)
            assertEquals(color, first.color)
            assertEquals(color, second.color)
            assertNotEquals(first.face, second.face)
        }
    }

    @Test fun desktopCompatibleUtf8AssignmentHasFixedVectors() {
        assertEquals(BotAppearance(0, 0), BotAppearance.forSession("HT Product Lead"))
        assertEquals(BotAppearance(8, 0), BotAppearance.forSession("SF Design Lead"))
        assertEquals(BotAppearance(9, 0), BotAppearance.forSession("机器人"))
        assertNotEquals(BotAppearance.forSession("bot-0"), BotAppearance.forSession("bot-2"))
    }
}

package dev.multiprompt.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BotAppearanceTest {
    @Test fun desktopCompatibleUtf8AssignmentHasFixedVectors() {
        assertEquals(BotAppearance(0, 3), BotAppearance.forSession("HT Product Lead"))
        assertEquals(BotAppearance(8, 3), BotAppearance.forSession("SF Design Lead"))
        assertEquals(BotAppearance(9, 4), BotAppearance.forSession("机器人"))
        assertNotEquals(BotAppearance.forSession("bot-0"), BotAppearance.forSession("bot-2"))
    }
}

package dev.multiprompt.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BotAppearanceTest {
    @Test fun workspaceOwnsColorButSessionStillOwnsFace() {
        for (color in 0..7) {
            val first = BotAppearance.forSession("inne Chief of Staff", color)
            val second = BotAppearance.forSession("inne-cro", color)
            assertEquals(color, first.color)
            assertEquals(color, second.color)
            assertNotEquals(first.face, second.face)
        }
    }

    @Test fun aBotsOwnPickWinsOverTheWorkspaceColour() {
        val picked = BotAppearance.forSession("HT Product Lead", workspaceColor = 3, face = 17, color = 5)
        assertEquals(17, picked.face)
        assertEquals(5, picked.color)
        val faceOnly = BotAppearance.forSession("HT Product Lead", workspaceColor = 3, face = 17)
        assertEquals(17, faceOnly.face)
        assertEquals("workspace colour still applies when only a face is picked", 3, faceOnly.color)
        val colourOnly = BotAppearance.forSession("HT Product Lead", workspaceColor = 3, color = 5)
        assertEquals(5, colourOnly.color)
        assertEquals("the name still decides the face when only a colour is picked", 0, colourOnly.face)
        val wrapped = BotAppearance.forSession("HT Product Lead", workspaceColor = 3, face = 24, color = 11)
        assertEquals("an over-range face wraps", 4, wrapped.face)
        assertEquals("an over-range colour wraps", 3, wrapped.color)
    }

    @Test fun desktopCompatibleUtf8AssignmentHasFixedVectors() {
        assertEquals(BotAppearance(0, 0), BotAppearance.forSession("HT Product Lead"))
        assertEquals(BotAppearance(8, 0), BotAppearance.forSession("SF Design Lead"))
        assertEquals(BotAppearance(9, 0), BotAppearance.forSession("机器人"))
        assertNotEquals(BotAppearance.forSession("bot-0"), BotAppearance.forSession("bot-2"))
    }
}

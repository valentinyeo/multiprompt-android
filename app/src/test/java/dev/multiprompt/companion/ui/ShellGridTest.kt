package dev.multiprompt.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellGridTest {
    @Test fun theGridFillsTheWholePhoneHeight() {
        val grid = shellGrid(widthPx = 1080f, heightPx = 2000f, charWidthPx = 20f, lineHeightPx = 40f)
        assertEquals(50, grid.rows)
        assertEquals(54, grid.columns)
        assertTrue("the grid must cover the viewport", grid.rows * 40f >= 2000f - 40f)
    }

    @Test fun aKeyboardThatTakesHalfTheScreenHalvesTheRows() {
        val full = shellGrid(widthPx = 1080f, heightPx = 2000f, charWidthPx = 20f, lineHeightPx = 40f)
        val withKeyboard = shellGrid(widthPx = 1080f, heightPx = 1100f, charWidthPx = 20f, lineHeightPx = 40f)
        assertEquals(50, full.rows)
        assertEquals(27, withKeyboard.rows)
        assertTrue(withKeyboard.rows < full.rows)
    }

    @Test fun aTinyOrUnmeasuredViewportFallsBackInsteadOfCrashing() {
        assertEquals(ShellGrid(24, 80), shellGrid(0f, 0f, 20f, 40f))
        assertEquals(ShellGrid(24, 80), shellGrid(1080f, 2000f, 0f, 40f))
        assertEquals(ShellGrid(24, 80), shellGrid(1080f, 2000f, 20f, 0f))
    }

    @Test fun aViewportNarrowerThanOneCharacterStillGivesAUsableGrid() {
        val grid = shellGrid(widthPx = 5f, heightPx = 5f, charWidthPx = 20f, lineHeightPx = 40f)
        assertEquals(4, grid.rows)
        assertEquals(10, grid.columns)
    }
}

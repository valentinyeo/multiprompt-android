package dev.multiprompt.companion.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteMarkerTest {
    @Test
    fun dotMatchesDesktopColorAndSize() {
        assertEquals(0xFFF0C44B, FavoriteMarker.DOT_ARGB)
        assertEquals(6, FavoriteMarker.DOT_DP)
    }

    @Test
    fun mainSourcesContainNoBlackStarGlyph() {
        val files = File("src/main").walkTopDown().filter { it.isFile }.toList()
        assertTrue("Source walk must find MultipromptApp.kt", files.any { it.name == "MultipromptApp.kt" })
        val offenders = files.filter { '\u2605' in it.readText() }
        assertTrue("Black star glyph found in: ${offenders.joinToString { it.path }}", offenders.isEmpty())
    }
}

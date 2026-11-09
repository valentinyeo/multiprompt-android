package dev.multiprompt.companion.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BotColourVisibilityTest {
    private fun contrast(a: Color, b: Color): Float {
        val high = max(a.luminance(), b.luminance())
        val low = min(a.luminance(), b.luminance())
        return (high + 0.05f) / (low + 0.05f)
    }

    @Test fun theEightSoftColoursMatchTheDesktop() {
        assertEquals(
            listOf(0xFF8FCB6B, 0xFFE9875A, 0xFFE8C65A, 0xFF6F9FE0, 0xFFA77FE0, 0xFFE77B98, 0xFF5CCFC0, 0xFFEDEDED).map { Color(it) },
            (0 until BOT_COLORS).map(::botColor),
        )
    }

    @Test fun everySoftColourStandsOutOnTheDarkTile() {
        (0 until BOT_COLORS).forEach { index ->
            assertTrue("colour $index is lost on the tile", contrast(botColor(index), Color(0xFF1E1E1E)) >= 3f)
        }
    }

    @Test fun retiredColourNumbersAreNotPaletteColours() {
        (0 until BOT_COLORS).forEach { assertEquals(it, botPaletteColor(it)) }
        listOf(-1, 8, 9, 10, 11).forEach { assertNull(botPaletteColor(it)) }
        assertNull(botPaletteColor(null))
    }
}

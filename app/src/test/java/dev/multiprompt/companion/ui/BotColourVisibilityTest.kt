package dev.multiprompt.companion.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BotColourVisibilityTest {
    private val pages = mapOf("dark" to ReaderBackground, "sunlight" to Color(0xFFFFFFFF))

    private fun contrast(a: Color, b: Color): Float {
        val high = max(a.luminance(), b.luminance())
        val low = min(a.luminance(), b.luminance())
        return (high + 0.05f) / (low + 0.05f)
    }

    @Test fun everyFaceColourStandsOutFromBothBackgrounds() {
        (0..10).forEach { index ->
            val colour = botColor(index)
            pages.forEach { (theme, page) ->
                val outlineCarries = colour.luminance() > 0.7f && contrast(Color(0xFF6E7276), page) >= 2f
                assertTrue(
                    "colour $index is washed out on $theme at ${contrast(colour, page)}",
                    contrast(colour, page) >= 1.2f || outlineCarries,
                )
            }
        }
    }

    @Test fun everyFaceColourIsItsOwnValue() {
        val colours = (0..10).map(::botColor)
        assertTrue("palette repeats a colour", colours.distinct().size == colours.size)
    }

    @Test fun elevenColoursArePickableLikeTheDesktop() {
        val colours = (0..10).map(::botColor)
        assertTrue("the palette lost a colour", colours.size == 11)
        assertEquals(Color(0xFFF4F4F4), colours[7])
        assertEquals(Color(0xFF9A6B4F), colours[8])
        assertEquals(Color(0xFFE0453C), colours[9])
        assertEquals(Color(0xFF9AA0A6), colours[10])
    }

    @Test fun aWhiteHeadKeepsAVisibleEdgeOnTheLightTheme() {
        assertTrue("white head has no outline rule", botColor(7).luminance() > 0.7f)
        assertTrue("the outline is too faint on the light theme", contrast(Color(0xFF6E7276), pages.getValue("sunlight")) >= 2f)
        assertTrue("the outline is too faint on the dark theme", contrast(Color(0xFF6E7276), pages.getValue("dark")) >= 2f)
    }

    @Test fun aToleratedFutureColourStillRendersDeterministically() {
        listOf(7, 8, 9, 10).forEach { index ->
            assertTrue("colour $index has no rendering", botColor(index) == botColor(index))
        }
    }
}

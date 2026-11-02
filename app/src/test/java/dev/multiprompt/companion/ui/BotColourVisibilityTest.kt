package dev.multiprompt.companion.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
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
        (0..6).forEach { index ->
            val colour = botColor(index)
            pages.forEach { (theme, page) ->
                assertTrue(
                    "colour $index is washed out on $theme at ${contrast(colour, page)}",
                    contrast(colour, page) >= 1.2f,
                )
            }
        }
    }

    @Test fun everyFaceColourIsItsOwnValue() {
        val colours = (0..6).map(::botColor)
        assertTrue("palette repeats a colour", colours.distinct().size == colours.size)
    }

    @Test fun aToleratedFutureColourStillRendersDeterministically() {
        listOf(7, 8, 9, 10).forEach { index ->
            assertTrue("colour $index has no rendering", botColor(index) == botColor(index))
        }
    }
}

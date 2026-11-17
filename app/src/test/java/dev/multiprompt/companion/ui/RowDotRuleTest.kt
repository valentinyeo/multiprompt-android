package dev.multiprompt.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RowDotRuleTest {
    @Test
    fun unreadAlwaysWinsOverFavorite() {
        for (favorite in listOf(false, true)) {
            for (inFavoritesView in listOf(false, true)) {
                assertEquals(RowDot.BLUE, RowDotRule.pick(unread = true, favorite = favorite, inFavoritesView = inFavoritesView))
            }
        }
    }

    @Test
    fun readFavoriteShowsYellowOutsideFavorites() {
        assertEquals(RowDot.YELLOW, RowDotRule.pick(unread = false, favorite = true, inFavoritesView = false))
    }

    @Test
    fun readFavoriteShowsNoDotInsideFavorites() {
        assertEquals(RowDot.NONE, RowDotRule.pick(unread = false, favorite = true, inFavoritesView = true))
    }

    @Test
    fun readNonFavoriteShowsNoDot() {
        assertEquals(RowDot.NONE, RowDotRule.pick(unread = false, favorite = false, inFavoritesView = false))
        assertEquals(RowDot.NONE, RowDotRule.pick(unread = false, favorite = false, inFavoritesView = true))
    }

    @Test
    fun matchesDesktopRulePerCombination() {
        for (unread in listOf(false, true)) {
            for (favorite in listOf(false, true)) {
                for (inFavoritesView in listOf(false, true)) {
                    val expected = if (unread) RowDot.BLUE else if (favorite && !inFavoritesView) RowDot.YELLOW else RowDot.NONE
                    assertEquals(expected, RowDotRule.pick(unread, favorite, inFavoritesView))
                }
            }
        }
    }
}

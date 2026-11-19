package dev.multiprompt.companion.ssh

import dev.multiprompt.companion.ssh.TmuxText.ReaderBlock
import dev.multiprompt.companion.ssh.TmuxText.ReaderBlockKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPaneBlocksTest {
    private fun blocks(count: Int) = (1..count).map { ReaderBlock(ReaderBlockKind.PROSE, "line $it") }

    private fun watchPane(cycles: Int): String = buildString {
        for (index in 1..cycles) {
            append("❯ check the release watch at tag v0.1.$index\n")
            append("the release watch still trails the newest tag by ${index % 60 + 1} minutes\n")
        }
        append("──────────\n")
        append("LONG_PANE_TAIL_2840\n")
    }

    @Test
    fun shortPaneShowsEveryBlock() {
        val window = ReaderPaneBlocks.window(blocks(ReaderPaneBlocks.PAGE - 1), 1)

        assertEquals(ReaderPaneBlocks.PAGE - 1, window.blocks.size)
        assertFalse(window.hasEarlier)
    }

    @Test
    fun longPaneShowsTheNewestPageFirst() {
        val window = ReaderPaneBlocks.window(blocks(2951), 1)

        assertEquals(ReaderPaneBlocks.PAGE, window.blocks.size)
        assertEquals("line ${2951 - ReaderPaneBlocks.PAGE + 1}", window.blocks.first().text)
        assertEquals("line 2951", window.blocks.last().text)
        assertTrue(window.hasEarlier)
    }

    @Test
    fun eachPageRevealsOneMorePageOfEarlierBlocks() {
        val all = blocks(ReaderPaneBlocks.PAGE * 2 + 5)

        val second = ReaderPaneBlocks.window(all, 2)
        val third = ReaderPaneBlocks.window(all, 3)

        assertEquals(ReaderPaneBlocks.PAGE * 2, second.blocks.size)
        assertTrue(second.hasEarlier)
        assertEquals(all, third.blocks)
        assertFalse(third.hasEarlier)
    }

    @Test
    fun emptyPaneNeverAsksForEarlierBlocks() {
        val window = ReaderPaneBlocks.window(emptyList(), 1)

        assertTrue(window.blocks.isEmpty())
        assertFalse(window.hasEarlier)
    }

    @Test
    fun aLongWatchPaneSplitsIntoThousandsOfRenderedBubbles() {
        val split = TmuxText.readerBlocks(watchPane(1475))
            .filter { it.kind == ReaderBlockKind.PROSE || it.kind == ReaderBlockKind.USER_PROMPT }

        assertEquals(2951, split.size)
        assertEquals("LONG_PANE_TAIL_2840", split.last().text)
        assertTrue(split.count { it.kind == ReaderBlockKind.USER_PROMPT } >= 1475)
        assertTrue(ReaderPaneBlocks.window(split, 1).hasEarlier)
        assertEquals(ReaderPaneBlocks.PAGE, ReaderPaneBlocks.window(split, 1).blocks.size)
    }
}

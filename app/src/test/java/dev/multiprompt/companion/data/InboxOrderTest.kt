package dev.multiprompt.companion.data

import dev.multiprompt.companion.model.TmuxSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InboxOrderTest {
    private fun session(name: String) = TmuxSession(
        hostId = "host-1",
        name = name,
        windows = 1,
        attachedClients = 1,
        lastActivityEpochSeconds = 100L,
    )

    @Test
    fun pinnedChatSitsAtTheVisualTopInBothSortOrders() {
        val newest = session("newest")
        val middle = session("middle")
        val pinned = session("pinned")
        val listed = listOf(newest, middle, pinned)
        val pins = listOf(PinnedChats.key(pinned))

        assertEquals(
            listOf("newest", "middle", "pinned"),
            InboxOrder.display(listed, pins, newestAtBottom = true).map { it.name },
        )
        assertEquals(
            listOf("pinned", "newest", "middle"),
            InboxOrder.display(listed, pins, newestAtBottom = false).map { it.name },
        )
    }

    @Test
    fun archivingThePinnedTopRowOpensTheRowBelowIt() {
        val newest = session("newest")
        val middle = session("middle")
        val pinned = session("pinned")
        val displayed = InboxOrder.display(listOf(newest, middle, pinned), listOf(PinnedChats.key(pinned)), newestAtBottom = true)
        assertEquals(listOf("newest", "middle", "pinned"), displayed.map { it.name })
        assertEquals(middle, InboxOrder.nextAfterRemoving(displayed, displayed.lastIndex))

        val oldest = session("oldest")
        val flat = InboxOrder.display(listOf(pinned, middle, oldest), listOf(PinnedChats.key(pinned)), newestAtBottom = false)
        assertEquals(listOf("pinned", "middle", "oldest"), flat.map { it.name })
        assertEquals(middle, InboxOrder.nextAfterRemoving(flat, 0))
    }

    @Test
    fun archivingAMiddleRowOpensTheRowBelowIt() {
        val first = session("first")
        val second = session("second")
        val third = session("third")
        val displayed = listOf(first, second, third)

        assertEquals(second, InboxOrder.nextAfterRemoving(displayed, 0))
        assertEquals(third, InboxOrder.nextAfterRemoving(displayed, 1))
        assertEquals(second, InboxOrder.nextAfterRemoving(displayed, 2))
    }

    @Test
    fun archivingAnUnlistedChatOpensTheTopRowAndAnEmptiedListOpensNothing() {
        val only = session("only")

        assertEquals(only, InboxOrder.nextAfterRemoving(listOf(only), -1))
        assertEquals(only, InboxOrder.nextAfterRemoving(listOf(only, session("other")), -1))
        assertNull(InboxOrder.nextAfterRemoving(listOf(only), 0))
        assertNull(InboxOrder.nextAfterRemoving(emptyList<TmuxSession>(), 0))
    }
}

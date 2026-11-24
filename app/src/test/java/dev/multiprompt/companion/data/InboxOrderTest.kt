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
    fun topToBottomPutsPinsFirstInPinOrderInBothSortOrders() {
        val newest = session("newest")
        val middle = session("middle")
        val oldest = session("oldest")
        val firstPin = session("first-pin")
        val secondPin = session("second-pin")
        val listed = listOf(newest, middle, firstPin, oldest, secondPin)
        val pins = listOf(PinnedChats.key(firstPin), PinnedChats.key(secondPin))

        assertEquals(
            listOf("first-pin", "second-pin", "oldest", "middle", "newest"),
            InboxOrder.topToBottom(listed, pins, newestAtBottom = true).map { it.name },
        )
        assertEquals(
            listOf("first-pin", "second-pin", "newest", "middle", "oldest"),
            InboxOrder.topToBottom(listed, pins, newestAtBottom = false).map { it.name },
        )
    }

    @Test
    fun archivingThePinnedTopRowOpensTheRowBelowIt() {
        val newest = session("newest")
        val middle = session("middle")
        val oldest = session("oldest")
        val pinned = session("pinned")
        val pins = listOf(PinnedChats.key(pinned))

        val newestAtBottom = InboxOrder.topToBottom(listOf(newest, middle, oldest, pinned), pins, newestAtBottom = true)
        assertEquals(listOf("pinned", "oldest", "middle", "newest"), newestAtBottom.map { it.name })
        assertEquals(oldest, InboxOrder.nextAfterRemoving(newestAtBottom, 0))

        val newestAtTop = InboxOrder.topToBottom(listOf(newest, middle, oldest, pinned), pins, newestAtBottom = false)
        assertEquals(listOf("pinned", "newest", "middle", "oldest"), newestAtTop.map { it.name })
        assertEquals(newest, InboxOrder.nextAfterRemoving(newestAtTop, 0))
    }

    @Test
    fun archivingALowerPinnedRowOpensTheUnpinnedRowBelowItNotThePinAboveIt() {
        val newest = session("newest")
        val oldest = session("oldest")
        val firstPin = session("first-pin")
        val secondPin = session("second-pin")
        val pins = listOf(PinnedChats.key(firstPin), PinnedChats.key(secondPin))

        val topToBottom = InboxOrder.topToBottom(listOf(newest, firstPin, oldest, secondPin), pins, newestAtBottom = true)
        assertEquals(listOf("first-pin", "second-pin", "oldest", "newest"), topToBottom.map { it.name })
        assertEquals(oldest, InboxOrder.nextAfterRemoving(topToBottom, 1))
        assertEquals(firstPin, InboxOrder.nextAfterRemoving(topToBottom, 2))
    }

    @Test
    fun archivingAnUnpinnedRowOpensTheRowBelowItAndTheRowAboveOnlyAtTheBottom() {
        val newest = session("newest")
        val middle = session("middle")
        val oldest = session("oldest")
        val pinned = session("pinned")
        val topToBottom = InboxOrder.topToBottom(
            listOf(newest, middle, oldest, pinned),
            listOf(PinnedChats.key(pinned)),
            newestAtBottom = true,
        )

        assertEquals(listOf("pinned", "oldest", "middle", "newest"), topToBottom.map { it.name })
        assertEquals(newest, InboxOrder.nextAfterRemoving(topToBottom, 2))
        assertEquals(middle, InboxOrder.nextAfterRemoving(topToBottom, 3))
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

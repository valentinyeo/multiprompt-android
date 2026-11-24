package dev.multiprompt.companion.data

import dev.multiprompt.companion.model.TmuxSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedChatsTest {
    private fun session(name: String, hostId: String = "host-1", activity: Long = 100L) = TmuxSession(
        hostId = hostId,
        name = name,
        windows = 1,
        attachedClients = 1,
        lastActivityEpochSeconds = activity,
    )

    @Test
    fun pinningMovesChatFirstAndPreservesPinOrder() {
        val a = session("alpha")
        val b = session("beta")
        val c = session("gamma")
        val d = session("delta")
        val normal = listOf(a, b, c, d)

        val pinsAfterFirst = PinnedChats.pin(emptyList(), PinnedChats.key(c))
        assertEquals(listOf("gamma", "alpha", "beta", "delta"), PinnedChats.order(normal, pinsAfterFirst).map { it.name })

        val pinsAfterSecond = PinnedChats.pin(pinsAfterFirst, PinnedChats.key(b))
        assertEquals(listOf("gamma", "beta", "alpha", "delta"), PinnedChats.order(normal, pinsAfterSecond).map { it.name })

        val repinned = PinnedChats.pin(pinsAfterSecond, PinnedChats.key(c))
        assertEquals(pinsAfterSecond, repinned)
        assertEquals(listOf("gamma", "beta", "alpha", "delta"), PinnedChats.order(normal, repinned).map { it.name })
    }

    @Test
    fun unpinningRestoresNormalOrder() {
        val a = session("alpha")
        val b = session("beta")
        val c = session("gamma")
        val interactions = mapOf(
            SessionReadStore.key("host-1", "alpha") to 300L,
            SessionReadStore.key("host-1", "beta") to 200L,
            SessionReadStore.key("host-1", "gamma") to 100L,
        )
        val normal = SessionSearch.newestFirst(listOf(c, a, b), interactions)
        assertEquals(listOf("alpha", "beta", "gamma"), normal.map { it.name })

        val pinned = PinnedChats.pin(emptyList(), PinnedChats.key(c))
        assertEquals(listOf("gamma", "alpha", "beta"), PinnedChats.order(normal, pinned).map { it.name })
        assertTrue(PinnedChats.isPinned(pinned, c))
        assertFalse(PinnedChats.isPinned(pinned, a))

        val unpinned = PinnedChats.unpin(pinned, PinnedChats.key(c))
        assertEquals(listOf("alpha", "beta", "gamma"), PinnedChats.order(normal, unpinned).map { it.name })
        assertFalse(PinnedChats.isPinned(unpinned, c))
    }

    @Test
    fun newReplyOrInteractionElsewhereDoesNotPushPinnedChatDown() {
        val pinnedChat = session("important", activity = 100L)
        val busyChat = session("busy", activity = 200L)
        val quietChat = session("quiet", activity = 50L)
        val pins = PinnedChats.pin(emptyList(), PinnedChats.key(pinnedChat))

        val busyAfterReply = busyChat.copy(
            lastActivityEpochSeconds = 5_000L,
            outputActivityEpochSeconds = 5_000L,
            replyAtEpochSeconds = 5_000L,
            replyWaiting = true,
        )
        val interactions = mapOf(
            SessionReadStore.key("host-1", "important") to 100L,
            SessionReadStore.key("host-1", "busy") to 5_000L,
        )
        val normal = SessionSearch.newestFirst(listOf(quietChat, pinnedChat, busyAfterReply), interactions)
        assertEquals(listOf("busy", "important", "quiet"), normal.map { it.name })

        val ordered = PinnedChats.order(normal, pins)
        assertEquals(listOf("important", "busy", "quiet"), ordered.map { it.name })
    }

    @Test
    fun pinnedChatRemovedFromHostLeavesRemainingPinsAndNormalOrderIntact() {
        val kept = session("kept")
        val other = session("other")
        val gone = session("gone")
        val pins = PinnedChats.pin(PinnedChats.pin(emptyList(), PinnedChats.key(gone)), PinnedChats.key(kept))

        val remaining = listOf(other, kept)
        assertEquals(listOf("kept", "other"), PinnedChats.order(remaining, pins).map { it.name })

        val otherHost = session("remote", hostId = "host-2")
        val multiHostPins = PinnedChats.pin(pins, PinnedChats.key(otherHost))
        val afterHostRemoval = PinnedChats.removeHost(multiHostPins, "host-1")
        assertEquals(listOf(PinnedChats.key(otherHost)), afterHostRemoval)
    }

    @Test
    fun sameSessionNameOnDifferentHostsPinsIndependently() {
        val hostOne = session("main", hostId = "host-1")
        val hostTwo = session("main", hostId = "host-2")
        val pins = PinnedChats.pin(emptyList(), PinnedChats.key(hostTwo))

        assertFalse(PinnedChats.isPinned(pins, hostOne))
        assertTrue(PinnedChats.isPinned(pins, hostTwo))
        assertEquals(listOf("host-2", "host-1"), PinnedChats.order(listOf(hostOne, hostTwo), pins).map { it.hostId })
    }

    @Test
    fun newestAtBottomOrderingPutsPinnedChatsAtTheVisualTop() {
        val a = session("alpha")
        val b = session("beta")
        val c = session("gamma")
        val d = session("delta")
        val normal = listOf(a, b, c, d)
        val pins = PinnedChats.pin(PinnedChats.pin(emptyList(), PinnedChats.key(c)), PinnedChats.key(b))

        assertEquals(listOf("gamma", "beta", "alpha", "delta"), PinnedChats.order(normal, pins).map { it.name })

        val rendered = PinnedChats.order(normal, pins, atEnd = true)
        assertEquals(listOf("alpha", "delta", "beta", "gamma"), rendered.map { it.name })
        assertEquals(listOf("gamma", "beta"), rendered.asReversed().take(2).map { it.name })
    }

    @Test
    fun pruneDropsPinsForSessionsNoLongerListedOnThatHost() {
        val renamed = session("old-name")
        val kept = session("kept")
        val elsewhere = session("main", hostId = "host-2")
        val pins = listOf(PinnedChats.key(renamed), PinnedChats.key(kept), PinnedChats.key(elsewhere))

        assertEquals(
            listOf(PinnedChats.key(kept), PinnedChats.key(elsewhere)),
            PinnedChats.prune(pins, "host-1", listOf("kept", "new-name")),
        )
        assertEquals(pins, PinnedChats.prune(pins, "host-1", listOf("kept", "old-name")))
        assertEquals(emptyList<String>(), PinnedChats.prune(emptyList(), "host-1", emptyList()))
    }

    @Test
    fun encodeAndDecodeRoundTripPreservesOrderAndIgnoresBadInput() {
        val pins = listOf("host-1::gamma", "host-2::alpha")
        assertEquals(pins, PinnedChats.decode(PinnedChats.encode(pins)))
        assertEquals(emptyList<String>(), PinnedChats.decode(null))
        assertEquals(emptyList<String>(), PinnedChats.decode(""))
        assertEquals(emptyList<String>(), PinnedChats.decode("{bad"))
        assertEquals(
            listOf("host-1::a", "host-1::b"),
            PinnedChats.decode("""["host-1::a","","  ",42,"host-1::a","host-1::b"]"""),
        )
    }
}

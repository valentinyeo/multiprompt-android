package dev.multiprompt.companion.data

import dev.multiprompt.companion.model.TmuxSession
import org.junit.Assert.assertEquals
import org.junit.Test

class UnreadOnlyFilterTest {
    private fun session(host: String, name: String) =
        TmuxSession(hostId = host, name = name, windows = 1, attachedClients = 0, lastActivityEpochSeconds = 1)

    private val a = session("h1", "alpha")
    private val b = session("h1", "beta")
    private val c = session("h2", "alpha")
    private val all = listOf(a, b, c)
    private val unread = setOf(SessionReadStore.key("h1", "beta"), SessionReadStore.key("h2", "alpha"))

    @Test
    fun onKeepsOnlyUnreadSessionsInOrder() {
        assertEquals(listOf(b, c), UnreadOnlyFilter.visible(all, unread, enabled = true))
    }

    @Test
    fun offKeepsEverySession() {
        assertEquals(all, UnreadOnlyFilter.visible(all, unread, enabled = false))
    }

    @Test
    fun sameNameOnAnotherHostIsNotConfused() {
        assertEquals(listOf(c), UnreadOnlyFilter.visible(all, setOf(SessionReadStore.key("h2", "alpha")), enabled = true))
    }

    @Test
    fun noUnreadSessionsLeavesAnEmptyList() {
        assertEquals(emptyList<TmuxSession>(), UnreadOnlyFilter.visible(all, emptySet(), enabled = true))
    }

    @Test
    fun searchIgnoresTheFilterSoAnyChatCanBeFound() {
        assertEquals(all, UnreadOnlyFilter.visible(all, unread, enabled = true, searching = true))
    }
}

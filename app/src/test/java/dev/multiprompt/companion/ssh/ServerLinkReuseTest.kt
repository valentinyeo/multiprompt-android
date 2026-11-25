package dev.multiprompt.companion.ssh

import dev.multiprompt.companion.model.HostProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerLinkReuseTest {
    private class FakeLink(val number: Int) {
        var alive = true
        var closed = false
    }

    private class Server {
        val opened = mutableListOf<FakeLink>()

        fun open(): FakeLink = FakeLink(opened.size + 1).also(opened::add)
    }

    private val host = HostProfile(
        id = "vps",
        label = "VPS",
        hostname = "vps.example.com",
        username = "dev",
        hostKeyType = "ssh-ed25519",
        hostKeyFingerprint = "SHA256:trusted",
    )

    private var clock = 1_000L

    private fun pool() = LinkPool<FakeLink>(
        isAlive = { it.alive && !it.closed },
        close = { it.closed = true },
        now = { clock },
    )

    private suspend fun <T> LinkPool<FakeLink>.listing(server: Server, block: suspend (FakeLink) -> T): T =
        use(host.id, LinkPolicy.identity(host), LinkPolicy.LIST_STALL_MS, { server.open() }, block)

    private suspend fun LinkPool<FakeLink>.chatOpen(server: Server): LinkPool.Rented<FakeLink> =
        rent(host.id, LinkPolicy.identity(host)) { server.open() }

    @Test
    fun fiveListingsAndOneChatOpenShareOneLogin() = runBlocking {
        val server = Server()
        val pool = pool()
        repeat(5) { pool.listing(server) { } }
        val stream = pool.chatOpen(server)
        val history = pool.chatOpen(server)
        repeat(3) { pool.listing(server) { } }
        pool.sweep(idleMs = 0L)
        assertEquals(1, server.opened.size)
        assertSame(stream.link, history.link)
        stream.release()
        history.release()
        var listed = 0
        pool.listing(server) { link -> listed = link.number }
        assertEquals(1, listed)
        assertEquals(1, server.opened.size)
        assertFalse(server.opened.single().closed)
    }

    @Test
    fun droppedLinkReconnectsOnce() = runBlocking {
        val server = Server()
        val pool = pool()
        pool.listing(server) { }
        val stream = pool.chatOpen(server)
        server.opened.single().alive = false
        var listed = 0
        pool.listing(server) { link -> listed = link.number }
        assertTrue(server.opened.first().closed)
        assertEquals(2, server.opened.size)
        stream.release()
        pool.listing(server) { link -> listed = link.number }
        assertEquals(2, listed)
        assertEquals(2, server.opened.size)
    }

    @Test
    fun aChatOpenAfterADropReconnectsOnce() = runBlocking {
        val server = Server()
        val pool = pool()
        repeat(3) { pool.listing(server) { } }
        server.opened.single().alive = false
        val next = pool.chatOpen(server)
        assertEquals(2, next.link.number)
        assertEquals(2, server.opened.size)
        next.release()
    }
}

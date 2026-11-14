package dev.multiprompt.companion.ssh

import dev.multiprompt.companion.model.HostProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LinkPoolTest {
    private class FakeLink(val number: Int) {
        var alive = true
        var closed = false
    }

    private class Server(var refuseWith: Throwable? = null) {
        val opened = mutableListOf<FakeLink>()
        fun open(): FakeLink {
            refuseWith?.let { throw it }
            return FakeLink(opened.size + 1).also(opened::add)
        }
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

    private suspend fun <T> LinkPool<FakeLink>.refresh(
        server: Server,
        profile: HostProfile = host,
        repeatable: Boolean = true,
        stallMs: Long? = LinkPolicy.LIST_STALL_MS,
        block: suspend (FakeLink) -> T,
    ): T = use(profile.id, LinkPolicy.identity(profile), repeatable, stallMs, { server.open() }, block)

    @Test
    fun tenListRefreshesUseOneLogin() = runBlocking {
        val server = Server()
        val pool = pool()
        val used = (1..10).map { pool.refresh(server) { link -> link.number } }
        assertEquals(List(10) { 1 }, used)
        assertEquals(1, server.opened.size)
        assertFalse(server.opened.single().closed)
    }

    @Test
    fun droppedLinkComesBackOnTheNextRefresh() = runBlocking {
        val server = Server()
        val pool = pool()
        pool.refresh(server) { }
        server.opened.single().alive = false
        val number = pool.refresh(server) { link -> link.number }
        assertEquals(2, number)
        assertEquals(2, server.opened.size)
        assertTrue(server.opened.first().closed)
    }

    @Test
    fun linkThatDiesDuringARefreshReconnectsOnceAndAnswers() = runBlocking {
        val server = Server()
        val pool = pool()
        pool.refresh(server) { }
        val answer = pool.refresh(server) { link ->
            if (link.number == 1) {
                link.alive = false
                throw SshProblem.Connection("The VPS returned an incomplete tmux response. Refresh to retry.")
            }
            "sessions from link ${link.number}"
        }
        assertEquals("sessions from link 2", answer)
        assertEquals(2, server.opened.size)
    }

    @Test
    fun reconnectHappensOnlyOnceBeforeTheErrorShows() = runBlocking {
        val server = Server()
        val pool = pool()
        pool.refresh(server) { }
        var attempts = 0
        try {
            pool.refresh(server) { link ->
                attempts++
                link.alive = false
                throw SshProblem.Connection("dropped ${link.number}")
            }
            fail("expected the second drop to surface")
        } catch (error: SshProblem.Connection) {
            assertEquals("dropped 2", error.message)
        }
        assertEquals(2, attempts)
        assertEquals(2, server.opened.size)
    }

    @Test
    fun changedServerIdentityIsStillRefusedAndNeverCached() = runBlocking {
        val changed = PresentedHostKey("ssh-ed25519", "SHA256:attacker", changed = true)
        val server = Server(refuseWith = SshProblem.HostKeyRequired(changed))
        val pool = pool()
        repeat(2) {
            try {
                pool.refresh(server) { fail("no command may run on a refused server") }
                fail("expected the changed host key to be refused")
            } catch (error: SshProblem.HostKeyRequired) {
                assertSame(changed, error.presented)
                assertTrue(error.presented.changed)
            }
        }
        assertEquals(0, server.opened.size)
    }

    @Test
    fun hostKeyChangeOnAReusedLinkRunsPinningAgainAndSurfaces() = runBlocking {
        val server = Server()
        val pool = pool()
        pool.refresh(server) { }
        server.opened.single().alive = false
        val changed = PresentedHostKey("ssh-ed25519", "SHA256:attacker", changed = true)
        server.refuseWith = SshProblem.HostKeyRequired(changed)
        try {
            pool.refresh(server) { }
            fail("expected the changed host key to be refused")
        } catch (error: SshProblem.HostKeyRequired) {
            assertSame(changed, error.presented)
        }
        assertTrue(server.opened.single().closed)
    }

    @Test
    fun hostProfileChangeInvalidatesThePooledLink() = runBlocking {
        val server = Server()
        val pool = pool()
        pool.refresh(server) { }
        val edits = listOf(
            host.copy(hostname = "other.example.com"),
            host.copy(port = 2222),
            host.copy(username = "root"),
            host.copy(keySecretId = "host_key_new"),
            host.copy(hostKeyFingerprint = "SHA256:rotated"),
            host.copy(hostKeyType = "ecdsa-sha2-nistp256"),
            host.copy(hostKeys = listOf("ssh-ed25519 AAAA")),
        )
        edits.forEachIndexed { index, edited ->
            assertEquals(index + 2, pool.refresh(server, edited) { link -> link.number })
        }
        assertEquals(edits.size + 1, server.opened.size)
        assertTrue(server.opened.dropLast(1).all { it.closed })
        assertEquals(edits.size + 1, pool.refresh(server, edits.last()) { link -> link.number })
    }

    @Test
    fun sentActionIsNeverRepeatedAfterTheLinkDrops() = runBlocking {
        val server = Server()
        val pool = pool()
        pool.refresh(server) { }
        var sends = 0
        try {
            pool.refresh(server, repeatable = false) { link ->
                sends++
                link.alive = false
                throw SshProblem.Connection("Could not send the prompt")
            }
            fail("expected the send failure to surface")
        } catch (error: SshProblem.Connection) {
            assertEquals("Could not send the prompt", error.message)
        }
        assertEquals(1, sends)
        assertEquals(2, pool.refresh(server) { link -> link.number })
    }

    @Test
    fun unsentActionRetriesOnceOnAFreshLink() = runBlocking {
        val server = Server()
        val pool = pool()
        pool.refresh(server) { }
        val sentOn = pool.refresh(server, repeatable = false) { link ->
            if (link.number == 1) {
                link.alive = false
                throw SshProblem.LinkUnavailable("The SSH server refused a command channel")
            }
            link.number
        }
        assertEquals(2, sentOn)
        assertTrue(server.opened.first().closed)
    }

    @Test
    fun commandErrorKeepsAHealthyLink() = runBlocking {
        val server = Server()
        val pool = pool()
        try {
            pool.refresh(server) { throw SshProblem.Connection("The VPS cannot run tmux: no server running") }
            fail("expected the tmux error to surface")
        } catch (error: SshProblem.Connection) {
            assertEquals("The VPS cannot run tmux: no server running", error.message)
        }
        assertEquals(1, pool.refresh(server) { link -> link.number })
        assertEquals(1, server.opened.size)
    }

    @Test
    fun stalledReusedLinkIsReplacedForARefresh() = runBlocking {
        val server = Server()
        val pool = pool()
        pool.refresh(server) { }
        val number = pool.refresh(server, stallMs = 50L) { link ->
            if (link.number == 1) delay(5_000L)
            link.number
        }
        assertEquals(2, number)
        assertTrue(server.opened.first().closed)
    }

    @Test
    fun timedOutCommandDropsTheLinkSoTheNextRefreshReconnects() = runBlocking {
        val server = Server()
        val pool = pool()
        pool.refresh(server) { }
        val timedOut = withTimeoutOrNull(50L) {
            pool.refresh(server, repeatable = false, stallMs = null) { delay(5_000L) }
        }
        assertNull(timedOut)
        assertTrue(server.opened.single().closed)
        assertEquals(2, pool.refresh(server) { link -> link.number })
    }

    @Test
    fun idleLinkClosesAfterTheIdlePeriodOnly() = runBlocking {
        val server = Server()
        val pool = pool()
        pool.refresh(server) { }
        clock += LinkPolicy.IDLE_MS - 1
        pool.sweep()
        assertFalse(server.opened.single().closed)
        clock += 1
        pool.sweep()
        assertTrue(server.opened.single().closed)
        assertEquals(2, pool.refresh(server) { link -> link.number })
    }

    @Test
    fun backgroundCloseSparesALinkStillInUse() = runBlocking {
        val server = Server()
        val pool = pool()
        pool.refresh(server) { pool.sweep(idleMs = 0L) }
        assertFalse(server.opened.single().closed)
        pool.sweep(idleMs = 0L)
        assertTrue(server.opened.single().closed)
    }

    @Test
    fun refusedChannelOnAHealthyLinkKeepsTheSharedLink() = runBlocking {
        val server = Server()
        val pool = pool()
        pool.refresh(server) { }
        var tries = 0
        val sentOn = pool.refresh(server, repeatable = false) { link ->
            tries++
            if (tries == 1) throw SshProblem.LinkUnavailable("The SSH server refused a command channel")
            link.number
        }
        assertEquals(1, sentOn)
        assertEquals(2, tries)
        assertEquals(1, server.opened.size)
        assertFalse(server.opened.single().closed)
    }

    @Test
    fun failurePolicyTable() {
        val unsent = SshProblem.LinkUnavailable("refused")
        val command = SshProblem.Connection("tmux failed")
        fun outcome(error: Throwable, alive: Boolean, attempt: Int = 0, reused: Boolean = true, repeatable: Boolean) =
            LinkPolicy.afterFailure(error, alive, attempt, reused, repeatable)
        assertEquals(LinkPolicy.Outcome(evict = true, retry = true), outcome(command, alive = false, repeatable = true))
        assertEquals(LinkPolicy.Outcome(evict = true, retry = false), outcome(command, alive = false, repeatable = false))
        assertEquals(LinkPolicy.Outcome(evict = true, retry = true), outcome(unsent, alive = false, repeatable = false))
        assertEquals(LinkPolicy.Outcome(evict = false, retry = true), outcome(unsent, alive = true, repeatable = false))
        assertEquals(LinkPolicy.Outcome(evict = false, retry = false), outcome(command, alive = true, repeatable = true))
        assertEquals(LinkPolicy.Outcome(evict = true, retry = true), outcome(LinkPolicy.Stalled(), alive = true, repeatable = true))
        assertEquals(LinkPolicy.Outcome(evict = true, retry = false), outcome(command, alive = false, reused = false, repeatable = true))
        assertEquals(LinkPolicy.Outcome(evict = false, retry = false), outcome(unsent, alive = true, attempt = 1, repeatable = true))
    }
}

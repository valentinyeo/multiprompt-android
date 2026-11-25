package dev.multiprompt.companion.ssh

import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.security.SecretStorage
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.connectbot.sshlib.AuthResult
import org.connectbot.sshlib.ConnectResult
import org.connectbot.sshlib.HostKeyVerifier
import org.connectbot.sshlib.KeyFingerprint
import org.connectbot.sshlib.PublicKey
import org.connectbot.sshlib.SshClient
import org.connectbot.sshlib.SshClientConfig
import org.connectbot.sshlib.SshSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class LinkPoolSshTest {
    private lateinit var host: HostProfile
    private lateinit var key: ByteArray
    private val clients = CopyOnWriteArrayList<SshClient>()
    private val logins = AtomicInteger()
    private val connecting = AtomicInteger()
    private val peakConnecting = AtomicInteger()
    private var loginStarted: CompletableDeferred<Unit>? = null
    private var allowLogin: CompletableDeferred<Unit>? = null

    @Before
    fun fixture() {
        assumeTrue("Run link-pool-proof.yml for the real sshd fixture", System.getenv("POOL_SSH_KEY") != null)
        key = File(System.getenv("POOL_SSH_KEY")).readBytes()
        host = HostProfile(
            id = "pool-proof",
            label = "Pool proof",
            hostname = "127.0.0.1",
            port = 2222,
            username = "poolssh",
            keySecretId = "proof-key",
            hostKeyType = "ssh-ed25519",
            hostKeyFingerprint = System.getenv("POOL_SSH_FINGERPRINT"),
        )
    }

    private suspend fun login(): SshClient {
        val active = connecting.incrementAndGet()
        peakConnecting.updateAndGet { maxOf(it, active) }
        try {
            loginStarted?.complete(Unit)
            allowLogin?.await()
            val client = SshClient(SshClientConfig {
                this.host = this@LinkPoolSshTest.host.hostname
                port = this@LinkPoolSshTest.host.port
                hostKeyVerifier = object : HostKeyVerifier {
                    override suspend fun verify(key: PublicKey): Boolean =
                        key.type == this@LinkPoolSshTest.host.hostKeyType &&
                            KeyFingerprint.sha256(key.encoded) == this@LinkPoolSshTest.host.hostKeyFingerprint
                }
                autoDisconnectOnLastChannelClose = false
                keepAliveIntervalMs = LinkPolicy.KEEPALIVE_MS
            })
            clients.add(client)
            assertTrue(client.connect() is ConnectResult.Success)
            assertTrue(client.authenticatePublicKey(host.username, key, null) is AuthResult.Success)
            logins.incrementAndGet()
            return client
        } finally {
            connecting.decrementAndGet()
        }
    }

    private fun pool() = LinkPool<SshClient>(
        isAlive = { it.isAuthenticated },
        close = { it.disconnect() },
    )

    private suspend fun <T> LinkPool<SshClient>.command(block: suspend (SshClient) -> T): T =
        use(host.id, LinkPolicy.identity(host), stallMs = null, open = { login() }, block = block)

    private suspend fun channel(client: SshClient): SshSession = client.openSession()
        ?: throw SshProblem.LinkUnavailable("The SSH server refused a command channel")

    private suspend fun output(client: SshClient, command: String): String {
        val session = channel(client)
        try {
            assertTrue(session.requestExec(command))
            val text = StringBuilder()
            for (chunk in session.stdout) text.append(chunk.toString(Charsets.UTF_8))
            return text.toString()
        } finally {
            session.close()
        }
    }

    @Test
    fun eleventhChannelUsesSecondLogin() = runBlocking {
        withTimeout(30_000L) {
            val pool = pool()
            val opened = Channel<SshClient>(Channel.UNLIMITED)
            val finish = CompletableDeferred<Unit>()
            try {
                coroutineScope {
                    val streams = (1..11).map {
                        async {
                            pool.command { client ->
                                val session = channel(client)
                                try {
                                    assertTrue(session.requestExec("sleep 60"))
                                    opened.send(client)
                                    finish.await()
                                } finally {
                                    session.close()
                                }
                            }
                        }
                    }
                    val channels = List(11) { opened.receive() }
                    assertEquals(2, logins.get())
                    assertEquals(listOf(1, 10), channels.groupingBy { it }.eachCount().values.sorted())
                    assertTrue(clients.all { it.isAuthenticated })
                    finish.complete(Unit)
                    streams.awaitAll()
                }
                pool.command { assertEquals("still-pooled", output(it, "printf still-pooled")) }
                assertEquals(2, logins.get())
            } finally {
                finish.complete(Unit)
                pool.dropAll()
            }
        }
    }

    @Test
    fun fiveDyingStreamsShareOneReconnectAndDeliverEveryQueuedSendOnce() = runBlocking {
        withTimeout(45_000L) {
            val pool = pool()
            val ready = Channel<Unit>(Channel.UNLIMITED)
            val drop = CompletableDeferred<Unit>()
            val queued = List(5) { Channel<String>(Channel.UNLIMITED) }
            val receipt = "/home/poolssh/pending-${UUID.randomUUID()}.txt"
            try {
                pool.command { }
                coroutineScope {
                    val streams = queued.map { pending ->
                        async {
                            pool.command { client ->
                                val session = channel(client)
                                try {
                                    assertTrue(session.requestExec("while IFS= read -r line; do printf '%s\\n' \"\$line\" >> '$receipt'; printf '%s\\n' \"\$line\"; done"))
                                    if (client === clients.first()) {
                                        ready.send(Unit)
                                        drop.await()
                                        throw SshProblem.LinkUnavailable("The link dropped before the pending send")
                                    }
                                    val message = pending.receive()
                                    session.write("$message\n".toByteArray())
                                    val reply = StringBuilder()
                                    while (!reply.endsWith("\n")) {
                                        reply.append(session.stdout.receive().toString(Charsets.UTF_8))
                                    }
                                    assertEquals("$message\n", reply.toString())
                                } finally {
                                    session.close()
                                }
                            }
                        }
                    }
                    repeat(5) { ready.receive() }
                    clients.first().disconnect()
                    queued.forEachIndexed { index, pending -> pending.send("pending-$index") }
                    loginStarted = CompletableDeferred()
                    allowLogin = CompletableDeferred()
                    drop.complete(Unit)
                    loginStarted!!.await()
                    assertEquals(1, connecting.get())
                    allowLogin!!.complete(Unit)
                    streams.awaitAll()
                }
                assertEquals(2, logins.get())
                assertEquals(1, peakConnecting.get())
                val delivered = pool.command { output(it, "cat '$receipt'") }.lines().filter { it.isNotBlank() }
                assertEquals((0..4).map { "pending-$it" }.sorted(), delivered.sorted())
                assertEquals(5, delivered.size)
            } finally {
                allowLogin?.complete(Unit)
                drop.complete(Unit)
                pool.dropAll()
            }
        }
    }

    @Test
    fun unreadReaderDoesNotBlockListingOrSendOnSameLink() = runBlocking {
        withTimeout(30_000L) {
            val pool = pool()
            val lease = pool.rent(host.id, LinkPolicy.identity(host)) { login() }
            val stalled = channel(lease.link)
            val marker = "/home/poolssh/drained-${UUID.randomUUID()}"
            try {
                assertTrue(stalled.requestExec("dd if=/dev/zero bs=1048576 count=64 2>/dev/null; touch '$marker'; sleep 60"))
                delay(1_000L)
                withTimeout(5_000L) {
                    val listing = async {
                        pool.command { client ->
                            assertSame(lease.link, client)
                            output(client, "test ! -e '$marker' && printf listing")
                        }
                    }
                    val send = async {
                        pool.command { client ->
                            assertSame(lease.link, client)
                            val session = channel(client)
                            try {
                                assertTrue(session.requestExec("IFS= read -r line; printf '%s' \"\$line\""))
                                session.write("sent-once\n".toByteArray())
                                val reply = StringBuilder()
                                for (chunk in session.stdout) reply.append(chunk.toString(Charsets.UTF_8))
                                reply.toString()
                            } finally {
                                session.close()
                            }
                        }
                    }
                    assertEquals("listing", listing.await())
                    assertEquals("sent-once", send.await())
                }
                assertTrue(stalled.isOpen)
                assertFalse(stalled.exitInfo.isCompleted)
                assertEquals(1, logins.get())
            } finally {
                stalled.close()
                lease.release()
                pool.dropAll()
            }
        }
    }

    @Test
    fun productionKeepalivePreservesIdleStreamBeyondServerDeadline() = runBlocking {
        withTimeout(160_000L) {
            val secrets = object : SecretStorage {
                override fun get(id: String): ByteArray? = if (id == host.keySecretId) key else null
                override fun put(id: String, value: ByteArray) = error("No key writes during proof")
                override fun remove(id: String?) = error("No key removal during proof")
            }
            val repository = SshRepository(secrets)
            val lease = repository.rentServerLink(host)
            val stream = channel(lease.client)
            try {
                assertTrue(LinkPolicy.KEEPALIVE_MS < 30_000L * 4)
                assertTrue(stream.requestExec("sleep 180"))
                delay(125_000L)
                assertTrue(stream.isOpen)
                assertTrue(lease.client.isAuthenticated)
                repository.withServerLink(host) { client ->
                    assertSame(lease.client, client)
                    assertEquals("idle-alive", output(client, "printf idle-alive"))
                }
            } finally {
                stream.close()
                lease.releaseNow()
                repository.dropServerLinks()
            }
        }
    }
}

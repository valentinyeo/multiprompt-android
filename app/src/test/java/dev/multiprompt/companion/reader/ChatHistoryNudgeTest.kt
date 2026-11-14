package dev.multiprompt.companion.reader

import android.app.Application
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.security.SecretStore
import dev.multiprompt.companion.ssh.SshRepository
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatHistoryNudgeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val host = HostProfile(id = "host", label = "invalid", hostname = "", port = 0,
        username = "user", keySecretId = "missing")
    private val identity = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "tmux", "/cwd")
    private fun repository() = SshRepository(SecretStore(RuntimeEnvironment.getApplication()))

    @Test
    fun nudgeRetriesAtOnceInsteadOfWaitingOutTheBackoff(): Unit = runBlocking {
        val attempts = AtomicInteger()
        val chat = ChatHistoryConnection(repository(), host, identity, byteArrayOf(), ChatHistoryCache(temporary.newFolder()),
            gate = MessageGate(), connectedExchange = { _ -> attempts.incrementAndGet(); throw IOException("down") })
        chat.use {
            chat.select(true)
            chat.start()
            withTimeout(10_000L) { chat.state.first { it.retrying } }
            assertEquals(1, attempts.get())
            assertTrue(chat.nudge())
            withTimeout(1_000L) { while (attempts.get() < 2) delay(10) }
        }
    }

    @Test
    fun nudgeOnAChatThatNeverFailedDoesNothing() {
        ChatHistoryConnection(repository(), host, identity, byteArrayOf(), ChatHistoryCache(temporary.newFolder())).use { chat ->
            assertFalse(chat.nudge())
        }
    }
}

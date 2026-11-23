package dev.multiprompt.companion.reader

import android.app.Application
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.security.SecretStore
import dev.multiprompt.companion.ssh.SshRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SessionReaderConcurrentLiveTest {
    @get:Rule val temporary = TemporaryFolder()

    private val host = HostProfile(
        id = "host",
        label = "host",
        hostname = "invalid.example",
        port = 22,
        username = "user",
        keySecretId = "missing",
    )

    private fun reader(name: String) = SessionReaderConnection(
        SshRepository(SecretStore(RuntimeEnvironment.getApplication())),
        host,
        name,
        historyScript = byteArrayOf(),
        historyCache = ChatHistoryCache(temporary.newFolder()),
    )

    @Test
    fun concurrentReadersUseSeparatePollGates() {
        val first = reader("one")
        val second = reader("two")
        val firstGate = ReflectionHelpers.getField<ChatPollGate>(first, "pollGate")
        val secondGate = ReflectionHelpers.getField<ChatPollGate>(second, "pollGate")
        assertNotSame(firstGate, secondGate)
    }

    @Test
    fun twoStartedReadersBothNeedVisibilityBeforeStreaming() = runBlocking {
        val first = reader("one")
        val second = reader("two")
        try {
            coroutineScope {
                val jobs = listOf(
                    async { first.start() },
                    async { second.start() },
                )
                jobs.forEach { it.await() }
            }
            assertFalse(gateActive(first))
            assertFalse(gateActive(second))
            val firstLease = first.observe()
            val secondLease = second.observe()
            assertTrue(gateActive(first))
            assertTrue(gateActive(second))
            firstLease.close()
            assertFalse(gateActive(first))
            assertTrue(gateActive(second))
            secondLease.close()
        } finally {
            first.close()
            second.close()
        }
    }

    private fun gateActive(connection: SessionReaderConnection): Boolean =
        ReflectionHelpers.getField<ChatPollGate>(connection, "pollGate").active.value
}
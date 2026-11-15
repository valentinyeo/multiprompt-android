package dev.multiprompt.companion.reader

import android.app.Application
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.security.SecretStore
import dev.multiprompt.companion.ssh.SshRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SessionPiModelsLoadTest {
    @get:Rule val temporary = TemporaryFolder()
    private val host = HostProfile(id = "host", label = "invalid", hostname = "", port = 0,
        username = "user", keySecretId = "missing")
    private val models = listOf(PiModel("gpt-5.6-sol", "openai-codex", "272K"))

    private fun reader(agent: AgentKind = AgentKind.PI) = SessionReaderConnection(
        SshRepository(SecretStore(RuntimeEnvironment.getApplication())), host, "live-abc",
        agent = agent, historyScript = byteArrayOf(),
        historyCache = ChatHistoryCache(temporary.newFolder()),
    )

    @Test fun aLoadedListReplacesTheError() = runBlocking {
        reader().use { reader ->
            reader.loadPiModels { models }
            val state = withTimeout(10_000) { reader.piModels.first { it.models.isNotEmpty() } }
            assertEquals(models, state.models)
            assertNull(state.error)
            assertFalse(state.loading)
        }
    }

    @Test fun anEmptyListSaysThereAreNoModels() = runBlocking {
        reader().use { reader ->
            reader.loadPiModels { emptyList() }
            val state = withTimeout(10_000) { reader.piModels.first { !it.loading && it.error != null } }
            assertEquals(PiModels.EMPTY, state.error)
            assertTrue(state.models.isEmpty())
        }
    }

    @Test fun failureAndTimeoutStopLoading() = runBlocking {
        reader().use { reader ->
            reader.loadPiModels { error("down") }
            val failed = withTimeout(10_000) { reader.piModels.first { it.error == PiModels.FAILED } }
            assertFalse(failed.loading)
        }
        reader().use { reader ->
            reader.loadPiModels {
                withTimeout(20) { delay(5_000) }
                models
            }
            val timedOut = withTimeout(10_000) { reader.piModels.first { it.error == PiModels.FAILED } }
            assertFalse(timedOut.loading)
            assertTrue(timedOut.models.isEmpty())
        }
    }

    @Test fun aSecondLoadWaitsUntilTheFirstFinishes() = runBlocking {
        val calls = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        reader().use { reader ->
            reader.loadPiModels {
                calls.incrementAndGet()
                release.await()
                error("down")
            }
            reader.loadPiModels { error("second load must not run") }
            assertTrue(reader.piModels.value.loading)
            release.complete(Unit)
            val failed = withTimeout(10_000) { reader.piModels.first { it.error == PiModels.FAILED && !it.loading } }
            delay(50)
            assertEquals(PiModels.FAILED, failed.error)
            assertEquals(1, calls.get())
            reader.loadPiModels { models }
            val loaded = withTimeout(10_000) { reader.piModels.first { it.models.isNotEmpty() } }
            assertEquals(models, loaded.models)
            assertNull(loaded.error)
            assertEquals(2, calls.get())
        }
    }

    @Test fun otherAgentsDoNotAskForPiModels() = runBlocking {
        reader(AgentKind.CLAUDE).use { reader ->
            reader.loadPiModels { error("claude must not load pi models") }
            assertEquals(PiModelList(), reader.piModels.value)
        }
    }
}

package dev.multiprompt.companion.reader

import android.app.Application
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.security.SecretStore
import dev.multiprompt.companion.skills.NativeSkill
import dev.multiprompt.companion.skills.SkillsLoader
import dev.multiprompt.companion.ssh.SshProblem
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
class SessionSkillsLoadTest {
    @get:Rule val temporary = TemporaryFolder()
    private val host = HostProfile(id = "host", label = "invalid", hostname = "", port = 0,
        username = "user", keySecretId = "missing")
    private val skills = listOf(NativeSkill("review", "Check it"))

    private fun reader() = SessionReaderConnection(
        SshRepository(SecretStore(RuntimeEnvironment.getApplication())), host, "live-abc",
        agent = AgentKind.CLAUDE, historyScript = byteArrayOf(),
        historyCache = ChatHistoryCache(temporary.newFolder()),
    )

    private suspend fun SessionReaderConnection.settled(): ReaderState =
        withTimeout(10_000L) { state.first { !it.skillsLoading && (it.skillsError != null || it.nativeSkills.isNotEmpty()) } }

    @Test fun retryThenSuccessShowsTheSkillsWithoutAnError() = runBlocking {
        val calls = AtomicInteger()
        reader().use { reader ->
            reader.loadSkills(retryDelayMillis = 0) {
                if (calls.incrementAndGet() == 1) throw SshProblem.Connection("Connection reset") else skills
            }
            val state = reader.settled()
            assertEquals(skills, state.nativeSkills)
            assertNull(state.skillsError)
            assertEquals(2, calls.get())
        }
    }

    @Test fun failureShowsTheRealReasonAndStopsLoading() = runBlocking {
        reader().use { reader ->
            reader.loadSkills(retryDelayMillis = 0) { throw SshProblem.Connection("Could not read skills: no python3") }
            val state = reader.settled()
            assertFalse(state.skillsLoading)
            assertEquals("Could not load skills: Could not read skills: no python3 (tap to retry)", state.skillsError)
        }
    }

    @Test fun timeoutClearsLoadingAndSaysSo() = runBlocking {
        reader().use { reader ->
            reader.loadSkills(retryDelayMillis = 0) {
                withTimeout(20) { delay(5_000) }
                skills
            }
            val state = reader.settled()
            assertFalse(state.skillsLoading)
            assertEquals(SkillsLoader.TIMEOUT_MESSAGE, state.skillsError)
        }
    }

    @Test fun reloadIsSkippedWhileOneIsRunningAndTapRetryWorksAfterwards() = runBlocking {
        val calls = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        reader().use { reader ->
            reader.loadSkills(retryDelayMillis = 0) {
                calls.incrementAndGet()
                release.await()
                throw SshProblem.Connection("down")
            }
            reader.loadSkills(retryDelayMillis = 0) { error("second load must not run") }
            assertTrue(reader.state.value.skillsLoading)
            release.complete(Unit)
            assertTrue(reader.settled().skillsError!!.startsWith("Could not load skills: down"))
            assertEquals(2, calls.get())
            reader.loadSkills(retryDelayMillis = 0) { skills }
            val state = reader.settled()
            assertEquals(skills, state.nativeSkills)
            assertNull(state.skillsError)
        }
    }
}

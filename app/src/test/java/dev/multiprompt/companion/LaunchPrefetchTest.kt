package dev.multiprompt.companion

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import dev.multiprompt.companion.ssh.SshRepository
import java.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LaunchPrefetchTest {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()
    private lateinit var model: MainViewModel
    private lateinit var state: MutableStateFlow<AppUiState>
    private lateinit var foreground: MutableStateFlow<Boolean>
    private val host = HostProfile("host", "VPS", "", port = 0, username = "user", keySecretId = "missing")
    private var sessions = (1..10).map { index ->
        TmuxSession(
            hostId = host.id,
            name = "live-chat-%02d".format(index),
            windows = 1,
            attachedClients = 0,
            lastActivityEpochSeconds = 100L + index,
            paneCommand = "sleep",
            hostSessionId = "sid-$index",
        )
    }
    private var listCalls = 0
    private val warmed = mutableListOf<List<TmuxSession>>()
    private val prefetched = mutableListOf<List<TmuxSession>>()

    @Before fun setup() {
        val app = MultipromptApplication()
        ReflectionHelpers.callInstanceMethod<Unit>(
            app,
            "attach",
            ReflectionHelpers.ClassParameter.from(Context::class.java, RuntimeEnvironment.getApplication()),
        )
        app.getSharedPreferences("hosts", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("updates", Context.MODE_PRIVATE).edit()
            .putLong("last_check_ms", System.currentTimeMillis()).commit()
        model = MainViewModel(app).also { models.put("main", it) }
        state = ReflectionHelpers.getField(model, "_state")
        foreground = ReflectionHelpers.getField(model, "foreground")
        model.sessionLister = { listCalls++; SshRepository.HostSessions(sessions.map { it.copy() }) }
        model.historyWarmer = { _, batch -> warmed.add(batch) }
        model.historyPrefetcher = { _, batch -> prefetched.add(batch) }
        state.value = AppUiState(hosts = listOf(host))
        foreground.value = true
        model.setInboxVisible(true)
        advance(0)
    }

    @After fun cleanup() {
        models.clear()
    }

    private fun advance(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            !model.state.value.refreshing
        }
    }

    private fun initialRefresh() {
        compose.runOnIdle { model.refresh() }
        advance(0)
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            listCalls >= 1 && !model.state.value.refreshing && warmed.isNotEmpty()
        }
    }

    @Test fun coldLaunchWarmsDiskCacheWithoutRemoteHistoryPrefetch() {
        initialRefresh()
        assertEquals(
            listOf("live-chat-10", "live-chat-09", "live-chat-08", "live-chat-07"),
            warmed.single().map { it.name },
        )
        assertTrue(prefetched.isEmpty())
    }

    @Test fun openingAChatPrefetchesTopFourNeighboringChatsExcludingOpenOne() {
        initialRefresh()
        assertTrue(prefetched.isEmpty())
        compose.runOnIdle { model.openReader(sessions.last(), userInitiated = true) }
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            prefetched.isNotEmpty()
        }
        assertEquals(1, prefetched.size)
        assertEquals(
            listOf("live-chat-09", "live-chat-08", "live-chat-07", "live-chat-06"),
            prefetched.single().map { it.name },
        )
    }

    @Test fun subsequentInboxRefreshDoesNotTriggerRemoteHistoryPrefetch() {
        initialRefresh()
        sessions = sessions.map { session ->
            if (session.name == "live-chat-10") session.copy(lastActivityEpochSeconds = 500L) else session
        }
        advance(2_000)
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            listCalls >= 2 && !model.state.value.refreshing
        }
        assertTrue(prefetched.isEmpty())
    }

    @Test fun openingAPlainShellDoesNotPrefetchChatHistories() {
        val shell = TmuxSession(host.id, "shell", 1, 0, 50L, paneCommand = "bash")
        sessions = sessions + shell
        initialRefresh()
        compose.runOnIdle { model.openReader(shell, userInitiated = true) }
        advance(1_000)
        assertTrue(prefetched.isEmpty())
    }
}

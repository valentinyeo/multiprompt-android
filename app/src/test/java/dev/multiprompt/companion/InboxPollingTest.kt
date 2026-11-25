package dev.multiprompt.companion

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import dev.multiprompt.companion.ssh.SshRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class InboxPollingTest {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()
    private lateinit var model: MainViewModel
    private lateinit var state: MutableStateFlow<AppUiState>
    private lateinit var foreground: MutableStateFlow<Boolean>
    private val host = HostProfile("host", "VPS", "example.com", username = "user")
    private var sessions = listOf(TmuxSession(host.id, "chat", 1, 0, 1))
    private var calls = 0
    private var collecting: Job? = null

    @Before fun setup() {
        val app = MultipromptApplication()
        ReflectionHelpers.callInstanceMethod<Unit>(app, "attach",
            ReflectionHelpers.ClassParameter.from(Context::class.java, RuntimeEnvironment.getApplication()))
        app.getSharedPreferences("hosts", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("updates", Context.MODE_PRIVATE).edit()
            .putLong("last_check_ms", System.currentTimeMillis()).commit()
        model = MainViewModel(app).also { models.put("main", it) }
        state = ReflectionHelpers.getField(model, "_state")
        foreground = ReflectionHelpers.getField(model, "foreground")
        model.sessionLister = { calls++; SshRepository.HostSessions(sessions.map { it.copy() }) }
        model.historyPrefetcher = { _, _ -> }
        model.historyWarmer = { _, _ -> }
        state.value = AppUiState(hosts = listOf(host))
        foreground.value = true
        model.setInboxVisible(true)
        advance(0)
    }

    @After fun cleanup() { collecting?.cancel(); models.clear() }

    private fun advance(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            !model.state.value.refreshing
        }
    }

    private fun firstPoll() {
        advance(2_000)
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            calls > 0 && !model.state.value.refreshing
        }
        assertEquals(1, calls)
    }

    @Test fun unchangedListsBackOffToEightSecondsLeavingTwoSecondsForDelivery() {
        firstPoll()
        advance(1_999); assertEquals(1, calls)
        advance(1); assertEquals(2, calls)
        advance(3_999); assertEquals(2, calls)
        advance(1); assertEquals(3, calls)
        advance(7_999); assertEquals(3, calls)
        advance(1); assertEquals(4, calls)
        repeat(3) {
            advance(7_999); assertEquals(4 + it, calls)
            advance(1); assertEquals(5 + it, calls)
        }
    }

    @Test fun changedListResetsTheNextIntervalToTwoSeconds() {
        firstPoll()
        advance(2_000)
        sessions = sessions.map { it.copy(lastActivityEpochSeconds = 2, preview = "New reply") }
        advance(4_000); assertEquals(3, calls)
        assertEquals("New reply", model.state.value.sessions.single().preview)
        advance(1_999); assertEquals(3, calls)
        advance(1); assertEquals(4, calls)
    }

    @Test fun userRefreshResetsBackoff() {
        firstPoll(); advance(2_000); advance(4_000); advance(8_000)
        val before = calls
        compose.runOnIdle { model.refresh() }
        advance(0)
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            calls == before + 1 && !model.state.value.refreshing
        }
        advance(1_999); assertEquals(before + 1, calls)
        advance(1); assertEquals(before + 2, calls)
    }

    @Test fun aTapWithoutARefreshAlsoRestartsTheTwoSecondInterval() {
        firstPoll(); advance(2_000); advance(4_000); advance(8_000)
        val before = calls
        model.onUserInteraction()
        advance(1_999); assertEquals(before, calls)
        advance(1); assertEquals(before + 1, calls)
    }

    @Test fun aNewReplyAtMaximumBackoffArrivesWithinTenSeconds() {
        firstPoll(); advance(2_000); advance(4_000); advance(8_000)
        sessions = sessions.map { it.copy(lastActivityEpochSeconds = 2, preview = "New reply") }
        advance(7_999)
        assertNotEquals("New reply", model.state.value.sessions.single().preview)
        advance(1)
        assertEquals("New reply", model.state.value.sessions.single().preview)
        val before = calls
        advance(2_000); assertEquals(before + 1, calls)
    }

    @Test fun realResumeRefreshesImmediatelyAndRestartsFast() {
        firstPoll(); advance(2_000); advance(4_000); advance(8_000)
        model.onPause()
        advance(30_000)
        val before = calls
        compose.runOnIdle { model.onResume() }
        advance(0)
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            calls == before + 1 && !model.state.value.refreshing
        }
        advance(1_999); assertEquals(before + 1, calls)
        advance(1); assertEquals(before + 2, calls)
    }

    @Test fun noPollingWhileSettingsHideTheInbox() {
        firstPoll()
        model.setInboxVisible(false)
        advance(30_000); assertEquals(1, calls)
        model.setInboxVisible(true)
        advance(1_999); assertEquals(1, calls)
        advance(1); assertEquals(2, calls)
    }

    @Test fun noPollingWhenBackgroundedEvenAfterTheOldBackgroundInterval() {
        firstPoll()
        foreground.value = false
        advance(120_000)
        assertEquals(1, calls)
        foreground.value = true
        advance(1_999); assertEquals(1, calls)
        advance(1); assertEquals(2, calls)
    }

    @Test fun noPollingBehindOtherScreensAndReturningRestartsFast() {
        firstPoll()
        for (hidden in listOf(
            state.value.copy(section = AppSection.UPDATE),
            state.value.copy(readerSession = sessions.single()),
            state.value.copy(terminalSession = sessions.single()),
            state.value.copy(roomId = "room"),
            state.value.copy(editorVisible = true),
        )) {
            state.value = hidden
            val before = calls
            advance(30_000); assertEquals(before, calls)
            state.value = hidden.copy(section = AppSection.SESSIONS, readerSession = null,
                terminalSession = null, roomId = null, editorVisible = false)
            advance(1_999); assertEquals(before, calls)
            advance(1); assertEquals(before + 1, calls)
        }
    }

    @Test fun identicalAutomaticListEmitsNoStateIncludingLoadingFlags() {
        firstPoll()
        val emitted = mutableListOf<AppUiState>()
        collecting = CoroutineScope(Dispatchers.Main.immediate).launch { model.state.collect { emitted += it } }
        advance(0)
        val before = emitted.size
        advance(2_000)
        assertEquals(2, calls)
        assertEquals(before, emitted.size)
    }
}

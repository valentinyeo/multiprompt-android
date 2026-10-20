package dev.multiprompt.companion

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelStore
import dev.multiprompt.companion.data.SessionReadStore
import dev.multiprompt.companion.data.UnreadCount
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import dev.multiprompt.companion.ssh.PresentedHostKey
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UnreadCountViewModelTest {
    private val models = ViewModelStore()
    private lateinit var application: MultipromptApplication
    private lateinit var model: MainViewModel
    private val host = HostProfile("host", "VPS", "example.com", username = "user", keySecretId = "key")
    private val key = SessionReadStore.key(host.id, "claude")
    private val now = System.currentTimeMillis() / 1000 + 100

    @Before
    fun createModel() {
        application = MultipromptApplication()
        ReflectionHelpers.callInstanceMethod<Unit>(
            application, "attach",
            ReflectionHelpers.ClassParameter.from(Context::class.java, RuntimeEnvironment.getApplication()),
        )
        application.getSharedPreferences("updates", Context.MODE_PRIVATE).edit()
            .putLong("last_check_ms", System.currentTimeMillis()).commit()
        model = MainViewModel(application).also { models.put("main", it) }
    }

    @After
    fun closeModel() {
        models.clear()
    }

    private fun refresh(current: AppUiState, activity: Long, waiting: Boolean, answered: Boolean = true): AppUiState {
        val session = TmuxSession(host.id, "claude", 1, 0, activity, preview = if (waiting) "❯" else "Working")
        val resultClass = Class.forName("dev.multiprompt.companion.MainViewModel\$HostRefresh")
        val result = resultClass.getDeclaredConstructor(
            String::class.java, List::class.java, Map::class.java, String::class.java, PresentedHostKey::class.java,
        ).apply { isAccessible = true }.newInstance(host.id, listOf(session), emptyMap<String, Any>(), null, null)
        val apply = MainViewModel::class.java.getDeclaredMethod(
            "applyRefresh", AppUiState::class.java, List::class.java, Map::class.java, Map::class.java,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        return apply.invoke(
            model, current, listOf(host), mapOf(host.id to listOf(session)),
            if (answered) mapOf(host.id to result) else emptyMap<String, Any>(), true,
        ) as AppUiState
    }

    @Test
    fun refreshCountsFinishedTurnsAndActionsResetState() {
        var state = AppUiState(hosts = listOf(host))
        state = refresh(state, now, waiting = false)
        assertFalse(key in state.unreadSessionKeys)
        state = refresh(state, now + 1, waiting = true)
        assertEquals(1, state.unreadCounts[key])
        state = refresh(state, now + 1, waiting = true)
        assertEquals(1, state.unreadCounts[key])
        state = refresh(state, now + 2, waiting = false)
        state = refresh(state, now + 3, waiting = true)
        assertTrue(key in state.unreadSessionKeys)
        assertEquals(2, state.unreadCounts[key])
        val session = state.sessions.single()
        val mutable = ReflectionHelpers.getField<MutableStateFlow<AppUiState>>(model, "_state")
        mutable.value = state.copy(readerSession = session)
        model.markReaderRead()
        assertFalse(key in model.state.value.unreadSessionKeys)
        assertEquals(0, application.sessionReadStore.unreadCountState(session).count)
        model.markSessionUnread(session)
        assertEquals(1, model.state.value.unreadCounts[key])
        model.archiveSession(session)
        assertFalse(key in model.state.value.unreadSessionKeys)
        assertEquals(0, application.sessionReadStore.unreadCountState(session).count)
    }

    @Test
    fun cachedSnapshotDoesNotCountAndUnobservedUnreadDisplaysOne() {
        var state = refresh(AppUiState(hosts = listOf(host)), now, waiting = false, answered = false)
        assertEquals(UnreadCount.State(), application.sessionReadStore.unreadCountState(state.sessions.single()))
        state = refresh(state, now + 1, waiting = true)
        assertEquals(1, state.unreadCounts[key])
        assertEquals(0, application.sessionReadStore.unreadCountState(state.sessions.single()).count)
    }
}

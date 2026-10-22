package dev.multiprompt.companion

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelStore
import dev.multiprompt.companion.data.HostStore
import dev.multiprompt.companion.model.HostProfile
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
class HostKeyDismissalTest {
    private val models = ViewModelStore()
    private lateinit var application: MultipromptApplication
    private lateinit var model: MainViewModel
    private val host = HostProfile(
        id = "host", label = "VPS", hostname = "example.com", username = "user",
        hostKeyType = "ssh-ed25519", hostKeyFingerprint = "SHA256:saved", hostKeys = listOf("ssh-ed25519 c2F2ZWQ="),
    )
    private val presented = PresentedHostKey("ssh-ed25519", "SHA256:changed", changed = true)
    private val error = "SSH host key changed"

    @Before
    fun createModel() {
        application = MultipromptApplication()
        ReflectionHelpers.callInstanceMethod<Unit>(
            application, "attach",
            ReflectionHelpers.ClassParameter.from(Context::class.java, RuntimeEnvironment.getApplication()),
        )
        application.getSharedPreferences("hosts", Context.MODE_PRIVATE).edit().clear().commit()
        application.getSharedPreferences("updates", Context.MODE_PRIVATE).edit()
            .putLong("last_check_ms", System.currentTimeMillis()).commit()
        model = MainViewModel(application).also { models.put("main", it) }
        application.hostStore.upsert(host)
        setState(AppUiState(hosts = listOf(host), pendingHostKeys = mapOf(host.id to presented), hostErrors = mapOf(host.id to error)))
    }

    @After
    fun closeModel() {
        models.clear()
    }

    @Test
    fun sameDismissedKeyStaysHiddenAfterRepeatedRefreshes() {
        model.dismissHostKey(host.id)
        assertFalse(host.id in model.state.value.pendingHostKeys)
        var state = model.state.value
        repeat(3) {
            state = refresh(state, listOf(host), mapOf(host.id to presented))
            assertFalse(host.id in state.pendingHostKeys)
            assertEquals(error, state.hostErrors[host.id])
        }
    }

    @Test
    fun dismissalSurvivesReloadingHostStore() {
        model.dismissHostKey(host.id)
        val reloaded = HostStore(application).load()
        val state = refresh(AppUiState(hosts = reloaded), reloaded, mapOf(host.id to presented))
        assertFalse(host.id in state.pendingHostKeys)
        assertEquals(error, state.hostErrors[host.id])
    }

    @Test
    fun differentFingerprintShowsAgain() {
        model.dismissHostKey(host.id)
        val different = presented.copy(fingerprint = "SHA256:another")
        val state = refresh(model.state.value, listOf(host), mapOf(host.id to different))
        assertEquals(different, state.pendingHostKeys[host.id])
    }

    @Test
    fun differentTypeWithSameFingerprintShowsAgain() {
        model.dismissHostKey(host.id)
        val different = presented.copy(type = "ssh-rsa")
        val state = refresh(model.state.value, listOf(host), mapOf(host.id to different))
        assertEquals(different, state.pendingHostKeys[host.id])
    }

    @Test
    fun dismissalDoesNotChangePinnedKey() {
        model.dismissHostKey(host.id)
        val reloaded = HostStore(application).load().single()
        assertEquals(host.hostKeyType, reloaded.hostKeyType)
        assertEquals(host.hostKeyFingerprint, reloaded.hostKeyFingerprint)
        assertEquals(host.hostKeys, reloaded.hostKeys)
    }

    @Test
    fun dismissalDoesNotApplyToAnotherHostWithTheSamePresentedKey() {
        val other = host.copy(id = "other", hostname = "other.example.com")
        application.hostStore.upsert(other)
        setState(model.state.value.copy(hosts = listOf(host, other)))
        model.dismissHostKey(host.id)
        val state = refresh(model.state.value, listOf(host, other), mapOf(host.id to presented, other.id to presented))
        assertFalse(host.id in state.pendingHostKeys)
        assertEquals(presented, state.pendingHostKeys[other.id])
        assertTrue(state.hostErrors.keys.containsAll(listOf(host.id, other.id)))
    }

    private fun setState(state: AppUiState) {
        ReflectionHelpers.getField<MutableStateFlow<AppUiState>>(model, "_state").value = state
    }

    private fun refresh(current: AppUiState, snapshot: List<HostProfile>, keys: Map<String, PresentedHostKey>): AppUiState {
        val resultClass = Class.forName("dev.multiprompt.companion.MainViewModel\$HostRefresh")
        val constructor = resultClass.getDeclaredConstructor(
            String::class.java, List::class.java, Map::class.java, String::class.java, PresentedHostKey::class.java,
        ).apply { isAccessible = true }
        val results = keys.mapValues { (hostId, key) ->
            constructor.newInstance(hostId, emptyList<Any>(), emptyMap<String, Any>(), error, key)
        }
        val apply = MainViewModel::class.java.getDeclaredMethod(
            "applyRefresh", AppUiState::class.java, List::class.java, Map::class.java, Map::class.java,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        return apply.invoke(model, current, snapshot, emptyMap<String, Any>(), results, true) as AppUiState
    }
}

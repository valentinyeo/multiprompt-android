package dev.multiprompt.companion

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HostKeyRefreshTest {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()
    private lateinit var application: MultipromptApplication
    private lateinit var model: MainViewModel
    private val host = HostProfile(
        id = "host", label = "VPS", hostname = "example.com", username = "user",
        hostKeyType = "ssh-ed25519", hostKeyFingerprint = "SHA256:saved", hostKeys = listOf("ssh-ed25519 c2F2ZWQ="),
    )
    private val presented = PresentedHostKey("ssh-ed25519", "SHA256:changed", changed = true, publicKey = "ssh-ed25519 Y2hhbmdlZA==")
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
    fun pendingKeySurvivesRefreshStart() {
        val state = refresh(model.state.value, listOf(host), emptyMap(), final = false)
        assertEquals(presented, state.pendingHostKeys[host.id])
        assertTrue(state.refreshing)
    }

    @Test
    fun unansweredKeySurvivesAnotherHostsAnswer() {
        val other = host.copy(id = "other")
        val current = model.state.value.copy(hosts = listOf(host, other))
        val state = refresh(current, listOf(host, other), mapOf(other.id to presented), final = false)
        assertEquals(mapOf(host.id to presented, other.id to presented), state.pendingHostKeys)
    }

    @Test
    fun trustDuringRefreshPinsKeyAndStaleAnswerDoesNotReopenDialog() {
        setState(model.state.value.copy(refreshing = true))
        model.trustHostKey(host.id)
        val trusted = HostStore(application).load().single()
        assertEquals(presented.type, trusted.hostKeyType)
        assertEquals(presented.fingerprint, trusted.hostKeyFingerprint)
        assertEquals(listOf(presented.publicKey), trusted.hostKeys)
        assertFalse(host.id in model.state.value.pendingHostKeys)
        for (final in listOf(false, true)) {
            val state = refresh(model.state.value, listOf(host), mapOf(host.id to presented), final = final)
            assertFalse(host.id in state.pendingHostKeys)
        }
    }

    @Test
    fun differentFingerprintStillShowsAfterTrust() {
        setState(model.state.value.copy(refreshing = true))
        model.trustHostKey(host.id)
        val different = presented.copy(fingerprint = "SHA256:another")
        val state = refresh(model.state.value, listOf(host), mapOf(host.id to different))
        assertEquals(different, state.pendingHostKeys[host.id])
    }

    @Test
    fun differentTypeWithTrustedFingerprintStillShows() {
        setState(model.state.value.copy(refreshing = true))
        model.trustHostKey(host.id)
        val different = presented.copy(type = "ssh-rsa")
        val state = refresh(model.state.value, listOf(host), mapOf(host.id to different))
        assertEquals(different, state.pendingHostKeys[host.id])
    }

    @Test
    fun dismissedFingerprintStaysHidden() {
        model.dismissHostKey(host.id)
        val state = refresh(model.state.value, listOf(host), mapOf(host.id to presented))
        assertFalse(host.id in state.pendingHostKeys)
        assertEquals(error, state.hostErrors[host.id])
    }

    @Test
    fun answeredHostWithoutKeyLosesPendingEntry() {
        val state = refresh(model.state.value, listOf(host), mapOf(host.id to null))
        assertFalse(host.id in state.pendingHostKeys)
    }

    @Test
    fun removedHostsLoseUnansweredAndAnsweredEntries() {
        val current = model.state.value.copy(hosts = emptyList())
        assertTrue(refresh(current, listOf(host), emptyMap(), final = false).pendingHostKeys.isEmpty())
        assertTrue(refresh(current, listOf(host), mapOf(host.id to presented)).pendingHostKeys.isEmpty())
    }

    @Test
    fun trustDuringRunningRefreshQueuesExactlyOneFollowUp() {
        val connections = CopyOnWriteArrayList<Socket>()
        ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { server ->
            val listener = thread(isDaemon = true) {
                try {
                    while (!server.isClosed) {
                        val socket = server.accept()
                        connections.add(socket)
                        if (connections.size > 2) socket.close()
                    }
                } catch (exception: SocketException) {
                    if (!server.isClosed) throw exception
                }
            }
            try {
                val local = host.copy(hostname = "127.0.0.1", port = server.localPort)
                val other = local.copy(id = "other")
                application.hostStore.upsert(local)
                application.hostStore.upsert(other)
                compose.runOnIdle {
                    setState(AppUiState(hosts = listOf(local, other)))
                    model.refresh()
                }
                compose.waitUntil {
                    shadowOf(Looper.getMainLooper()).idle()
                    connections.size == 2
                }
                compose.runOnIdle {
                    assertTrue(model.state.value.refreshing)
                    setState(model.state.value.copy(pendingHostKeys = mapOf(local.id to presented, other.id to presented)))
                    model.trustHostKey(local.id)
                    model.refresh()
                    model.trustHostKey(other.id)
                    model.refresh()
                    assertTrue(model.state.value.pendingHostKeys.isEmpty())
                    assertTrue(HostStore(application).load().all { it.hostKeyFingerprint == presented.fingerprint })
                    connections.forEach { it.close() }
                }
                compose.waitUntil {
                    shadowOf(Looper.getMainLooper()).idle()
                    !model.state.value.refreshing
                }
                assertEquals(4, connections.size)
            } finally {
                server.close()
                listener.join()
                connections.forEach { it.close() }
            }
        }
    }

    private fun setState(state: AppUiState) {
        ReflectionHelpers.getField<MutableStateFlow<AppUiState>>(model, "_state").value = state
    }

    private fun refresh(
        current: AppUiState,
        snapshot: List<HostProfile>,
        keys: Map<String, PresentedHostKey?>,
        final: Boolean = true,
    ): AppUiState {
        val resultClass = Class.forName("dev.multiprompt.companion.MainViewModel\$HostRefresh")
        val constructor = resultClass.getDeclaredConstructor(
            String::class.java, List::class.java, Map::class.java, String::class.java, PresentedHostKey::class.java,
        ).apply { isAccessible = true }
        val results = keys.mapValues { (hostId, key) ->
            constructor.newInstance(hostId, emptyList<Any>(), emptyMap<String, Any>(), if (key == null) null else error, key)
        }
        val apply = MainViewModel::class.java.getDeclaredMethod(
            "applyRefresh", AppUiState::class.java, List::class.java, Map::class.java, Map::class.java,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        return apply.invoke(model, current, snapshot, emptyMap<String, Any>(), results, final) as AppUiState
    }
}

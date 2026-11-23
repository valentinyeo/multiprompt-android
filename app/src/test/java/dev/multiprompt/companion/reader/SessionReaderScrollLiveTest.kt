package dev.multiprompt.companion.reader

import android.app.Application
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.security.SecretStore
import dev.multiprompt.companion.ssh.SshRepository
import dev.multiprompt.companion.ssh.TmuxAction
import dev.multiprompt.companion.ssh.TmuxCommands
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class SessionReaderScrollLiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private val host = HostProfile(id = "host", label = "invalid", hostname = "", port = 0,
        username = "user", keySecretId = "missing")

    private fun reader() = SessionReaderConnection(
        SshRepository(SecretStore(RuntimeEnvironment.getApplication())), host, "live-abc",
        historyScript = byteArrayOf(), historyCache = ChatHistoryCache(temporary.newFolder()),
    )

    @Test fun jumpToNewestOnANormalPaneIssuesNoSendKeysCommand() {
        assertNull(TmuxCommands.remoteScrollLive("work", alternateOn = false))
    }

    @Test fun jumpToNewestOnAlternateScreenSendsEnd() {
        val command = TmuxCommands.remoteScrollLive("work", alternateOn = true)
        assertEquals(TmuxCommands.action("work", TmuxAction.SCROLL_BOTTOM), command)
    }

    @Test fun scrollLiveOnANormalPaneSendsNoRemoteAction() = runBlocking {
        reader().use { connection ->
            assertEquals(false, connection.state.value.alternateOn)
            connection.scrollLive()
            delay(200)
            assertEquals(0, connection.state.value.completedActions)
            assertEquals(0, connection.state.value.failedActions)
        }
    }
}
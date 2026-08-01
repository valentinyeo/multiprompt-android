package dev.multiprompt.companion.terminal

import androidx.compose.ui.graphics.Color
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.ssh.SshRepository
import dev.multiprompt.companion.ssh.TmuxParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.connectbot.sshlib.SshClient
import org.connectbot.sshlib.SshSession
import org.connectbot.terminal.TerminalDimensions
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory

sealed interface TerminalStatus {
    data object Connecting : TerminalStatus
    data object Connected : TerminalStatus
    data object Closed : TerminalStatus
    data class Failed(val message: String) : TerminalStatus
}

class TerminalConnection(
    private val repository: SshRepository,
    private val host: HostProfile,
    val tmuxSessionName: String,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val keyboard = Channel<ByteArray>(Channel.UNLIMITED)
    private val resizes = MutableStateFlow(TerminalDimensions(rows = 24, columns = 80))
    private val _status = MutableStateFlow<TerminalStatus>(TerminalStatus.Connecting)
    val status: StateFlow<TerminalStatus> = _status.asStateFlow()

    private var client: SshClient? = null
    private var session: SshSession? = null
    private var connectionJob: Job? = null

    val emulator: TerminalEmulator = TerminalEmulatorFactory.create(
        initialRows = 24,
        initialCols = 80,
        defaultForeground = Color(0xFFE5E7EB),
        defaultBackground = Color(0xFF090B10),
        onKeyboardInput = { data -> keyboard.trySend(data) },
        onResize = { dimensions -> resizes.value = dimensions },
        autoDetectUrls = true,
    )

    fun start() {
        if (connectionJob != null) return
        connectionJob = scope.launch {
            try {
                val connectedClient = repository.connect(host)
                client = connectedClient
                val connectedSession = connectedClient.openSession()
                    ?: error("The SSH server refused a terminal channel")
                session = connectedSession

                val dimensions = resizes.value
                check(
                    connectedSession.requestPty(
                        terminalType = "xterm-256color",
                        widthChars = dimensions.columns,
                        heightRows = dimensions.rows,
                    ),
                ) { "The SSH server refused a PTY" }

                val target = TmuxParser.shellQuote(tmuxSessionName)
                // ignore-size keeps this client out of tmux's size calculation, so attaching
                // from the phone never resizes the window the desktop is using. Setting
                // window-size on the session did the opposite: it stuck, and left the desktop
                // looking at a window sized for a phone.
                val command = "exec tmux attach-session -f ignore-size -t $target"
                check(connectedSession.requestExec(command)) { "tmux attach was refused" }
                _status.value = TerminalStatus.Connected

                launch {
                    for (data in keyboard) connectedSession.write(data)
                }
                launch {
                    resizes.collectLatest { next ->
                        connectedSession.resizeTerminal(
                            widthChars = next.columns,
                            heightRows = next.rows,
                            widthPixels = 0,
                            heightPixels = 0,
                        )
                    }
                }
                launch {
                    for (data in connectedSession.stderr) emulator.writeInput(data)
                }
                for (data in connectedSession.stdout) emulator.writeInput(data)
                _status.value = TerminalStatus.Closed
            } catch (throwable: Throwable) {
                if (_status.value != TerminalStatus.Closed) {
                    _status.value = TerminalStatus.Failed(
                        throwable.message ?: throwable::class.java.simpleName,
                    )
                }
            } finally {
                cleanupTransport()
            }
        }
    }

    fun paste(text: String) {
        if (text.isNotEmpty()) keyboard.trySend(text.toByteArray(Charsets.UTF_8))
    }

    override fun close() {
        _status.value = TerminalStatus.Closed
        keyboard.close()
        connectionJob?.cancel()
        CoroutineScope(Dispatchers.IO).launch { cleanupTransport() }
        scope.cancel()
    }

    private suspend fun cleanupTransport() {
        val oldSession = session
        val oldClient = client
        session = null
        client = null
        runCatching { oldSession?.close() }
        runCatching { oldClient?.disconnect() }
    }
}

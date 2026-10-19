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
import kotlinx.coroutines.withContext
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
    windowColumns: Int = 0,
    windowRows: Int = 0,
) : AutoCloseable {
    private val fixedSize = if (windowColumns > 0 && windowRows > 0) {
        TerminalDimensions(rows = windowRows, columns = windowColumns)
    } else {
        null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val keyboard = Channel<ByteArray>(Channel.UNLIMITED)
    private val resizes = MutableStateFlow(fixedSize ?: TerminalDimensions(rows = 24, columns = 80))
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
        onResize = { dimensions -> if (fixedSize == null) resizes.value = dimensions },
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
                val command = "exec tmux attach-session -f ignore-size -t $target"
                check(connectedSession.requestExec(command)) { "tmux attach was refused" }
                _status.value = TerminalStatus.Connected

                launch {
                    for (data in keyboard) connectedSession.write(data)
                }
                if (fixedSize == null) {
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

    suspend fun history(): List<String> = withContext(Dispatchers.IO) {
        val historyClient = repository.connect(host)
        try {
            shellHistoryLines(repository.captureSession(historyClient, tmuxSessionName))
        } finally {
            historyClient.disconnect()
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

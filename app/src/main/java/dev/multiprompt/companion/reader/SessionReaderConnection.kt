package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.ssh.SshRepository
import dev.multiprompt.companion.ssh.TmuxAction
import dev.multiprompt.companion.ssh.TmuxText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.connectbot.sshlib.SshClient

sealed interface ReaderStatus {
    data object Connecting : ReaderStatus
    data object Live : ReaderStatus
    data object Closed : ReaderStatus
    data class Failed(val message: String) : ReaderStatus
}

data class ReaderState(
    val output: String = "",
    val runtimeDetails: TmuxText.RuntimeDetails = TmuxText.RuntimeDetails(),
    val modelPickerOptions: List<TmuxText.ModelPickerOption> = emptyList(),
    val status: ReaderStatus = ReaderStatus.Connecting,
    val sending: Boolean = false,
    val actionError: String? = null,
    val lastUpdatedAtMillis: Long = 0,
    val completedActions: Long = 0,
)

class SessionReaderConnection(
    private val repository: SshRepository,
    private val host: HostProfile,
    val tmuxSessionName: String,
    private val agent: AgentKind = AgentKind.OTHER,
) : AutoCloseable {
    private sealed interface Request {
        data class Prompt(val text: String) : Request
        data class Action(val action: TmuxAction) : Request
        data class ModelPickerOption(val index: Int) : Request
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private var streamClient: SshClient? = null
    private var streamJob: Job? = null
    private var actionJob: Job? = null

    fun start() {
        if (actionJob == null) {
            actionJob = scope.launch {
                for (request in requests) runAction(request)
            }
        }
        startStream()
    }

    fun sendPrompt(text: String): Boolean = requests.trySend(Request.Prompt(text)).isSuccess

    fun sendEnter() {
        requests.trySend(Request.Action(TmuxAction.ENTER))
    }

    fun interrupt() {
        requests.trySend(Request.Action(TmuxAction.INTERRUPT))
    }

    fun selectModelPickerOption(index: Int): Boolean =
        requests.trySend(Request.ModelPickerOption(index)).isSuccess

    private fun startStream() {
        if (streamJob?.isActive == true) return
        streamJob = scope.launch {
            while (isActive) {
                _state.update { it.copy(status = ReaderStatus.Connecting) }
                try {
                    val connectedClient = withTimeout(CONNECT_TIMEOUT_MS) {
                        repository.connect(host)
                    }
                    streamClient = connectedClient
                    repository.streamSession(connectedClient, tmuxSessionName, agent) { snapshot, details, pickerOptions ->
                        _state.update { current ->
                            val liveDetails = if (details.model != null) {
                                details
                            } else {
                                current.runtimeDetails
                            }
                            // The agent's screen is all tmux can give for an alternate-screen
                            // TUI, so the transcript above it is grown here, screen by screen.
                            val merged = TmuxText.mergeSnapshot(current.output, snapshot)
                            current.copy(
                                output = merged,
                                runtimeDetails = liveDetails,
                                modelPickerOptions = pickerOptions,
                                status = ReaderStatus.Live,
                                lastUpdatedAtMillis = System.currentTimeMillis(),
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    _state.update {
                        it.copy(
                            status = ReaderStatus.Failed(
                                throwable.message ?: throwable::class.java.simpleName,
                            ),
                        )
                    }
                } finally {
                    val oldClient = streamClient
                    streamClient = null
                    runCatching { oldClient?.disconnect() }
                }
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    private suspend fun runAction(request: Request) {
        _state.update { it.copy(sending = true, actionError = null) }
        try {
            withTimeout(REQUEST_TIMEOUT_MS) {
                withFreshClient { client ->
                    when (request) {
                        is Request.Prompt -> repository.sendPrompt(
                            client,
                            tmuxSessionName,
                            request.text,
                        )
                        is Request.Action -> repository.performAction(
                            client,
                            tmuxSessionName,
                            request.action,
                        )
                        is Request.ModelPickerOption -> repository.selectModelPickerOption(
                            client,
                            tmuxSessionName,
                            request.index,
                        )
                    }
                }
            }
            _state.update { it.copy(completedActions = it.completedActions + 1) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            _state.update {
                it.copy(actionError = throwable.message ?: throwable::class.java.simpleName)
            }
        } finally {
            _state.update { it.copy(sending = false) }
        }
    }

    private suspend fun <T> withFreshClient(block: suspend (SshClient) -> T): T {
        val client = repository.connect(host)
        return try {
            block(client)
        } finally {
            runCatching { client.disconnect() }
        }
    }

    override fun close() {
        _state.update { it.copy(status = ReaderStatus.Closed) }
        requests.close()
        streamJob?.cancel()
        actionJob?.cancel()
        val oldClient = streamClient
        streamClient = null
        CoroutineScope(Dispatchers.IO).launch { runCatching { oldClient?.disconnect() } }
        scope.cancel()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val REQUEST_TIMEOUT_MS = 20_000L
        const val RECONNECT_DELAY_MS = 3_000L
    }
}

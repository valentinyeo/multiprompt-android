package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.ssh.SshRepository
import dev.multiprompt.companion.ssh.TmuxAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.connectbot.sshlib.SshClient

sealed interface ReaderStatus {
    data object Connecting : ReaderStatus
    data object Ready : ReaderStatus
    data object Working : ReaderStatus
    data object Closed : ReaderStatus
    data class Failed(val message: String) : ReaderStatus
}

data class ReaderState(
    val output: String = "",
    val status: ReaderStatus = ReaderStatus.Connecting,
)

class SessionReaderConnection(
    private val repository: SshRepository,
    private val host: HostProfile,
    val tmuxSessionName: String,
) : AutoCloseable {
    private sealed interface Request {
        data object Refresh : Request
        data class Prompt(val text: String) : Request
        data class Action(val action: TmuxAction) : Request
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(ReaderState())
    val state: kotlinx.coroutines.flow.StateFlow<ReaderState> = _state

    private var client: SshClient? = null
    private var worker: Job? = null

    fun start() {
        if (worker != null) return
        worker = scope.launch {
            requests.send(Request.Refresh)
            for (request in requests) {
                _state.value = _state.value.copy(status = ReaderStatus.Working)
                try {
                    withTimeout(REQUEST_TIMEOUT_MS) {
                        when (request) {
                            Request.Refresh -> refreshOutput()
                            is Request.Prompt -> {
                                withClient(retry = false) {
                                    repository.sendPrompt(it, tmuxSessionName, request.text)
                                }
                                refreshOutput()
                            }
                            is Request.Action -> {
                                withClient(retry = false) {
                                    repository.performAction(it, tmuxSessionName, request.action)
                                }
                                refreshOutput()
                            }
                        }
                    }
                    _state.value = _state.value.copy(status = ReaderStatus.Ready)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    _state.value = _state.value.copy(
                        status = ReaderStatus.Failed(
                            throwable.message ?: throwable::class.java.simpleName,
                        ),
                    )
                }
            }
        }
    }

    fun refresh() {
        requests.trySend(Request.Refresh)
    }

    fun sendPrompt(text: String) {
        requests.trySend(Request.Prompt(text))
    }

    fun sendEnter() {
        requests.trySend(Request.Action(TmuxAction.ENTER))
    }

    fun interrupt() {
        requests.trySend(Request.Action(TmuxAction.INTERRUPT))
    }

    private suspend fun refreshOutput() {
        val output = withClient { repository.captureSession(it, tmuxSessionName) }
        _state.value = _state.value.copy(output = output)
    }

    private suspend fun <T> withClient(
        retry: Boolean = true,
        block: suspend (SshClient) -> T,
    ): T {
        val first = client ?: repository.connect(host).also { client = it }
        return try {
            block(first)
        } catch (firstFailure: Throwable) {
            runCatching { first.disconnect() }
            client = null
            if (firstFailure is CancellationException) throw firstFailure
            if (!retry) throw firstFailure
            val replacement = repository.connect(host).also { client = it }
            block(replacement)
        }
    }

    override fun close() {
        _state.value = _state.value.copy(status = ReaderStatus.Closed)
        requests.close()
        worker?.cancel()
        val oldClient = client
        client = null
        CoroutineScope(Dispatchers.IO).launch { runCatching { oldClient?.disconnect() } }
        scope.cancel()
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 20_000L
    }
}

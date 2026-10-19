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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val screenOutput: String = "",
    val runtimeDetails: TmuxText.RuntimeDetails = TmuxText.RuntimeDetails(),
    val modelPickerOptions: List<TmuxText.ModelPickerOption> = emptyList(),
    val status: ReaderStatus = ReaderStatus.Connecting,
    val sending: Boolean = false,
    val actionError: String? = null,
    val lastUpdatedAtMillis: Long = 0,
    val completedActions: Long = 0,
    val waitingForInput: Boolean = false,
    val lastFailure: String? = null,
    val eta: TmuxText.SessionEta? = null,
    val hookState: TmuxText.HookState? = null,
    val sentPrompts: List<String> = emptyList(),
    val sentRecord: List<QueuedMessages.SentPrompt> = emptyList(),
    val alternateOn: Boolean = false,
    val transcript: List<TranscriptMessage>? = null,
)

internal fun transcriptAfterUpdate(
    current: List<TranscriptMessage>?,
    response: TranscriptProtocol.Response?,
): List<TranscriptMessage>? = when (response?.status) {
    null, "same" -> current
    "ok" -> response.messages
    else -> null
}

class SessionReaderConnection(
    private val repository: SshRepository,
    private val host: HostProfile,
    val tmuxSessionName: String,
    private val agent: AgentKind = AgentKind.OTHER,
    private val historyScript: ByteArray,
    private val historyCache: ChatHistoryCache,
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

    private var historyConnection: ChatHistoryConnection? = null
    private var historyIdentity: ChatHistoryProtocol.Identity? = null

    fun chatHistory(agent: AgentKind, cwd: String): ChatHistoryConnection {
        require(TranscriptProtocol.supports(agent))
        val identity = ChatHistoryProtocol.Identity(agent, tmuxSessionName, cwd)
        if (historyIdentity == identity) return requireNotNull(historyConnection)
        historyConnection?.close()
        return ChatHistoryConnection(repository, host, identity, historyScript, historyCache)
            .also {
                historyIdentity = identity
                historyConnection = it
                it.start()
            }
    }

    fun start() {
        if (actionJob == null) {
            actionJob = scope.launch {
                for (request in requests) runAction(request)
            }
        }
        startStream()
    }

    fun sendPrompt(text: String): Boolean {
        val sent = requests.trySend(Request.Prompt(text)).isSuccess
        if (sent) {
            val prompt = QueuedMessages.SentPrompt(text, System.currentTimeMillis())
            awaitModelSwitchConfirmation(text)
            _state.update { current ->
                current.copy(
                    output = TmuxText.mergeSnapshot(current.output, "> $text"),
                    sentPrompts = (current.sentPrompts + text).takeLast(SENT_PROMPT_MEMORY),
                    sentRecord = (current.sentRecord + prompt).takeLast(8),
                )
            }
        }
        return sent
    }

    fun sendEnter() {
        requests.trySend(Request.Action(TmuxAction.ENTER))
    }

    fun interrupt() {
        requests.trySend(Request.Action(TmuxAction.INTERRUPT))
    }

    fun scrollBackPage() {
        requests.trySend(Request.Action(TmuxAction.SCROLL_UP))
    }

    @Volatile
    private var modelSwitchConfirmDeadline = 0L

    @Volatile
    private var modelSwitchConfirmed = true

    private fun awaitModelSwitchConfirmation(command: String) {
        val trimmed = command.trimStart()
        if (!trimmed.startsWith("/model") && !trimmed.startsWith("/effort")) return
        modelSwitchConfirmed = false
        modelSwitchConfirmDeadline = System.currentTimeMillis() + MODEL_CONFIRM_TIMEOUT_MS
    }

    private fun confirmModelSwitchIfAsked(visible: Boolean) {
        if (modelSwitchConfirmed || !visible) return
        if (System.currentTimeMillis() > modelSwitchConfirmDeadline) {
            modelSwitchConfirmed = true
            return
        }
        modelSwitchConfirmed = true
        requests.trySend(Request.Action(TmuxAction.ENTER))
    }

    fun scrollLive() {
        requests.trySend(Request.Action(TmuxAction.SCROLL_BOTTOM))
    }

    private val _historyPage = MutableStateFlow<String?>(null)
    private val historyMutex = Mutex(false)

    @Volatile
    private var historyMode = false

    private val _historyPages = MutableStateFlow<List<String>>(emptyList())

    private var transcriptHistoryDepth = 0

    val historyPages: StateFlow<List<String>> = _historyPages

    suspend fun enterHistory() = historyMutex.withLock {
        if (!historyMode) {
            historyMode = true
            _historyPages.value = emptyList()
            scrollBackPage()
            delay(1200)
            _historyPage.value?.let { page -> _historyPages.value = listOf(page) }
        }
    }

    suspend fun loadOlderHistoryPage(): Boolean = historyMutex.withLock {
        if (!historyMode) return@withLock false
        val before = _historyPages.value.lastOrNull()
        scrollBackPage()
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            val page = _historyPage.value
            if (page != null && page != before && page !in _historyPages.value) {
                _historyPages.value = _historyPages.value + page
                return@withLock true
            }
            delay(150)
        }
        false
    }

    suspend fun exitHistory() = historyMutex.withLock {
        historyMode = false
        _historyPages.value = emptyList()
        _historyPage.value = null
        scrollLive()
    }

    suspend fun loadOlderIntoTranscript(): Boolean = historyMutex.withLock {
        if (TranscriptProtocol.supports(agent) || historyMode) return@withLock false
        if (!_state.value.alternateOn) return@withLock false
        val targetDepth = transcriptHistoryDepth + 1
        historyMode = true
        _historyPage.value = null
        repeat(targetDepth) {
            runCatching { pageBackOnce() }
            delay(HISTORY_PAGE_STEP_MS)
        }
        val page = awaitStableHistoryPage()
        scrollLiveDirect()
        delay(HISTORY_RETURN_MS)
        historyMode = false
        _historyPage.value = null
        if (page == null) return@withLock false
        val older = page.trimEnd()
        if (_state.value.output.contains(older)) return@withLock false
        transcriptHistoryDepth = targetDepth
        _state.update { it.copy(output = older + "\n" + it.output) }
        true
    }

    private suspend fun pageBackOnce() = withFreshClient { client ->
        repository.performAction(client, tmuxSessionName, TmuxAction.SCROLL_UP)
    }

    private suspend fun scrollLiveDirect() {
        runCatching {
            withFreshClient { client ->
                repository.performAction(client, tmuxSessionName, TmuxAction.SCROLL_BOTTOM)
            }
        }
    }

    private suspend fun awaitStableHistoryPage(): String? {
        var previous: String? = null
        var stableReads = 0
        val deadline = System.currentTimeMillis() + HISTORY_PAGE_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val candidate = _historyPage.value?.takeIf(String::isNotBlank)
            if (candidate != null && candidate == previous) {
                stableReads++
                if (stableReads >= HISTORY_PAGE_STABLE_READS) return candidate
            } else {
                stableReads = 0
                previous = candidate
            }
            delay(HISTORY_PAGE_READ_MS)
        }
        return previous
    }

    fun selectModelPickerOption(index: Int): Boolean {
        awaitModelSwitchConfirmation("/model")
        return requests.trySend(Request.ModelPickerOption(index)).isSuccess
    }

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
                    repository.streamSession(
                        connectedClient,
                        tmuxSessionName,
                        agent,
                    ) { frame ->
                        confirmModelSwitchIfAsked(frame.confirmationVisible)
                        if (historyMode) {
                            _historyPage.value = frame.output
                            _state.update { current ->
                                current.copy(
                                    status = ReaderStatus.Live,
                                    alternateOn = frame.alternateOn,
                                )
                            }
                            return@streamSession
                        }
                        _state.update { current ->
                            val liveDetails = if (frame.details.model != null) {
                                frame.details
                            } else {
                                current.runtimeDetails
                            }
                            val merged = TmuxText.mergeSnapshot(current.output, frame.output)
                            current.copy(
                                output = merged,
                                screenOutput = frame.screenOutput,
                                runtimeDetails = liveDetails,
                                modelPickerOptions = frame.pickerOptions,
                                status = ReaderStatus.Live,
                                lastFailure = null,
                                lastUpdatedAtMillis = System.currentTimeMillis(),
                                waitingForInput = frame.waitingForInput,
                                alternateOn = frame.alternateOn,
                                eta = frame.eta?.toEta(System.currentTimeMillis()),
                                hookState = frame.hookState,
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    val message = throwable.message ?: throwable::class.java.simpleName
                    _state.update {
                        it.copy(status = ReaderStatus.Failed(message), lastFailure = message)
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
        historyConnection?.close()
        val oldClient = streamClient
        streamClient = null
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { oldClient?.disconnect() }
        }
        scope.cancel()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val REQUEST_TIMEOUT_MS = 20_000L
        const val RECONNECT_DELAY_MS = 3_000L
        const val SENT_PROMPT_MEMORY = 3
        const val MODEL_CONFIRM_TIMEOUT_MS = 25_000L
        const val HISTORY_PAGE_STEP_MS = 350L
        const val HISTORY_PAGE_READ_MS = 500L
        const val HISTORY_PAGE_STABLE_READS = 3
        const val HISTORY_PAGE_WAIT_MS = 4_000L
        const val HISTORY_RETURN_MS = 1_200L
    }
}

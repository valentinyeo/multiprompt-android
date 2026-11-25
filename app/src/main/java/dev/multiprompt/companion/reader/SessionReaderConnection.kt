package dev.multiprompt.companion.reader

import dev.multiprompt.companion.computer.ComputerTarget
import dev.multiprompt.companion.computer.ComputerTunnel
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.skills.NativeSkill
import dev.multiprompt.companion.skills.SkillsLoader
import dev.multiprompt.companion.ssh.SshRepository
import dev.multiprompt.companion.ssh.TmuxAction
import dev.multiprompt.companion.ssh.TmuxText
import dev.multiprompt.companion.upload.DeviceFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.connectbot.sshlib.SshClient
import java.util.concurrent.atomic.AtomicBoolean

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
    val nativeSkills: List<dev.multiprompt.companion.skills.NativeSkill> = emptyList(),
    val skillsLoading: Boolean = false,
    val skillsError: String? = null,
    val status: ReaderStatus = ReaderStatus.Connecting,
    val sending: Boolean = false,
    val working: Boolean = false,
    val actionError: String? = null,
    val lastUpdatedAtMillis: Long = 0,
    val completedActions: Long = 0,
    val failedActions: Long = 0,
    val waitingForInput: Boolean = false,
    val lastFailure: String? = null,
    val eta: TmuxText.SessionEta? = null,
    val hookState: TmuxText.HookState? = null,
    val sentPrompts: List<String> = emptyList(),
    val sentRecord: List<QueuedMessages.SentPrompt> = emptyList(),
    val alternateOn: Boolean = false,
    val transcript: List<TranscriptMessage>? = null,
    val aiRestartRequestedAtMillis: Long = 0,
    val pendingModelFamily: String? = null,
    val modelSwitchFailure: String? = null,
)

internal fun transcriptAfterUpdate(
    current: List<TranscriptMessage>?,
    response: TranscriptProtocol.Response?,
): List<TranscriptMessage>? = when (response?.status) {
    null, "same" -> current
    "ok" -> response.messages
    else -> null
}

internal fun nextPaneState(
    current: ReaderState,
    frame: SshRepository.StreamSnapshot,
    agent: AgentKind,
    settings: HostSettings?,
    nowMillis: Long,
): ReaderState? {
    if (current.status is ReaderStatus.Live &&
        current.lastFailure == null &&
        current.pendingModelFamily == null &&
        current.screenOutput == frame.screenOutput &&
        current.alternateOn == frame.alternateOn &&
        current.hookState == frame.hookState &&
        frame.eta == null
    ) {
        return null
    }
    val merged = TmuxText.mergeSnapshot(current.output, frame.output)
    val parsed = TmuxText.runtimeDetails(merged)
    val liveDetails = when {
        frame.details.model != null -> frame.details
        parsed.model != null -> parsed
        else -> current.runtimeDetails
    }
    val resolvedFamily = HostSettings.family(liveDetails.model)
        ?: HostSettings.family(settings?.model?.takeIf { it.isNotEmpty() })
    val clearedPending = current.pendingModelFamily != null && resolvedFamily == current.pendingModelFamily
    return current.copy(
        output = merged,
        screenOutput = frame.screenOutput,
        runtimeDetails = liveDetails,
        modelPickerOptions = frame.pickerOptions,
        status = ReaderStatus.Live,
        lastFailure = null,
        lastUpdatedAtMillis = nowMillis,
        waitingForInput = frame.waitingForInput,
        alternateOn = frame.alternateOn,
        eta = frame.eta?.toEta(nowMillis),
        hookState = frame.hookState,
        working = frame.hookState?.active ?: paneWorking(frame.screenOutput, agent),
        pendingModelFamily = if (clearedPending) null else current.pendingModelFamily,
        modelSwitchFailure = if (clearedPending) null else current.modelSwitchFailure,
    )
}

internal fun unconfirmedAfterResync(history: ChatHistory, sent: List<QueuedMessages.SentPrompt>): List<QueuedMessages.SentPrompt> =
    sent.filter { record ->
        record.messageId.isEmpty() || (record.sid.isNotEmpty() && record.sid != history.sid) ||
            history.deliveryState(record) !in setOf(QueuedMessages.Delivery.DELIVERED, QueuedMessages.Delivery.CANCELLED)
    }

sealed interface PromptAdmission {
    data class Recorded(val messageId: String) : PromptAdmission
    data object LegacyEnqueued : PromptAdmission
    data class WaitingForConnection(val id: String) : PromptAdmission
    data class Rejected(val reason: String) : PromptAdmission
}

class SessionReaderConnection(
    private val repository: SshRepository,
    private val host: HostProfile,
    val tmuxSessionName: String,
    val agent: AgentKind = AgentKind.OTHER,
    private val historyScript: ByteArray,
    private val historyCache: ChatHistoryCache,
    private val messageJournal: MessageJournal? = null,
    private val messageGate: MessageGate? = null,
    private val hostSessionId: String = "",
) : AutoCloseable {
    private sealed interface Request {
        data class Prompt(val record: QueuedMessages.SentPrompt) : Request
        data class RuntimeCommand(val text: String) : Request
        data class Cancel(val record: QueuedMessages.SentPrompt) : Request
        data class Action(val action: TmuxAction) : Request
        data class RestartAi(val sid: String, val cwd: String) : Request
        data class FreshStart(val provider: String, val sid: String) : Request
        data class ModelPickerOption(val index: Int) : Request
        data class QuestionAnswer(val question: PendingQuestion, val choices: Set<Int>, val other: String) : Request
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val streamNudge = Channel<Unit>(Channel.CONFLATED)
    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private var streamClient: SshClient? = null
    private var streamJob: Job? = null
    private var actionJob: Job? = null
    private val skillsRunning = AtomicBoolean(false)
    private val piModelsRunning = AtomicBoolean(false)
    private val _piModels = MutableStateFlow(PiModelList())
    val piModels: StateFlow<PiModelList> = _piModels.asStateFlow()

    private var historyConnection: ChatHistoryConnection? = null
    private var pendingPromptJob: Job? = null
    private val histories = MutableStateFlow<ChatHistoryConnection?>(null)
    val pendingPrompts = PendingPrompts()
    private var historyIdentity: ChatHistoryProtocol.Identity? = null
    private val pollGate = ChatPollGate()
    private val paneGate = ChatPollGate()

    fun matches(host: HostProfile, agent: AgentKind, hostSessionId: String): Boolean =
        this.host == host && this.agent == agent && this.hostSessionId == hostSessionId

    val sharedMessages: Boolean get() = ChatHistoryProtocol.messageProvider(agent, hostSessionId) != null

    suspend fun openComputerTunnel(target: ComputerTarget): ComputerTunnel? {
        val client = streamClient ?: return null
        val forwarder = client.localPortForward(0, target.host, target.port) ?: return null
        return object : ComputerTunnel {
            override val localPort: Int = forwarder.boundPort
            override fun close() {
                runCatching { forwarder.close() }
            }
        }
    }

    fun observe(pane: Boolean = true): AutoCloseable {
        val paneLease = if (pane) paneGate.acquire() else null
        val lease = pollGate.acquire()
        return AutoCloseable {
            lease.close()
            paneLease?.close()
        }
    }

    fun nudge() {
        if (ReconnectBackoff.shouldNudge(_state.value.status is ReaderStatus.Failed)) streamNudge.trySend(Unit)
        historyConnection?.takeIf { pollGate.active.value && it.state.value.retrying }?.nudge()
    }

    fun hostSettings(): HostSettings? = historyConnection?.state?.value?.replica?.host?.settings

    suspend fun changeHost(change: HostChange): MessageActionResult =
        historyConnection?.changeHost(change) ?: MessageActionResult.Rejected("Chat is not ready")

    fun interruptHost(): Boolean = historyConnection?.interruptHost { requests.trySend(Request.Action(TmuxAction.ESCAPE)) } == true

    fun chatHistory(agent: AgentKind, cwd: String): ChatHistoryConnection {
        require(ChatHistoryProtocol.supports(agent, hostSessionId))
        val identity = ChatHistoryProtocol.Identity(agent, tmuxSessionName, cwd, hostSessionId)
        if (historyIdentity == identity) return requireNotNull(historyConnection)
        historyConnection?.close()
        return ChatHistoryConnection(repository, host, identity, historyScript, historyCache, messageJournal, messageGate, sharedClient = { streamClient }, pollGate = pollGate)
            .also {
                historyIdentity = identity
                historyConnection = it
                histories.value = it
                it.start()
            }
    }

    suspend fun resyncChat(): Boolean {
        val connection = historyConnection ?: return false
        try {
            connection.resync().await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
        val history = connection.state.value.history
        var confirmed = false
        _state.update { current ->
            val remaining = unconfirmedAfterResync(history, current.sentRecord)
            confirmed = history.held.none { held ->
                val record = current.sentRecord.firstOrNull { it.messageId == held.messageId && (it.sid.isEmpty() || it.sid == history.sid) }
                history.deliveryState(record ?: held) !in setOf(QueuedMessages.Delivery.DELIVERED, QueuedMessages.Delivery.CANCELLED)
            } && remaining.isEmpty()
            current
        }
        return confirmed
    }

    fun resetConversation(): kotlinx.coroutines.Deferred<MessageActionResult> =
        historyConnection?.resetConversation() ?: kotlinx.coroutines.CompletableDeferred(MessageActionResult.Rejected("Chat is not ready"))

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        if (pendingPromptJob == null) {
            pendingPromptJob = scope.launch {
                combine(histories.flatMapLatest { it?.state ?: flowOf(ChatHistoryState()) }, pendingPrompts.state, pollGate.active) { history, pending, selected ->
                    selected && history.ready && pending.any { !it.issuing && it.error == null }
                }.collect { ready ->
                    if (ready) while (true) {
                        val history = historyConnection ?: break
                        val entry = pendingPrompts.next(pollGate.active.value && history.state.value.ready) ?: break
                        val admission = history.submit(entry.text, messageId = entry.id)
                        pendingPrompts.complete(entry.id, admission)
                        if (admission is PromptAdmission.Rejected) {
                            if (PendingPrompts.retryable(admission.reason)) delay(1_000L)
                            else _state.update { it.copy(actionError = admission.reason) }
                        }
                    }
                }
            }
        }
        if (actionJob == null) {
            actionJob = scope.launch {
                for (request in requests) runAction(request)
            }
        }
        startStream()
    }

    fun loadSkills(script: ByteArray, workingDirectory: String = historyIdentity?.cwd.orEmpty()) {
        loadSkills { repository.nativeSkills(host, tmuxSessionName, script, workingDirectory) }
    }

    internal fun loadSkills(
        retryDelayMillis: Long = SkillsLoader.RETRY_DELAY_MILLIS,
        fetch: suspend () -> List<NativeSkill>,
    ) {
        if (agent != AgentKind.CLAUDE || !skillsRunning.compareAndSet(false, true)) return
        _state.update { it.copy(skillsLoading = true, skillsError = null) }
        scope.launch {
            val outcome = try {
                SkillsLoader.load(retryDelayMillis, fetch)
            } finally {
                skillsRunning.set(false)
            }
            when (outcome) {
                is SkillsLoader.Outcome.Loaded ->
                    _state.update { it.copy(nativeSkills = outcome.skills, skillsLoading = false) }
                is SkillsLoader.Outcome.Failed ->
                    _state.update { it.copy(skillsLoading = false, skillsError = outcome.message) }
            }
        }
    }

    fun loadPiModels() {
        loadPiModels { repository.piModels(host) }
    }

    internal fun loadPiModels(fetch: suspend () -> List<PiModel>) {
        if (agent != AgentKind.PI || !piModelsRunning.compareAndSet(false, true)) return
        _piModels.update { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                val models = fetch()
                _piModels.value = PiModelList(models, error = if (models.isEmpty()) PiModels.EMPTY else null)
            } catch (_: TimeoutCancellationException) {
                _piModels.update { it.copy(loading = false, error = PiModels.FAILED) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _piModels.update { it.copy(loading = false, error = PiModels.FAILED) }
            } finally {
                piModelsRunning.set(false)
            }
        }
    }

    suspend fun changePiModel(model: PiModel): MessageActionResult =
        historyConnection?.changePiModel(model) ?: MessageActionResult.Rejected("Chat is not ready")

    suspend fun sendPrompt(text: String): PromptAdmission {
        if (sharedMessages) {
            val history = historyConnection ?: return PromptAdmission.Rejected("Chat is not ready")
            if (!pollGate.active.value) return PromptAdmission.Rejected("Chat is no longer selected")
            if (text.isBlank() || '\u0000' in text) return PromptAdmission.Rejected("Message is empty or invalid")
            pendingPrompts.state.value.firstOrNull { MessageIdentity.same(it.identityText, MessageIdentity.text(text)) && it.error == null }?.let {
                return PromptAdmission.WaitingForConnection(it.id)
            }
            history.state.value.messageError?.let { return PromptAdmission.Rejected(it) }
            val admission = if (history.state.value.ready) history.submit(text) else PromptAdmission.Rejected("Chat is disconnected")
            if (admission is PromptAdmission.Rejected && PendingPrompts.retryable(admission.reason)) {
                val entry = pendingPrompts.enqueue(text)
                if (history.state.value.retrying) history.nudge()
                return PromptAdmission.WaitingForConnection(entry.id)
            }
            return admission
        }
        val origin = historyConnection?.state?.value?.takeIf { it.loaded }?.history
        val prompt = QueuedMessages.SentPrompt(text, System.currentTimeMillis(),
            messageId = "",
            sid = origin?.sid.orEmpty(), inode = origin?.inode ?: 0, afterOffset = origin?.offset ?: 0)
        _state.update { it.copy(sentRecord = it.sentRecord + prompt) }
        val sent = requests.trySend(Request.Prompt(prompt)).isSuccess
        if (!sent) _state.update { it.copy(sentRecord = it.sentRecord.filterNot { record -> record === prompt }) }
        if (sent) {
            _state.update { current ->
                current.copy(
                    output = TmuxText.mergeSnapshot(current.output, "> $text"),
                    sentPrompts = (current.sentPrompts + text).takeLast(SENT_PROMPT_MEMORY),
                )
            }
        }
        return if (sent) PromptAdmission.LegacyEnqueued else PromptAdmission.Rejected("Could not send")
    }

    suspend fun editWaiting(messageId: String, text: String): QueuedEdit.Outcome =
        historyConnection?.editWaiting(messageId, text) ?: QueuedEdit.Outcome.TooLate

    fun sendRuntimeCommand(command: String): Boolean {
        val sent = requests.trySend(Request.RuntimeCommand(command)).isSuccess
        if (sent) {
            modelFamilyFromCommand(command)?.let(::markModelPick)
            awaitModelSwitchConfirmation(command)
        }
        return sent
    }

    fun markModelPick(family: String) {
        _state.update { it.copy(pendingModelFamily = family, modelSwitchFailure = null) }
    }

    fun markModelPickFailed(message: String) {
        _state.update { it.copy(modelSwitchFailure = message, pendingModelFamily = null) }
    }

    private fun modelFamilyFromCommand(command: String): String? {
        val trimmed = command.trim()
        if (!trimmed.startsWith("/model")) return null
        val tail = trimmed.removePrefix("/model").trim().lowercase()
        return tail.split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotBlank() }
    }

    fun cancelQueued(messageId: String, held: List<QueuedMessages.SentPrompt> = emptyList()): Boolean {
        val record = (_state.value.sentRecord + held).firstOrNull { it.messageId == messageId } ?: return false
        return requests.trySend(Request.Cancel(record)).isSuccess
    }

    fun sendEnter() {
        requests.trySend(Request.Action(TmuxAction.ENTER))
    }

    fun restartAi(cwd: String): Boolean {
        val sid = historyConnection?.state?.value?.history?.sid.orEmpty()
        val sent = requests.trySend(Request.RestartAi(sid, cwd)).isSuccess
        if (sent) _state.update { it.copy(aiRestartRequestedAtMillis = System.currentTimeMillis()) }
        return sent
    }

    fun freshStart(): Boolean {
        val provider = ChatHistoryProtocol.hostedProvider(agent, hostSessionId)
        if (!dev.multiprompt.companion.reader.FreshStart.supported(provider, hostSessionId)) return false
        return requests.trySend(Request.FreshStart(requireNotNull(provider), hostSessionId)).isSuccess
    }

    suspend fun switchAi(to: String): SwitchAi.Outcome {
        val provider = ChatHistoryProtocol.hostedProvider(agent, hostSessionId)
        if (!SwitchAi.supported(provider, hostSessionId) || to == provider || SwitchAi.TARGETS.none { it.provider == to }) {
            return SwitchAi.Outcome.Failed("Switch AI is not available for this chat")
        }
        return try {
            withTimeout(SWITCH_TIMEOUT_MS) {
                withFreshClient { client ->
                    repository.switchAi(client, historyScript, requireNotNull(provider), hostSessionId, to)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            SwitchAi.Outcome.Unknown
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            SwitchAi.Outcome.Failed(throwable.message ?: SwitchAi.NO_ANSWER)
        }
    }

    suspend fun restartNewest(): RestartNewest.Outcome {
        val provider = ChatHistoryProtocol.hostedProvider(agent, hostSessionId)
        if (!RestartNewest.supported(provider, hostSessionId)) {
            return RestartNewest.Outcome.Failed(RestartNewest.NOT_HOSTED)
        }
        return try {
            withTimeout(RESTART_NEWEST_TIMEOUT_MS) {
                withFreshClient { client ->
                    repository.restartNewest(client, historyScript, requireNotNull(provider), hostSessionId, tmuxSessionName)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            RestartNewest.Outcome.Failed(throwable.message ?: RestartNewest.NO_ANSWER)
        }
    }

    fun interrupt() {
        requests.trySend(Request.Action(TmuxAction.INTERRUPT))
    }

    fun scrollBackPage() {
        requests.trySend(Request.Action(TmuxAction.SCROLL_UP))
    }

    private val confirmation = ModelSwitchConfirmation({ System.currentTimeMillis() }, MODEL_CONFIRM_TIMEOUT_MS)

    private fun awaitModelSwitchConfirmation(command: String) {
        val trimmed = command.trimStart()
        if (!trimmed.startsWith("/model") && !trimmed.startsWith("/effort")) return
        confirmation.begin()
        scope.launch {
            delay(MODEL_CONFIRM_TIMEOUT_MS + 1)
            if (confirmation.expire()) {
                _state.update { current ->
                    current.copy(
                        modelSwitchFailure = "Model switch did not finish; try again",
                        pendingModelFamily = null,
                    )
                }
            }
        }
    }

    private fun confirmModelSwitchIfAsked(visible: Boolean) {
        if (confirmation.shouldPress(visible)) requests.trySend(Request.Action(TmuxAction.ENTER))
    }

    private suspend fun confirmModelSwitchWhilePaneHidden(command: String) {
        if (paneGate.active.value || !confirmation.pending.value) return
        val family = modelFamilyFromCommand(command)
        val effort = command.trim().takeIf { it.startsWith("/effort ") }?.substringAfter(" ")?.trim()?.lowercase()
        val deadline = System.currentTimeMillis() + MODEL_CONFIRM_STEP_MS * MODEL_CONFIRM_READS
        while (System.currentTimeMillis() < deadline) {
            delay(MODEL_CONFIRM_STEP_MS)
            val screen = runCatching { withServerLink { repository.captureSession(it, tmuxSessionName) } }.getOrNull() ?: return
            if (confirmation.shouldPress(TmuxText.isModelSwitchConfirmation(screen))) {
                withServerLink { repository.performAction(it, tmuxSessionName, TmuxAction.ENTER) }
                continue
            }
            val details = TmuxText.runtimeDetails(screen)
            val applied = if (family != null) HostSettings.family(details.model) == family
                else effort != null && details.effort == effort
            if (applied) {
                confirmation.complete()
                _state.update { it.copy(runtimeDetails = details, pendingModelFamily = null, modelSwitchFailure = null) }
                return
            }
        }
    }

    fun scrollLive() {
        if (_state.value.alternateOn) {
            requests.trySend(Request.Action(TmuxAction.SCROLL_BOTTOM))
        }
    }

    private val _historyPage = MutableStateFlow<String?>(null)
    private val historyMutex = Mutex(false)

    @Volatile
    private var historyMode = false

    private val _historyPages = MutableStateFlow<List<String>>(emptyList())

    private var transcriptHistoryDepth = 0

    val historyPages: StateFlow<List<String>> = _historyPages

    suspend fun enterHistory() {
        historyMutex.withLock {
            if (historyMode) return
            historyMode = true
            _historyPages.value = emptyList()
            _historyPage.value = null
            scrollBackPage()
        }
        val deadline = System.currentTimeMillis() + HISTORY_FIRST_PAGE_MS
        while (System.currentTimeMillis() < deadline && historyMode) {
            val page = _historyPage.value
            if (!page.isNullOrBlank()) {
                historyMutex.withLock { if (historyMode) _historyPages.value = listOf(page) }
                return
            }
            delay(HISTORY_PAGE_POLL_MS)
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
        scrollLiveDirect()
    }

    suspend fun loadOlderIntoTranscript(): Boolean = historyMutex.withLock {
        if (ChatHistoryProtocol.supports(agent, hostSessionId) || historyMode) return@withLock false
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

    fun answerQuestion(question: PendingQuestion, choices: Set<Int>, other: String): Boolean {
        if (_state.value.sending ||
            historyConnection?.state?.value?.history?.pendingQuestions?.contains(question) != true) return false
        return requests.trySend(Request.QuestionAnswer(question, choices, other)).isSuccess
    }

    private fun startStream() {
        if (streamJob?.isActive == true) return
        streamJob = scope.launch {
            ReaderStreamPump<SshClient>(
                wanted = pollGate.active,
                connect = { withTimeout(CONNECT_TIMEOUT_MS) { repository.connect(host) } },
                stream = { client, heard ->
                    paneFrames(paneGate.active) {
                        streamFrames(client, heard)
                        error("The tmux reader ended")
                    }
                },
                release = { client -> runCatching { client.disconnect() } },
                onClient = { client ->
                    streamClient = client
                    if (client != null) _state.update { it.copy(status = ReaderStatus.Live, lastFailure = null) }
                },
                onConnecting = { _state.update { it.copy(status = ReaderStatus.Connecting) } },
                onFailure = { throwable ->
                    val message = throwable.message ?: throwable::class.java.simpleName
                    _state.update { it.copy(status = ReaderStatus.Failed(message), lastFailure = message) }
                },
                reconnectDelayMillis = RECONNECT_DELAY_MS,
                reconnectWait = { millis -> withTimeoutOrNull(millis) { streamNudge.receive() } },
                reuseFirstBytesMillis = REUSE_FIRST_BYTES_MS,
            ).pump()
        }
    }

    private suspend fun streamFrames(client: SshClient, heard: () -> Unit) {
        repository.streamSession(
            client,
            tmuxSessionName,
            agent,
            onBytes = heard,
            hosted = hostSessionId.isNotBlank(),
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
            val settings = hostSettings()
            _state.update { current ->
                nextPaneState(current, frame, agent, settings, System.currentTimeMillis()) ?: current
            }
        }
    }

    private suspend fun runAction(request: Request) {
        _state.update { it.copy(sending = true, actionError = null) }
        try {
            withTimeout(REQUEST_TIMEOUT_MS) {
                val questionScreen = if (request is Request.QuestionAnswer) {
                    withServerLink { repository.captureSession(it, tmuxSessionName) }
                } else null
                withFreshClient { client ->
                    when (request) {
                        is Request.RuntimeCommand -> {
                            repository.sendPrompt(client, tmuxSessionName, request.text)
                            confirmModelSwitchWhilePaneHidden(request.text)
                        }
                        is Request.Prompt -> repository.sendPrompt(
                            client,
                            tmuxSessionName,
                            request.record.text,
                        )
                        is Request.Cancel -> {
                            repository.heldPrompt(client, historyScript, tmuxSessionName, request.record, cancel = true)
                            _state.update { current -> current.copy(sentRecord = current.sentRecord.filterNot {
                                it.messageId == request.record.messageId
                            } + request.record.copy(delivery = QueuedMessages.Delivery.CANCELLED)) }
                        }
                        is Request.RestartAi -> {
                            val outcome = repository.restartAi(client, historyScript, tmuxSessionName, request.sid, request.cwd)
                            if (outcome is AiStopped.Outcome.Failed) {
                                _state.update { it.copy(aiRestartRequestedAtMillis = 0) }
                                throw dev.multiprompt.companion.ssh.SshProblem.Connection(outcome.message)
                            }
                            Unit
                        }
                        is Request.FreshStart -> {
                            val outcome = repository.freshStart(client, historyScript, request.provider, request.sid)
                            if (outcome is dev.multiprompt.companion.reader.FreshStart.Outcome.Failed) {
                                throw dev.multiprompt.companion.ssh.SshProblem.Connection(outcome.message)
                            }
                            Unit
                        }
                        is Request.Action -> repository.performAction(
                            client,
                            tmuxSessionName,
                            request.action,
                        )
                        is Request.QuestionAnswer -> repository.answerQuestion(
                            client,
                            tmuxSessionName,
                            request.question,
                            requireNotNull(questionScreen),
                            request.choices,
                            request.other,
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
        } catch (timeout: TimeoutCancellationException) {
            _state.update {
                it.copy(
                    actionError = "The send timed out",
                    failedActions = it.failedActions + 1,
                    aiRestartRequestedAtMillis = if (request is Request.RestartAi) 0 else it.aiRestartRequestedAtMillis,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            _state.update {
                it.copy(
                    actionError = throwable.message ?: throwable::class.java.simpleName,
                    failedActions = it.failedActions + 1,
                    aiRestartRequestedAtMillis = if (request is Request.RestartAi) 0 else it.aiRestartRequestedAtMillis,
                )
            }
        } finally {
            _state.update { it.copy(sending = false) }
        }
    }

    private suspend fun <T> withServerLink(block: suspend (SshClient) -> T): T =
        repository.withServerLink(host, block = block)

    suspend fun sendFile(file: DeviceFile): String = withFreshClient { client ->
        repository.uploadFile(client, tmuxSessionName, file)
    }

    private suspend fun <T> withFreshClient(block: suspend (SshClient) -> T): T {
        val client = withTimeout(CONNECT_TIMEOUT_MS) { repository.connect(host) }
        return try {
            block(client)
        } finally {
            runCatching { client.disconnect() }
        }
    }

    override fun close() {
        _state.update { it.copy(status = ReaderStatus.Closed) }
        pollGate.close()
        paneGate.close()
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
        const val SWITCH_TIMEOUT_MS = 75_000L
        const val RESTART_NEWEST_TIMEOUT_MS = 30_000L
        const val RECONNECT_DELAY_MS = 3_000L
        const val REUSE_FIRST_BYTES_MS = 2_000L
        const val SENT_PROMPT_MEMORY = 3
        const val MODEL_CONFIRM_TIMEOUT_MS = 25_000L
        const val MODEL_CONFIRM_STEP_MS = 700L
        const val MODEL_CONFIRM_READS = 8
        const val HISTORY_FIRST_PAGE_MS = 6_000L
        const val HISTORY_PAGE_POLL_MS = 150L
        const val HISTORY_PAGE_STEP_MS = 350L
        const val HISTORY_PAGE_READ_MS = 500L
        const val HISTORY_PAGE_STABLE_READS = 3
        const val HISTORY_PAGE_WAIT_MS = 4_000L
        const val HISTORY_RETURN_MS = 1_200L
    }
}

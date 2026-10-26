package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.ssh.SshRepository
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

data class ChatHistoryState(
    val history: ChatHistory = ChatHistory(),
    val loaded: Boolean = false,
    val retrying: Boolean = false,
)

class ChatHistoryConnection(
    private val repository: SshRepository,
    private val host: HostProfile,
    private val identity: ChatHistoryProtocol.Identity,
    private val script: ByteArray,
    private val cache: ChatHistoryCache,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(ChatHistoryState())
    val state: StateFlow<ChatHistoryState> = _state.asStateFlow()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null
    @Volatile private var nearTop = false
    @Volatile private var awaitingMessages = false
    private val cacheKey = listOf(host.id, host.hostname, host.port.toString(), host.username,
        identity.agent.name, identity.tmux, identity.cwd).joinToString("\u0000")

    fun nearTop(value: Boolean) {
        nearTop = value
        if (value) wake.trySend(Unit)
    }

    fun awaitingMessages(value: Boolean) {
        if (value == awaitingMessages) return
        awaitingMessages = value
        if (value) wake.trySend(Unit)
    }

    fun start() {
        if (job != null) return
        job = scope.launch {
            runCatching { cache.restore(cacheKey) }.getOrNull()?.let {
                _state.value = ChatHistoryState(it, loaded = true)
            }
            var retryDelay = 2_000L
            var requestId = 0L
            while (isActive) {
                try {
                    val client = withTimeout(20_000L) { repository.connect(host) }
                    try {
                        repository.streamChatHistory(client, script) { exchange ->
                            var wasOlder = false
                            while (isActive) {
                                val current = _state.value.history
                                val before = if (nearTop && !wasOlder && !current.more) current.start else 0
                                requestId++
                                val page = ChatHistoryProtocol.parse(
                                    exchange(ChatHistoryProtocol.request(requestId, identity, current, before)),
                                    requestId, identity,
                                ) ?: error("History unavailable")
                                val next = current.apply(page)
                                _state.value = ChatHistoryState(next, loaded = true)
                                if (next != current) runCatching { cache.save(cacheKey, next) }
                                retryDelay = 2_000L
                                wasOlder = page.mode == "older"
                                if (!next.more && !(nearTop && next.start > 0)) {
                                    withTimeoutOrNull(if (awaitingMessages) 250L else 2_000L) { wake.receive() }
                                }
                            }
                        }
                    } finally {
                        runCatching { client.disconnect() }
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException && !isActive) throw failure
                    _state.value = _state.value.copy(retrying = true)
                }
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
            }
        }
    }

    override fun close() {
        wake.close()
        scope.cancel()
    }
}

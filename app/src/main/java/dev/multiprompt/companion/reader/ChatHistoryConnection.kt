package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.ssh.SshRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

data class ChatHistoryState(
    val history: ChatHistory = ChatHistory(),
    val loaded: Boolean = false,
    val retrying: Boolean = false,
    val replica: MessageReplica? = null,
    val unresolved: List<MessageJournal.Entry> = emptyList(),
    val messageError: String? = null,
    val engine: Pair<Long, String>? = null,
)

sealed interface MessageActionResult {
    data object Applied : MessageActionResult
    data object Unknown : MessageActionResult
    data object BlockedRecovery : MessageActionResult
    data class Rejected(val reason: String) : MessageActionResult
}

class ChatHistoryConnection(
    private val repository: SshRepository,
    private val host: HostProfile,
    private val identity: ChatHistoryProtocol.Identity,
    private val script: ByteArray,
    private val cache: ChatHistoryCache,
    private val journal: MessageJournal? = null,
    private val gate: MessageGate? = null,
    private val connectedExchange: (suspend (suspend (suspend (String) -> String) -> Unit) -> Unit)? = null,
    private val beforeAdmission: (suspend () -> Unit)? = null,
) : AutoCloseable {
    private sealed interface Command {
        data class Submit(val text: String, val generation: Long, val result: CompletableDeferred<PromptAdmission>) : Command
        data class Action(val kind: String, val id: String, val epoch: Long?, val attempt: String?, val generation: Long, val result: CompletableDeferred<MessageActionResult>) : Command
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(ChatHistoryState())
    val state: StateFlow<ChatHistoryState> = _state.asStateFlow()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val resyncs = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val token = Any()
    private val account = listOf(host.id, host.hostname, host.port.toString(), host.username, host.hostKeyType.orEmpty(), host.hostKeyFingerprint.orEmpty(), host.hostKeys.joinToString(",")).joinToString("\u0000")
    private val cacheKey = listOf(host.id, host.hostname, host.port.toString(), host.username,
        identity.agent.name, identity.tmux, identity.cwd).joinToString("\u0000")
    private val provider = when (identity.agent) { AgentKind.CLAUDE -> "claude"; AgentKind.CODEX -> "codex"; else -> null }
    @Volatile private var selected = false
    @Volatile private var freshSid: String? = null
    @Volatile private var generation = 0L
    @Volatile private var resetFence = false
    @Volatile private var ownedKey: String? = null
    private data class ResetAck(val sid: String, val id: String, val epoch: Long, val generation: Long)
    private var resetAck: ResetAck? = null
    private var resetReply: CompletableDeferred<MessageActionResult>? = null
    private var job: Job? = null
    @Volatile private var resyncing: CompletableDeferred<Unit>? = null
    @Volatile private var nearTop = false
    @Volatile private var awaitingMessages = false
    @Volatile private var deliveryIds: List<String> = emptyList()

    private fun key(sid: String) = "$account\u0000$provider\u0000$sid"
    private fun wire(sid: String) = MessageProtocol.Scope(requireNotNull(provider), sid, identity.tmux)
    private fun owns(sid: String) = selected && ownedKey == key(sid) && gate?.owns(key(sid), token) == true
    private fun pending(sid: String) = journal?.entries().orEmpty().filter { it.account == account && it.scope.provider == provider && it.scope.sid == sid }

    private fun admit(sid: String, observed: Long, block: () -> MessageJournal.Entry): MessageJournal.Entry? =
        synchronized(this) {
            if (generation != observed || !selected || ownedKey != key(sid)) null
            else gate?.ifOwned(key(sid), token, block)
        }

    private fun markIssued(sid: String, observed: Long, id: String): Boolean = synchronized(this) {
        if (generation != observed || !selected || ownedKey != key(sid)) false
        else gate?.ifOwned(key(sid), token) { requireNotNull(journal).markIssued(id); true } ?: false
    }

    @Synchronized fun select(value: Boolean) {
        if (selected == value) return
        selected = value
        generation++
        if (!value) {
            resetReply?.complete(MessageActionResult.Unknown)
            resetReply = null
            resetAck = null
            resetFence = freshSid?.let { sid -> runCatching { pending(sid).any { it.operation == "reset" } }.getOrDefault(true) } ?: resetFence
        }
        ownedKey?.let { gate?.release(it, token) }
        ownedKey = null
        if (value) freshSid?.let { bind(it) }
        wake.trySend(Unit)
    }

    @Synchronized private fun bind(sid: String) {
        if (freshSid != sid) {
            ownedKey?.let { gate?.release(it, token) }
            ownedKey = null
            generation++
            resetReply?.complete(MessageActionResult.Unknown)
            resetReply = null
            resetAck = null
            resetFence = runCatching { pending(sid).any { it.operation == "reset" } }.getOrDefault(true)
        }
        freshSid = sid
        if (selected && provider != null && ownedKey != key(sid)) {
            gate?.claim(key(sid), token)
            ownedKey = key(sid)
        }
    }

    suspend fun submit(text: String): PromptAdmission {
        if (freshSid == null || !selected || resetFence) return PromptAdmission.Rejected("Chat is not ready")
        val result = CompletableDeferred<PromptAdmission>()
        if (commands.trySend(Command.Submit(text, generation, result)).isFailure) return PromptAdmission.Rejected("Chat is unavailable")
        wake.trySend(Unit)
        return result.await()
    }

    fun actionToken(): Long = generation
    suspend fun checkStatus(id: String, observed: Long = generation) = action("status", id, observed = observed)
    suspend fun replayUnknown(id: String, observed: Long = generation) = action("replay", id, observed = observed)
    suspend fun retryFailed(row: MessageProtocol.Row, confirmed: Boolean, observed: Long = generation): MessageActionResult =
        if (!confirmed) MessageActionResult.Rejected("Retry requires confirmation") else action("retry", row.id, row.epoch, row.attempt, observed)
    suspend fun dismiss(row: MessageProtocol.Row, observed: Long = generation) = action("dismiss", row.id, row.epoch, row.attempt, observed)
    suspend fun recoverReset(observed: Long = generation) = action("reset", "", observed = observed)

    @Synchronized fun resetConversation(): Deferred<MessageActionResult> {
        if (freshSid == null || !selected) return CompletableDeferred(MessageActionResult.Rejected("Chat is not ready"))
        generation++
        resetFence = true
        val result = CompletableDeferred<MessageActionResult>()
        if (commands.trySend(Command.Action("reset", "", null, null, generation, result)).isFailure) {
            resetFence = freshSid?.let { pending(it).any { entry -> entry.operation == "reset" } } ?: false
            result.complete(MessageActionResult.Rejected("Chat is unavailable"))
        }
        wake.trySend(Unit)
        return result
    }

    @Synchronized private fun clearUnrecordedReset(command: Command.Action, sid: String) {
        if (command.kind == "reset" && command.generation == generation && freshSid == sid &&
            pending(sid).none { it.operation == "reset" }) resetFence = false
    }

    private suspend fun action(kind: String, id: String, epoch: Long? = null, attempt: String? = null, observed: Long = generation): MessageActionResult {
        val result = CompletableDeferred<MessageActionResult>()
        if (commands.trySend(Command.Action(kind, id, epoch, attempt, observed, result)).isFailure) return MessageActionResult.Rejected("Chat is unavailable")
        wake.trySend(Unit)
        return result.await()
    }

    fun nearTop(value: Boolean) { nearTop = value; if (value) wake.trySend(Unit) }
    fun awaitingMessages(value: Boolean) { if (value != awaitingMessages) { awaitingMessages = value; if (value) wake.trySend(Unit) } }
    fun deliveryIds(ids: List<String>) { if (deliveryIds != ids) { deliveryIds = ids; wake.trySend(Unit) } }
    fun resync(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also {
        if (resyncs.trySend(it).isFailure) it.cancel()
        wake.trySend(Unit)
    }

    private suspend fun connected(block: suspend (suspend (String) -> String) -> Unit) {
        val transport = connectedExchange
        if (transport != null) {
            transport(block)
        } else {
            val client = withTimeout(20_000L) { repository.connect(host) }
            try { repository.streamChatHistory(client, script, block) }
            finally { runCatching { client.disconnect() } }
        }
    }

    fun start() {
        if (job != null) return
        job = scope.launch {
            val restoreGeneration = generation
            runCatching { cache.restore(cacheKey) }.getOrNull()?.let {
                synchronized(this@ChatHistoryConnection) {
                    if (generation == restoreGeneration && freshSid == null) {
                        val unresolved = runCatching { pending(it.sid) }.getOrDefault(emptyList())
                        _state.value = ChatHistoryState(it, loaded = true, unresolved = unresolved)
                    }
                }
            }
            var retryDelay = 2_000L
            var requestId = 0L
            val deferredCommands = java.util.ArrayDeque<Command>()
            while (isActive) {
                var connectionGeneration = generation
                try {
                    connected { exchange ->
                            var replica: MessageReplica? = null
                            var replicaSid: String? = null
                            var wasOlder = false
                            val checked = mutableSetOf<String>()
                            val engine = if (provider != null) runCatching {
                                requestId++
                                MessageProtocol.engine(exchange(JSONObject().put("id", requestId).put("op", "engine_info").toString() + "\n"), requestId)
                            }.getOrNull() else null
                            poll@ while (isActive) {
                                if (provider != null && !selected) {
                                    withTimeoutOrNull(2_000L) { wake.receive() }
                                    continue@poll
                                }
                                val historyGeneration = generation
                                var caughtUp = true
                                resyncing = resyncing ?: resyncs.tryReceive().getOrNull()
                                val current = if (resyncing != null) _state.value.history.resetSeed() else _state.value.history
                                val before = if (nearTop && !wasOlder && !current.more) current.start else 0
                                requestId++
                                val page = ChatHistoryProtocol.parse(exchange(ChatHistoryProtocol.request(requestId, identity,
                                    if (resyncing != null) current.copy(sid = "") else current, before, if (provider == null) deliveryIds else emptyList(), held = provider == null)), requestId, identity)
                                    ?: error("History unavailable")
                                if (generation != historyGeneration) { replica = null; continue@poll }
                                val next = current.apply(page)
                                if (replicaSid != page.sid) {
                                    replica = null
                                    replicaSid = page.sid
                                }
                                val cycleGeneration = synchronized(this@ChatHistoryConnection) {
                                    if (generation != historyGeneration) null else { bind(page.sid); generation }
                                } ?: continue@poll
                                connectionGeneration = cycleGeneration
                                val messageScope = if (provider != null && engine != null) wire(page.sid) else null
                                if (messageScope != null) {
                                    if (replica == null) {
                                        val pages = mutableListOf<MessageProtocol.SnapshotPage>()
                                        var cursor: Long? = 0
                                        var snapshotId: String? = null
                                        do {
                                            val arguments = JSONObject().put("cursor", cursor)
                                            if (snapshotId != null) arguments.put("snapshot_id", snapshotId)
                                            requestId++
                                            val snapshot = MessageProtocol.snapshot(exchange(MessageProtocol.request(requestId, messageScope, "message_snapshot", arguments)), requestId, messageScope)
                                            if (generation != cycleGeneration || !owns(page.sid)) { replica = null; continue@poll }
                                            if (pages.isNotEmpty()) require(snapshot.id == pages.first().id && snapshot.epoch == pages.first().epoch && snapshot.seq == pages.first().seq)
                                            pages.add(snapshot)
                                            snapshotId = snapshot.id
                                            if (snapshot.next != null) require(snapshot.next == cursor!! + snapshot.items.size && snapshot.next > cursor!!)
                                            cursor = snapshot.next
                                            if (cursor != null) {
                                                while (true) {
                                                    val queued = commands.tryReceive().getOrNull() ?: break
                                                    val queuedGeneration = when (queued) {
                                                        is Command.Submit -> queued.generation
                                                        is Command.Action -> queued.generation
                                                    }
                                                    if (queuedGeneration != cycleGeneration) {
                                                        when (queued) {
                                                            is Command.Submit -> queued.result.complete(PromptAdmission.Rejected("Chat changed"))
                                                            is Command.Action -> queued.result.complete(MessageActionResult.Rejected("Chat changed"))
                                                        }
                                                    } else if (queued is Command.Action && queued.kind == "reset") {
                                                        val previous = MessageReplica(snapshot.epoch, snapshot.seq)
                                                        val lock = gate?.claimIfOwned(key(page.sid), token)
                                                        if (lock == null) {
                                                            clearUnrecordedReset(queued, page.sid)
                                                            queued.result.complete(MessageActionResult.Rejected("Chat is not ready"))
                                                        } else lock.withLock {
                                                            val outcome = try { performAction(queued, previous, messageScope, exchange) { ++requestId } }
                                                            catch (failure: Exception) { queued.result.complete(MessageActionResult.Unknown); throw failure }
                                                            synchronized(this@ChatHistoryConnection) {
                                                                if (outcome == MessageActionResult.Applied && queued.generation == generation && owns(page.sid)) {
                                                                    resetReply = queued.result
                                                                } else {
                                                                    clearUnrecordedReset(queued, page.sid)
                                                                    queued.result.complete(outcome)
                                                                }
                                                            }
                                                        }
                                                        replica = null
                                                        continue@poll
                                                    } else deferredCommands.addLast(queued)
                                                }
                                            }
                                        } while (cursor != null)
                                        replica = MessageReplica.snapshot(pages)
                                    }
                                    for (entry in pending(page.sid).filter { it.operation == "submit" && it.id !in checked }) {
                                        requestId++
                                        MessageProtocol.mutation(exchange(MessageProtocol.request(requestId, messageScope, "message_status", JSONObject().put("message_id", entry.messageId))), requestId, messageScope, entry.messageId)
                                        if (generation != cycleGeneration || !owns(page.sid)) { replica = null; continue@poll }
                                        checked.add(entry.id)
                                    }
                                    requestId++
                                    val pageEvents = MessageProtocol.events(exchange(MessageProtocol.request(requestId, messageScope, "message_events", JSONObject().put("after", replica!!.cursor))), requestId, messageScope, replica!!.cursor)
                                    if (generation != cycleGeneration || !owns(page.sid)) { replica = null; continue@poll }
                                    replica = replica!!.apply(pageEvents)
                                    if (pageEvents.next < pageEvents.head) { caughtUp = false; wake.trySend(Unit) }
                                }
                                val published = synchronized(this@ChatHistoryConnection) {
                                    if (generation != cycleGeneration || freshSid != page.sid) false
                                    else if (messageScope != null) gate?.ifOwned(key(page.sid), token) {
                                        if (selected && ownedKey == key(page.sid)) {
                                            val materialized = requireNotNull(replica)
                                            pending(page.sid).filter { it.operation == "submit" && materialized.messages[it.messageId]?.text == it.text }.forEach { journal?.remove(it.id) }
                                            pending(page.sid).filter { it.operation == "dismiss" && materialized.messages[it.messageId]?.state == MessageProtocol.State.CANCELLED }.forEach { journal?.remove(it.id) }
                                            resetFence = resetFence || pending(page.sid).any { it.operation == "reset" }
                                            val ack = resetAck
                                            if (ack != null && ack.sid == page.sid && ack.generation == cycleGeneration && materialized.epoch >= ack.epoch && pending(page.sid).any { it.id == ack.id && it.operation == "reset" }) {
                                                journal?.remove(ack.id)
                                                resetAck = null
                                                resetFence = false
                                                resetReply?.complete(MessageActionResult.Applied)
                                                resetReply = null
                                            }
                                            _state.value = ChatHistoryState(next, loaded = true, replica = replica, unresolved = pending(page.sid), engine = engine)
                                            if (next != current) runCatching { cache.save(cacheKey, next) }
                                            true
                                        } else false
                                    } ?: false else {
                                        _state.value = ChatHistoryState(next, loaded = true, replica = replica, unresolved = pending(page.sid), engine = engine,
                                            messageError = if (provider != null && engine == null) "Shared messaging is unavailable on this host" else null)
                                        if (next != current) runCatching { cache.save(cacheKey, next) }
                                        true
                                    }
                                }
                                if (!published) { replica = null; continue@poll }
                                resyncing?.complete(Unit)
                                resyncing = null
                                retryDelay = 2_000L
                                wasOlder = page.mode == "older"
                                while (true) {
                                    if (!caughtUp) {
                                        while (true) deferredCommands.addLast(commands.tryReceive().getOrNull() ?: break)
                                    }
                                    val command = if (caughtUp) {
                                        if (deferredCommands.isEmpty()) commands.tryReceive().getOrNull() else deferredCommands.removeFirst()
                                    } else {
                                        val iterator = deferredCommands.iterator()
                                        var reset: Command? = null
                                        while (iterator.hasNext()) {
                                            val candidate = iterator.next()
                                            if (candidate is Command.Action && candidate.kind == "reset") {
                                                iterator.remove()
                                                reset = candidate
                                                break
                                            }
                                        }
                                        reset
                                    }
                                    if (command == null) break
                                    val observed = replica
                                    if (messageScope == null || observed == null) {
                                        when (command) {
                                            is Command.Submit -> command.result.complete(PromptAdmission.Rejected("Chat is not ready"))
                                            is Command.Action -> {
                                                clearUnrecordedReset(command, page.sid)
                                                command.result.complete(MessageActionResult.Rejected("Chat is not ready"))
                                            }
                                        }
                                        continue
                                    }
                                    val lock = gate?.claimIfOwned(key(page.sid), token)
                                    if (lock == null) {
                                        when (command) {
                                            is Command.Submit -> command.result.complete(PromptAdmission.Rejected("Chat is no longer selected"))
                                            is Command.Action -> command.result.complete(MessageActionResult.Rejected("Chat is no longer selected"))
                                        }
                                        continue
                                    }
                                    lock.withLock {
                                        if (!owns(page.sid)) {
                                            when (command) {
                                                is Command.Submit -> command.result.complete(PromptAdmission.Rejected("Chat is no longer selected"))
                                                is Command.Action -> command.result.complete(MessageActionResult.Rejected("Chat is no longer selected"))
                                            }
                                        } else when (command) {
                                            is Command.Submit -> {
                                                if (command.generation != generation || resetFence || pending(page.sid).any { it.operation == "reset" }) {
                                                    command.result.complete(PromptAdmission.Rejected("Reset or navigation is pending"))
                                                } else {
                                                    beforeAdmission?.invoke()
                                                    val entry = try { admit(page.sid, command.generation) {
                                                        if (resetFence) error("Reset pending")
                                                        requireNotNull(journal).create(account, messageScope, "submit", text = command.text, epoch = observed.epoch)
                                                    } } catch (_: Exception) { null }
                                                    if (entry == null) command.result.complete(PromptAdmission.Rejected("Could not save message")) else {
                                                        command.result.complete(PromptAdmission.Recorded(entry.id))
                                                        synchronized(this@ChatHistoryConnection) {
                                                            if (command.generation == generation && owns(page.sid)) _state.value = _state.value.copy(unresolved = pending(page.sid))
                                                        }
                                                        if (!resetFence && markIssued(page.sid, command.generation, entry.id)) {
                                                            requestId++
                                                            val arguments = JSONObject().put("message_id", entry.id).put("text", entry.text)
                                                            MessageProtocol.requiredMutation(exchange(MessageProtocol.request(requestId, messageScope, "message_submit", arguments)), requestId, messageScope, entry.id, entry.text)
                                                            replica = null
                                                        }
                                                    }
                                                }
                                            }
                                            is Command.Action -> {
                                                val result = try {
                                                    performAction(command, observed, messageScope, exchange) { ++requestId }
                                                } catch (failure: Exception) {
                                                    command.result.complete(MessageActionResult.Unknown)
                                                    throw failure
                                                }
                                                synchronized(this@ChatHistoryConnection) {
                                                    if (command.generation != generation || !owns(page.sid)) {
                                                        command.result.complete(MessageActionResult.Unknown)
                                                        replica = null
                                                    } else if (command.kind == "reset" && result == MessageActionResult.Applied) {
                                                        resetReply = command.result
                                                        replica = null
                                                        _state.value = _state.value.copy(replica = null)
                                                    } else {
                                                        if (result != MessageActionResult.Applied) clearUnrecordedReset(command, page.sid)
                                                        command.result.complete(result)
                                                        if (result == MessageActionResult.Applied) replica = null
                                                    }
                                                }
                                                synchronized(this@ChatHistoryConnection) {
                                                    if (command.generation == generation && owns(page.sid)) _state.value = _state.value.copy(unresolved = pending(page.sid))
                                                }
                                            }
                                        }
                                    }
                                    if (replica == null) break
                                }
                                if (!next.more && !(nearTop && next.start > 0)) withTimeoutOrNull(if (awaitingMessages) 250L else 2_000L) { wake.receive() }
                            }
                    }
                } catch (failure: Exception) {
                    synchronized(this@ChatHistoryConnection) {
                        val disconnectedSid = freshSid
                        if (generation == connectionGeneration && (disconnectedSid == null || provider == null || owns(disconnectedSid))) {
                            resetReply?.complete(MessageActionResult.Unknown)
                            resetReply = null
                            resetAck = null
                            freshSid = null
                            _state.value = _state.value.copy(retrying = true, messageError = "Connection lost; confirmation may be unknown",
                                unresolved = disconnectedSid?.let { pending(it) } ?: _state.value.unresolved)
                        }
                    }
                    while (deferredCommands.isNotEmpty()) {
                        when (val command = deferredCommands.removeFirst()) {
                            is Command.Submit -> command.result.complete(PromptAdmission.Rejected("Chat is disconnected"))
                            is Command.Action -> command.result.complete(MessageActionResult.Unknown)
                        }
                    }
                    if (failure is CancellationException && !isActive) throw failure
                    (resyncing ?: resyncs.tryReceive().getOrNull())?.completeExceptionally(failure)
                    resyncing = null
                    while (true) {
                        when (val command = commands.tryReceive().getOrNull() ?: break) {
                            is Command.Submit -> command.result.complete(PromptAdmission.Rejected("Chat is disconnected"))
                            is Command.Action -> command.result.complete(MessageActionResult.Unknown)
                        }
                    }
                }
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
            }
        }
    }

    private suspend fun performAction(command: Command.Action, replica: MessageReplica, wire: MessageProtocol.Scope,
        exchange: suspend (String) -> String, nextId: () -> Long): MessageActionResult {
        if (command.generation != generation) return MessageActionResult.Rejected("Chat changed")
        val entries = pending(wire.sid)
        val existing = entries.firstOrNull { it.id == command.id || it.messageId == command.id }
        val row = replica.messages[command.id]
        val args = JSONObject()
        val operation: String
        var entry: MessageJournal.Entry? = null
        when (command.kind) {
            "status" -> {
                if (existing == null || existing.operation != "submit") return MessageActionResult.Rejected("Message is not unknown")
                operation = "message_status"; args.put("message_id", existing.messageId)
            }
            "replay" -> {
                if (existing == null || existing.operation !in setOf("submit", "retry", "dismiss")) return MessageActionResult.Rejected("No recovery operation")
                if (existing.operation == "retry") {
                    val current = replica.messages[existing.messageId]
                        ?: return MessageActionResult.Rejected("Retry cannot be confirmed yet")
                    val safeProbe = current.state in setOf(MessageProtocol.State.ACCEPTED, MessageProtocol.State.STARTED, MessageProtocol.State.CANCELLED)
                    val sameFailedAttempt = current.state == MessageProtocol.State.FAILED && current.attempt == existing.attempt && existing.epoch == replica.epoch
                    if (!safeProbe && !sameFailedAttempt) return MessageActionResult.Rejected("Retry cannot be confirmed yet")
                } else if (existing.epoch != replica.epoch) return MessageActionResult.Rejected("Message belongs to an earlier reset")
                entry = existing
                operation = "message_${existing.operation}"
                args.put("message_id", existing.messageId)
                if (existing.operation == "submit") args.put("text", existing.text)
                if (existing.operation == "retry") args.put("retry_id", existing.id)
            }
            "retry", "dismiss" -> {
                if (resetFence || row == null || row.epoch != replica.epoch || row.epoch != command.epoch || row.attempt != command.attempt ||
                    (command.kind == "retry" && row.state != MessageProtocol.State.FAILED) ||
                    (command.kind == "dismiss" && row.state !in setOf(MessageProtocol.State.QUEUED, MessageProtocol.State.FAILED))) return MessageActionResult.Rejected("Message changed")
                beforeAdmission?.invoke()
                entry = entries.firstOrNull { it.operation == command.kind && it.messageId == row.id }
                    ?: admit(wire.sid, command.generation) { requireNotNull(journal).create(account, wire, command.kind, row.id, epoch = row.epoch, attempt = row.attempt) }
                    ?: return MessageActionResult.Rejected("Chat changed")
                operation = "message_${command.kind}"; args.put("message_id", row.id)
                if (command.kind == "retry") args.put("retry_id", entry.id)
            }
            "reset" -> {
                beforeAdmission?.invoke()
                var blocked = false
                entry = synchronized(this) {
                    if (command.generation != generation || !selected || ownedKey != key(wire.sid)) null
                    else gate?.ifOwned(key(wire.sid), token) {
                        val current = pending(wire.sid)
                        val unresolved = current.filter { it.operation != "reset" }
                        if (unresolved.any { it.issued }) {
                            blocked = true
                            null
                        } else {
                            unresolved.forEach { requireNotNull(journal).remove(it.id) }
                            current.firstOrNull { it.operation == "reset" }
                                ?: requireNotNull(journal).create(account, wire, "reset", epoch = replica.epoch)
                        }
                    }
                }
                if (blocked) return MessageActionResult.BlockedRecovery
                if (entry == null) return MessageActionResult.Rejected("Chat changed")
                operation = "message_reset"; args.put("reset_id", requireNotNull(entry).id)
            }
            else -> return MessageActionResult.Rejected("Unknown action")
        }
        if (command.generation != generation || !owns(wire.sid)) return MessageActionResult.Rejected("Chat changed")
        if (entry != null && !markIssued(wire.sid, command.generation, entry.id)) return MessageActionResult.Rejected("Chat changed")
        val id = nextId()
        val response = exchange(MessageProtocol.request(id, wire, operation, args))
        if (operation == "message_reset") {
            val result = MessageProtocol.reset(response, id)
            synchronized(this) {
                if (command.generation == generation && owns(wire.sid))
                    resetAck = ResetAck(wire.sid, requireNotNull(entry).id, result.epoch, command.generation)
            }
        } else {
            val result = if (operation == "message_status") MessageProtocol.mutation(response, id, wire, existing?.messageId, existing?.text)
                else MessageProtocol.requiredMutation(response, id, wire, requireNotNull(entry).messageId, if (operation == "message_submit") entry.text else null)
            if (result.row == null) return MessageActionResult.Unknown
            if (entry != null && entry.operation != "submit") journal?.remove(entry.id)
        }
        return if (command.generation == generation && owns(wire.sid)) MessageActionResult.Applied else MessageActionResult.Unknown
    }

    override fun close() {
        select(false)
        wake.close(); resyncs.close(); commands.close()
        resyncing?.cancel()
        generateSequence { resyncs.tryReceive().getOrNull() }.forEach { it.cancel() }
        generateSequence { commands.tryReceive().getOrNull() }.forEach {
            when (it) {
                is Command.Submit -> it.result.complete(PromptAdmission.Rejected("Chat closed"))
                is Command.Action -> it.result.complete(MessageActionResult.Unknown)
            }
        }
        scope.cancel()
    }
}

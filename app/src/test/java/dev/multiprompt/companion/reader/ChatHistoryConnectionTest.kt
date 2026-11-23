package dev.multiprompt.companion.reader

import android.app.Application
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.security.SecretStore
import dev.multiprompt.companion.ssh.SshRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatHistoryConnectionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val host = HostProfile(id = "host", label = "invalid", hostname = "", port = 0,
        username = "user", keySecretId = "missing")
    private fun repository() = SshRepository(SecretStore(RuntimeEnvironment.getApplication()))

    @Test
    fun restoresPrivateCacheAndRetainsItWhenConnectionFails() = runBlocking {
        val cache = ChatHistoryCache(temporary.newFolder())
        val identity = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "tmux", "/cwd")
        val row = ChatHistoryProtocol.Row("user", "Cached conversation € ✔", "id", "", 0, 100)
        val history = ChatHistory("sid", 1, 100, 0, 1, "a".repeat(64), rows = listOf(row))
        val key = listOf(host.id, host.hostname, host.port.toString(), host.username,
            identity.agent.name, identity.tmux, identity.cwd).joinToString("\u0000")
        cache.save(key, history)
        ChatHistoryConnection(repository(), host, identity, byteArrayOf(), cache, pollGate = ChatPollGate()).use { connection ->
            assertFalse(connection.state.value.loaded)
            connection.pollGate.acquire()
            connection.start()
            val failed = withTimeout(10_000L) { connection.state.first { it.retrying } }
            assertTrue(failed.loaded)
            assertEquals(history, failed.history)
            assertEquals(history.messages, failed.history.messages)
        }
    }

    @Test
    fun warmReaderStartsHistoryForCurrentAgentAndReplacesChangedIdentity() {
        SessionReaderConnection(repository(), host, "tmux", historyScript = byteArrayOf(),
            historyCache = ChatHistoryCache(temporary.newFolder())).use { reader ->
            val claude = reader.chatHistory(AgentKind.CLAUDE, "/cwd")
            assertSame(claude, reader.chatHistory(AgentKind.CLAUDE, "/cwd"))
            val codex = reader.chatHistory(AgentKind.CODEX, "/cwd")
            assertNotSame(claude, codex)
            assertSame(codex, reader.chatHistory(AgentKind.CODEX, "/cwd"))
            assertNotSame(codex, reader.chatHistory(AgentKind.CODEX, "/other"))
        }
    }

    @Test
    fun journalPreservesWaitingIdentityAndRejectsCollisions() {
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("identity-${java.util.UUID.randomUUID()}", 0)
        val journal = MessageJournal(preferences)
        val scope = MessageProtocol.Scope("claude", "sid1", "tmux")
        val id = java.util.UUID.randomUUID().toString()
        val entry = journal.create("account", scope, "submit", messageId = id, text = "original")
        assertEquals(id, entry.id)
        assertEquals(id, entry.messageId)
        assertThrows(IllegalStateException::class.java) {
            journal.create("other-account", scope, "submit", messageId = id, text = "replacement")
        }
        assertEquals(listOf(entry), journal.entries())
        assertThrows(IllegalArgumentException::class.java) {
            journal.create("account", scope, "submit", messageId = "invalid", text = "invalid id")
        }
    }

    @Test
    fun nativeSendWithoutFreshConnectionKeepsDraftUnsent() = runBlocking {
        SessionReaderConnection(repository(), host, "tmux", agent = AgentKind.CLAUDE, historyScript = byteArrayOf(),
            historyCache = ChatHistoryCache(temporary.newFolder())).use { reader ->
            val text = "Any idea when the visual update of the inbox is coming?"
            assertTrue(reader.sendPrompt(text) is PromptAdmission.Rejected)
            assertFalse(reader.resyncChat())
            assertTrue(reader.state.value.sentRecord.isEmpty())
        }
    }

    @Test
    fun resyncPreservesUnkeyedAndHeldMessagesUntilServerSettlesTheirIds() {
        val native = QueuedMessages.SentPrompt("same", 1)
        val first = native.copy(messageId = "first", delivery = QueuedMessages.Delivery.QUEUED)
        val second = first.copy(messageId = "second")
        val history = ChatHistory(rows = listOf(
            ChatHistoryProtocol.Row("user", "same", "one", "2023-11-14T22:13:20Z", 1, 2),
            ChatHistoryProtocol.Row("assistant", "Done", "two", "", 3, 4),
        ), held = listOf(second), deliveries = mapOf(
            "first" to QueuedMessages.Delivery.DELIVERED,
            "second" to QueuedMessages.Delivery.CANCELLED,
        ))
        assertEquals(listOf(native), unconfirmedAfterResync(history, listOf(native, first, second)))
        assertEquals(listOf(first), unconfirmedAfterResync(ChatHistory(), listOf(first)))
        assertEquals(emptyList<QueuedMessages.SentPrompt>(), unconfirmedAfterResync(
            ChatHistory(deliveries = mapOf("first" to QueuedMessages.Delivery.CANCELLED)), listOf(first)))
    }

    @Test
    fun liveRequestsFollowThePaneWhileArchivesKeepTheirExactSession() {
        val history = ChatHistory(sid = "cached", inode = 9, offset = 42)
        val live = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "pane", "/cwd", "saved")
        val request = org.json.JSONObject(ChatHistoryProtocol.request(1, live, history))
        assertTrue(request.getBoolean("follow"))
        assertEquals("saved", request.getString("sid"))
        assertEquals("cached", request.getString("cached_sid"))
        assertEquals(42L, request.getLong("offset"))
        val archive = org.json.JSONObject(ChatHistoryProtocol.request(2, live.copy(tmux = ""), history))
        assertFalse(archive.getBoolean("follow"))
        assertEquals("saved", archive.getString("sid"))
        assertEquals("cached", archive.getString("cached_sid"))
    }

    @Test
    fun resyncRequestsTheWholeTailFromTheSession() {
        val identity = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "tmux", "/cwd")
        val request = org.json.JSONObject(ChatHistoryProtocol.request(7, identity, ChatHistory()))
        assertEquals("", request.getString("cached_sid"))
        assertEquals(0L, request.getLong("offset"))
        assertEquals(0L, request.getLong("start"))
    }
    @Test
    fun resetSeedRetainsOnlySameSessionTerminalFactsAcrossStalePages() {
        val identity = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "tmux", "/cwd")
        for (terminal in listOf(QueuedMessages.Delivery.DELIVERED, QueuedMessages.Delivery.CANCELLED)) {
            val prompt = QueuedMessages.SentPrompt("text", 1, "id", QueuedMessages.Delivery.QUEUED, "sid", 9, 10)
            val initial = ChatHistory(sid = "sid", inode = 9, offset = 30, start = 5, mtime = 7,
                probe = "a".repeat(64), rows = listOf(ChatHistoryProtocol.Row("user", "old", "row", "", 5, 10)),
                deliveries = mapOf("guess" to QueuedMessages.Delivery.QUEUED), held = listOf(prompt))
            val observed = ChatHistoryProtocol.Page("sid", 0, 30, 9, false, 7, "a".repeat(64), "tail", 0,
                emptyList(), deliveries = mapOf("id" to terminal))
            val known = initial.apply(observed)
            val seed = known.resetSeed()
            assertEquals(mapOf("id" to terminal), seed.deliveries)
            assertTrue(seed.rows.isEmpty())
            assertTrue(seed.held.isEmpty())
            val request = org.json.JSONObject(ChatHistoryProtocol.request(1, identity, seed.copy(sid = "")))
            assertTrue(request.getBoolean("follow"))
            assertEquals("", request.getString("cached_sid"))
            for (field in listOf("inode", "offset", "start", "mtime", "before")) assertEquals(0L, request.getLong(field))
            assertEquals("", request.getString("probe"))
            val stale = observed.copy(deliveries = mapOf("id" to QueuedMessages.Delivery.QUEUED), held = listOf(prompt))
            for (page in listOf(stale, stale.copy(inode = 10))) {
                val recovered = seed.apply(page)
                assertEquals(terminal, recovered.deliveries["id"])
                assertFalse(recovered.projectMessages(sent = listOf(prompt)).single().pending)
                assertTrue(unconfirmedAfterResync(recovered, listOf(prompt)).isEmpty())
            }
            val rotated = known.apply(stale.copy(inode = 10))
            assertEquals(terminal, rotated.deliveries["id"])
            assertFalse(rotated.deliveries.containsKey("guess"))
            val other = seed.apply(stale.copy(sid = "other", inode = 10))
            assertEquals(QueuedMessages.Delivery.QUEUED, other.deliveries["id"])
            assertEquals(listOf(prompt), unconfirmedAfterResync(other, listOf(prompt)))
        }
    }

    private class FakeHost(private val wireAgent: String = "claude") {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val polls = java.util.concurrent.atomic.AtomicInteger()
        @Volatile var answered = false
        @Volatile var events: List<JSONObject> = emptyList()
        private val head get() = events.size.toLong()
        val stops = java.util.concurrent.CopyOnWriteArrayList<String>()
        val afters = java.util.concurrent.CopyOnWriteArrayList<Long>()
        @Volatile var dropAt = -1
        @Volatile var hangAt = -1
        @Volatile var online = true
        @Volatile var hangSnapshot = false
        @Volatile var rejectEngine = false
        @Volatile var dropFirstSubmit = false
        val retries = java.util.concurrent.CopyOnWriteArrayList<String>()
        val submissions = java.util.concurrent.CopyOnWriteArrayList<String>()
        private val submitted = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        private val digest = "b".repeat(64)

        private fun row(role: String, text: String, offset: Long, end: Long) =
            JSONObject().put("role", role).put("text", text).put("id", "row$offset").put("timestamp", "")
                .put("offset", offset).put("end", end).toString()

        private fun history(id: Long): String {
            val rows = listOf(row("user", "question", 0, 10)) + if (answered) listOf(row("assistant", "answer", 10, 20)) else emptyList()
            val end = if (answered) 20 else 10
            val header = listOf("@mp", id, "history", "ok", "sid1", 0, end, 1, 0, wireAgent, "tmux", 1, "a".repeat(64), "tail", 0).joinToString("\t")
            return JSONObject().put("id", id).put("wire", (listOf(header) + rows + TranscriptProtocol.END).joinToString("\n") + "\n").toString()
        }

        private fun result(id: Long, value: JSONObject) =
            JSONObject().put("id", id).put("message_protocol", 1).put("result", value).toString()

        suspend fun connect(block: suspend (suspend (String) -> String) -> Unit) {
            if (!online) throw java.io.IOException("Link down")
            block { line ->
                if (!online) throw java.io.IOException("Link down")
                if (calls.incrementAndGet() > 3_000) throw java.io.IOException("Request flood")
                val request = JSONObject(line)
                val id = request.getLong("id")
                when (request.optString("op")) {
                    "engine_info" -> {
                        if (rejectEngine) throw java.io.IOException("Engine handshake failed")
                        JSONObject().put("id", id)
                            .put("engine_info", JSONObject().put("protocol", 1).put("generation", 53).put("sha256", digest))
                            .put("message_protocols", JSONArray().put(1)).toString()
                    }
                    "message_snapshot" -> {
                        if (hangSnapshot) kotlinx.coroutines.awaitCancellation()
                        result(id, JSONObject().put("snapshot_id", "snap").put("epoch", 1).put("seq", head)
                            .put("items", JSONArray(submitted.map { JSONObject().put("message", it) })).put("next_cursor", JSONObject.NULL))
                    }
                    "message_status" -> result(id, JSONObject().put("epoch", 1).put("seq", head)
                        .put("message", submitted.firstOrNull { it.getString("id") == request.getString("message_id") } ?: JSONObject.NULL))
                    "message_submit" -> {
                        submissions.add(request.getString("text"))
                        val row = JSONObject().put("id", request.getString("message_id")).put("provider", "claude").put("sid", "sid1")
                            .put("text", request.getString("text")).put("position", submitted.size + 1).put("attempt", "attempt1")
                            .put("state", if (dropFirstSubmit) "failed" else "queued").put("deadline", JSONObject.NULL)
                            .put("receipt", JSONObject.NULL).put("epoch", 1).put("created", 1.0)
                        submitted.add(row)
                        result(id, JSONObject().put("epoch", 1).put("seq", head).put("message", row))
                    }
                    "message_retry" -> {
                        val token = request.getString("message_id")
                        retries.add(token)
                        val index = submitted.indexOfFirst { it.getString("id") == token }
                        require(index >= 0) { "unknown message" }
                        val row = JSONObject(submitted[index].toString()).put("state", "queued")
                            .put("receipt", JSONObject.NULL).put("attempt", "attempt2")
                        submitted[index] = row
                        result(id, JSONObject().put("epoch", 1).put("seq", head).put("message", row))
                    }
                    "message_events" -> {
                        afters.add(request.optLong("after"))
                        if (afters.size == dropAt) throw java.io.IOException("Dropped")
                        if (afters.size == hangAt) kotlinx.coroutines.awaitCancellation()
                        val rows = JSONArray()
                        events.forEachIndexed { index, event -> if (index + 1 > request.optLong("after")) rows.put(JSONObject().put("seq", index + 1).put("event", event)) }
                        result(id, JSONObject().put("epoch", 1).put("seq", head).put("events", rows).put("next_after", head))
                    }
                    "interrupt", "approval_reply" -> { stops.add(line.trim()); result(id, JSONObject().put("status", "ok")) }
                    "" -> { polls.incrementAndGet(); history(id) }
                    else -> error("Unexpected request")
                }
            }
        }
    }

    private fun hostEvent(kind: String, payload: JSONObject = JSONObject()) =
        JSONObject().put("v", 1).put("sid", "sid1").put("turn", "t1").put("kind", kind).put("at", 1).put("payload", payload)

    @Test
    fun conversationAppearsBeforeMessagingFinishesAndAStallShowsAnError(): Unit = runBlocking {
        val fake = FakeHost().apply { hangSnapshot = true }
        ChatHistoryConnection(repository(), host, ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "tmux", "/cwd"),
            byteArrayOf(), ChatHistoryCache(temporary.newFolder()), gate = MessageGate(),
            connectedExchange = { fake.connect(it) }, stallMillis = 500L, pollGate = ChatPollGate()).use { chat ->
            chat.pollGate.acquire()
            chat.start()
            val loaded = withTimeout(5_000L) { chat.state.first { it.loaded } }
            assertEquals("question", loaded.history.rows.single().text)
            assertFalse(loaded.ready)
            val failed = withTimeout(5_000L) { chat.state.first { it.connectionError != null } }
            assertEquals(ConversationLoad.TIMED_OUT, failed.connectionError)
            assertTrue(failed.retrying)
            fake.hangSnapshot = false
            chat.nudge()
            withTimeout(5_000L) { chat.state.first { it.ready } }
            assertNull(chat.state.value.connectionError)
        }
    }

    @Test
    fun failedEngineHandshakeRetriesInsteadOfDisablingMessagingForTheConnection(): Unit = runBlocking {
        val fake = FakeHost().apply { rejectEngine = true }
        ChatHistoryConnection(repository(), host, ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "tmux", "/cwd"),
            byteArrayOf(), ChatHistoryCache(temporary.newFolder()), gate = MessageGate(), connectedExchange = { fake.connect(it) }, pollGate = ChatPollGate()).use { chat ->
            chat.pollGate.acquire()
            chat.start()
            val failed = withTimeout(5_000L) { chat.state.first { it.retrying } }
            assertTrue(failed.loaded)
            assertEquals("question", failed.history.rows.single().text)
            assertFalse(failed.ready)
            assertNotNull(failed.connectionError)
            fake.rejectEngine = false
            chat.nudge()
            withTimeout(5_000L) { chat.state.first { it.ready && it.engine != null } }
            assertNull(chat.state.value.messageError)
        }
    }

    @Test
    fun automaticReaderNudgeDoesNotInterruptAHealthyHistoryConnection(): Unit = runBlocking {
        val fake = FakeHost()
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val cache = ChatHistoryCache(temporary.newFolder())
        SessionReaderConnection(repository(), host, "tmux", AgentKind.CLAUDE, byteArrayOf(), cache).use { reader ->
            val chat = ChatHistoryConnection(repository(), host, ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "tmux", "/cwd"),
                byteArrayOf(), cache, gate = MessageGate(), connectedExchange = { block ->
                    if (attempts.incrementAndGet() > 1) kotlinx.coroutines.awaitCancellation()
                    fake.connect(block)
                }, pollGate = ReflectionHelpers.getField<ChatPollGate>(reader, "pollGate"))
            ReflectionHelpers.setField(reader, "historyConnection", chat)
            reader.nudge()
            reader.observe()
            chat.start()
            withTimeout(5_000L) { chat.state.first { it.ready } }
            reader.nudge()
            assertNull(withTimeoutOrNull(1_000L) { chat.state.first { it.connectionError != null } })
            assertTrue(chat.state.value.ready)
            assertEquals(1, attempts.get())
        }
    }

    @Test
    fun readerFlushesWaitingMessagesAfterInitialFailureAndAfterAnotherDroppedLink(): Unit = runBlocking {
        val fake = FakeHost().apply { online = false }
        val cache = ChatHistoryCache(temporary.newFolder())
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("pending-${java.util.UUID.randomUUID()}", 0)
        SessionReaderConnection(repository(), host, "tmux", AgentKind.CLAUDE, byteArrayOf(), cache).use { reader ->
            val chat = ChatHistoryConnection(repository(), host, ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "tmux", "/cwd"),
                byteArrayOf(), cache, journal = MessageJournal(preferences), gate = MessageGate(), connectedExchange = { fake.connect(it) },
                pollGate = ReflectionHelpers.getField<ChatPollGate>(reader, "pollGate"))
            ReflectionHelpers.setField(reader, "historyConnection", chat)
            ReflectionHelpers.getField<MutableStateFlow<ChatHistoryConnection?>>(reader, "histories").value = chat
            reader.observe()
            reader.start()
            chat.start()
            for (text in listOf("/clear", "after dropped link")) {
                withTimeout(5_000L) { chat.state.first { it.retrying } }
                val waiting = reader.sendPrompt(text) as PromptAdmission.WaitingForConnection
                assertFalse(reader.pendingPrompts.state.value.isEmpty())
                fake.online = true
                chat.nudge()
                withTimeout(10_000L) { reader.pendingPrompts.accepted.first { waiting.id in it } }
                assertTrue(reader.pendingPrompts.state.value.isEmpty())
                withTimeout(10_000L) { while (fake.submissions.none { it == text }) delay(10L) }
                assertEquals(1, fake.submissions.count { it == text })
                withTimeout(10_000L) { chat.state.first { it.replica?.messages?.get(waiting.id)?.text == text } }
                fake.online = false
                chat.nudge()
            }
            assertEquals(listOf("/clear", "after dropped link"), fake.submissions.toList())
        }
    }

    @Test
    fun aSubmitTheChatDroppedWithoutAnAttemptIsQueuedAgainByItself(): Unit = runBlocking {
        val fake = FakeHost().apply { dropFirstSubmit = true }
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("recover-${java.util.UUID.randomUUID()}", 0)
        ChatHistoryConnection(repository(), host, ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "tmux", "/cwd"),
            byteArrayOf(), ChatHistoryCache(temporary.newFolder()), journal = MessageJournal(preferences), gate = MessageGate(),
            connectedExchange = { fake.connect(it) }, pollGate = ChatPollGate()).use { chat ->
            chat.pollGate.acquire()
            chat.start()
            withTimeout(10_000L) { chat.state.first { it.ready } }
            val admission = chat.submit("sent while the reply streamed")
            assertTrue(admission is PromptAdmission.Recorded)
            withTimeout(10_000L) { chat.state.first { it.replica?.messages?.values?.any { row -> row.state == MessageProtocol.State.FAILED } == true } }
            withTimeout(10_000L) { while (fake.retries.isEmpty()) delay(20L) }
            assertEquals(listOf("sent while the reply streamed"), fake.submissions.toList())
            assertEquals(1, fake.retries.size)
            withTimeout(10_000L) { chat.state.first { it.replica?.messages?.values?.any { row -> row.state == MessageProtocol.State.QUEUED } == true } }
        }
    }

    @Test
    fun longLiveChatLoadsFromRecentHostEventsOnly() = runBlocking {
        val identity = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "tmux", "/cwd")
        val fake = FakeHost().apply { events = List(5_000) { hostEvent("host.status", JSONObject().put("state", "running")) } + hostEvent("turn.started") }
        val chat = ChatHistoryConnection(repository(), host, identity, byteArrayOf(), ChatHistoryCache(temporary.newFolder()),
            gate = MessageGate(), connectedExchange = { block -> fake.connect(block) }, pollGate = ChatPollGate())
        chat.use {
            chat.pollGate.acquire()
            chat.start()
            withTimeout(10_000L) { chat.state.first { it.replica?.host?.running == true } }
            assertEquals(5_001L - ChatHistoryConnection.HOST_REPLAY_TAIL, fake.afters.first())
        }
    }

    @Test
    fun reconnectKeepsHostStateInsteadOfReplayingAgain() = runBlocking {
        val identity = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "tmux", "/cwd")
        val fake = FakeHost().apply { events = listOf(hostEvent("turn.started")); dropAt = 2 }
        val chat = ChatHistoryConnection(repository(), host, identity, byteArrayOf(), ChatHistoryCache(temporary.newFolder()),
            gate = MessageGate(), connectedExchange = { block -> fake.connect(block) }, pollGate = ChatPollGate())
        chat.use {
            chat.pollGate.acquire()
            chat.start()
            withTimeout(15_000L) { chat.state.first { it.replica?.host?.running == true } }
            assertEquals(listOf(0L), fake.afters.filter { it == 0L })
            assertTrue(fake.afters.size >= 3)
        }
    }

    @Test
    fun silentlyStuckChatShowsRetryingAndReconnects(): Unit = runBlocking {
        val identity = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "tmux", "/cwd")
        val fake = FakeHost().apply { events = listOf(hostEvent("turn.started")); hangAt = 2 }
        val chat = ChatHistoryConnection(repository(), host, identity, byteArrayOf(), ChatHistoryCache(temporary.newFolder()),
            gate = MessageGate(), connectedExchange = { block -> fake.connect(block) }, stallMillis = 1_000L, pollGate = ChatPollGate())
        chat.use {
            chat.pollGate.acquire()
            chat.start()
            withTimeout(10_000L) { chat.state.first { it.retrying } }
            withTimeout(15_000L) { chat.state.first { !it.retrying && it.replica?.host?.running == true && fake.afters.size > 2 } }
        }
    }

    @Test
    fun stopWithAnOpenApprovalCancelsItLikeDesktop() = runBlocking {
        val identity = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "tmux", "/cwd")
        val fake = FakeHost().apply { events = listOf(hostEvent("turn.started"),
            hostEvent("approval.request", JSONObject().put("request_id", "req_1").put("tool", "Bash").put("title", "Run tests"))) }
        val chat = ChatHistoryConnection(repository(), host, identity, byteArrayOf(), ChatHistoryCache(temporary.newFolder()),
            gate = MessageGate(), connectedExchange = { block -> fake.connect(block) }, pollGate = ChatPollGate())
        chat.use {
            chat.pollGate.acquire()
            chat.start()
            withTimeout(10_000L) { chat.state.first { it.replica?.host?.request?.id == "req_1" } }
            assertTrue(chat.interruptHost())
            val line = withTimeout(10_000L) { while (fake.stops.isEmpty()) delay(50L); fake.stops.single() }
            assertEquals(HostReply.approval(JSONObject(line).getLong("id"), "sid1", "req_1", "cancel"), line)
        }
    }

    @Test
    fun stopInAServerRunChatSendsTheDesktopInterrupt() = runBlocking {
        val identity = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "tmux", "/cwd")
        val fake = FakeHost().apply { events = listOf(hostEvent("turn.started")) }
        val chat = ChatHistoryConnection(repository(), host, identity, byteArrayOf(), ChatHistoryCache(temporary.newFolder()),
            gate = MessageGate(), connectedExchange = { block -> fake.connect(block) }, pollGate = ChatPollGate())
        chat.use {
            assertFalse(chat.interruptHost())
            chat.pollGate.acquire()
            chat.start()
            withTimeout(10_000L) { chat.state.first { it.replica?.host?.running == true } }
            var fellBack = false
            assertTrue(chat.interruptHost { fellBack = true })
            val line = withTimeout(10_000L) { while (fake.stops.isEmpty()) delay(50L); fake.stops.single() }
            assertEquals(HostReply.interrupt(JSONObject(line).getLong("id"), "sid1"), line)
            delay(500L)
            assertFalse(fellBack)
        }
    }

    @Test
    fun visibleChatKeepsUpdatingAfterAnotherReaderOfTheSameChatCloses() = runBlocking {
        val identity = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "tmux", "/cwd")
        val gate = MessageGate()
        val fake = FakeHost()
        val other = FakeHost()
        val visible = ChatHistoryConnection(repository(), host, identity, byteArrayOf(), ChatHistoryCache(temporary.newFolder()),
            gate = gate, connectedExchange = { block -> fake.connect(block) }, pollGate = ChatPollGate())
        val second = ChatHistoryConnection(repository(), host, identity, byteArrayOf(), ChatHistoryCache(temporary.newFolder()),
            gate = gate, connectedExchange = { block -> other.connect(block) }, pollGate = ChatPollGate())
        visible.use {
            visible.pollGate.acquire()
            visible.start()
            withTimeout(10_000L) { visible.state.first { it.engine != null && it.history.rows.size == 1 } }
            second.pollGate.acquire()
            second.start()
            withTimeout(10_000L) { second.state.first { it.engine != null && it.history.rows.size == 1 } }
            second.close()
            fake.answered = true
            val before = fake.calls.get()
            val shown = withTimeoutOrNull(5_000L) { visible.state.first { it.history.rows.size == 2 || it.retrying } }
            delay(3_000L)
            val requests = fake.calls.get() - before
            assertTrue("visible chat sent $requests requests in about 3 s", requests < 25)
            assertNotNull("visible chat never showed the answer", shown)
            assertEquals(listOf("question", "answer"), shown!!.history.rows.map { it.text })
        }
    }

    @Test
    fun closingASecondObserverKeepsPublishingAndLastCloseStopsAllHistoryRequests() = runBlocking {
        val identity = ChatHistoryProtocol.Identity(AgentKind.HAX, "tmux", "/cwd")
        val fake = FakeHost("codex")
        val gate = ChatPollGate()
        val first = gate.acquire()
        val second = gate.acquire()
        val chat = ChatHistoryConnection(repository(), host, identity, byteArrayOf(), ChatHistoryCache(temporary.newFolder()),
            gate = MessageGate(), connectedExchange = { block -> fake.connect(block) }, pollGate = gate)
        chat.use {
            chat.start()
            withTimeout(10_000L) { chat.state.first { it.history.rows.size == 1 } }
            second.close()
            fake.answered = true
            chat.nearTop(true)
            val shown = withTimeout(5_000L) { chat.state.first { it.history.rows.size == 2 } }
            assertEquals(listOf("question", "answer"), shown.history.rows.map { it.text })
            first.close()
            delay(300L)
            val settled = fake.calls.get()
            delay(3_000L)
            assertEquals("HIDDEN_READER_IO: history request without an active lease", settled, fake.calls.get())
            gate.acquire().use {
                withTimeout(1_000L) { while (fake.calls.get() == settled) delay(5L) }
            }
        }
    }

    private fun hiddenThenOpened(agent: AgentKind, wireAgent: String): Unit = runBlocking {
        val identity = ChatHistoryProtocol.Identity(agent, "tmux", "/cwd")
        val fake = FakeHost(wireAgent)
        val chat = ChatHistoryConnection(repository(), host, identity, byteArrayOf(), ChatHistoryCache(temporary.newFolder()),
            gate = MessageGate(), connectedExchange = { block -> fake.connect(block) }, pollGate = ChatPollGate())
        chat.use {
            chat.start()
            delay(10_000L)
            assertEquals("hidden $agent chat opened a history stream", 0, fake.calls.get())
            val opened = System.nanoTime()
            val chatLease = chat.pollGate.acquire()
            withTimeout(5_000L) { while (fake.polls.get() == 0) delay(5L) }
            val waitedMillis = (System.nanoTime() - opened) / 1_000_000L
            assertTrue("opened $agent chat polled after $waitedMillis ms", waitedMillis <= 250L)
            chatLease.close()
            delay(300L)
            val settled = fake.polls.get()
            delay(3_000L)
            assertEquals("hidden $agent chat kept polling", settled, fake.polls.get())
        }
    }

    @Test
    fun hiddenClaudeChatStopsPollingAndPollsAgainAtOnceWhenOpened() = hiddenThenOpened(AgentKind.CLAUDE, "claude")

    @Test
    fun hiddenHaxChatStopsPollingAndPollsAgainAtOnceWhenOpened() = hiddenThenOpened(AgentKind.HAX, "codex")

}

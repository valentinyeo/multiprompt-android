package dev.multiprompt.companion.reader

import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import dev.multiprompt.companion.MultipromptApplication
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.model.HostProfile
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ChatHistoryActorTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as MultipromptApplication
    private val messageId = "1234567890123456"

    internal inner class Fixture(val provider: String) {
        val host = HostProfile(UUID.randomUUID().toString(), "test", "host", username = "user")
        val identity = ChatHistoryProtocol.Identity(if (provider == "claude") AgentKind.CLAUDE else AgentKind.CODEX, "chat")
        val account = listOf(host.id, host.hostname, host.port.toString(), host.username, "", "", "").joinToString("\u0000")
        val prefs = context.getSharedPreferences(UUID.randomUUID().toString(), 0)
        @Volatile var failJournalRead = false
        @Volatile var failJournalWrite = false
        val journal = MessageJournal(object : SharedPreferences by prefs {
            override fun getAll(): MutableMap<String, *> {
                if (failJournalRead) error("preference read failed")
                return prefs.all
            }
            override fun edit(): SharedPreferences.Editor {
                if (failJournalWrite) error("preference write failed")
                return prefs.edit()
            }
        })
        val gate = MessageGate()
        var sid = "sid"
        var epoch = 1L
        var row: JSONObject? = null
        var retryCalls = 0
        var lastRetryId: String? = null
        var dropRetryAfterAdmission = false
        var admittedRetryId: String? = null
        var retryAdmissions = 0
        var submitCalls = 0
        var dismissCalls = 0
        var lastDismiss: JSONObject? = null
        var dropDismissAfterAdmission = false
        var dropDismissBeforeAdmission = false
        var directHandedOffReply = false
        var hideSnapshotRow = false
        var dismissPause: CompletableDeferred<Unit>? = null
        val dismissStarted = CompletableDeferred<Unit>()
        var resetCalls = 0
        var dropSubmit = false
        var dropAfterAdmission = false
        var dropReset = false
        var paged = false
        var targetOnLaterPage = false
        var targetOnThirdPage = false
        val failNextEvents = java.util.concurrent.atomic.AtomicBoolean(false)
        var pauseRestartSnapshot: CompletableDeferred<Unit>? = null
        val restartSnapshotStarted = CompletableDeferred<Unit>()
        var statusPause: CompletableDeferred<Unit>? = null
        val statusStarted = CompletableDeferred<Unit>()
        var lastStatus: JSONObject? = null
        var pauseSnapshot: CompletableDeferred<Unit>? = null
        val snapshotStarted = CompletableDeferred<Unit>()
        val secondSnapshotStarted = CompletableDeferred<Unit>()
        val thirdSnapshotStarted = CompletableDeferred<Unit>()
        var pauseSecondSnapshot: CompletableDeferred<Unit>? = null
        var snapshotCount = 0
        val laterPageRequested = CompletableDeferred<Unit>()
        val thirdPageRequested = CompletableDeferred<Unit>()
        val laterPageRelease = CompletableDeferred<Unit>()
        var blockLaterPage = false
        val resetRequested = CompletableDeferred<Unit>()
        var historyPause: CompletableDeferred<Unit>? = null
        val historyPaused = CompletableDeferred<Unit>()
        var growingEvents = false
        @Volatile var nativeRows: List<JSONObject>? = null
        val nativeDelivered = CompletableDeferred<Unit>()
        var eventCalls = 0
        var admissionPause: CompletableDeferred<Unit>? = null
        val admissionStarted = CompletableDeferred<Unit>()
        val wire get() = MessageProtocol.Scope(provider, sid, "chat")
        fun row(state: String, attempt: String = "attempt", epoch: Long = this.epoch) = JSONObject()
            .put("id", messageId).put("provider", provider).put("sid", sid).put("text", "hello")
            .put("position", 1).put("attempt", attempt).put("state", state).put("deadline", JSONObject.NULL)
            .put("receipt", JSONObject.NULL).put("epoch", epoch).put("created", 1.0)
        suspend fun exchange(raw: String): String {
            val request = JSONObject(raw)
            val id = request.getLong("id")
            fun result(value: JSONObject) = JSONObject().put("id", id).put("message_protocol", 1).put("result", value).toString()
            return when (request.optString("op")) {
                "engine_info" -> JSONObject().put("id", id).put("engine_info", JSONObject().put("protocol", 1).put("generation", 22).put("sha256", "a".repeat(64))).put("message_protocols", JSONArray().put(1)).toString()
                "message_snapshot" -> {
                    val number = ++snapshotCount
                    val cursor = request.optLong("cursor", 0)
                    if (cursor == 2L) thirdPageRequested.complete(Unit)
                    if (cursor > 0) {
                        laterPageRequested.complete(Unit)
                        if (blockLaterPage) laterPageRelease.await()
                    }
                    val items = JSONArray()
                    if (targetOnLaterPage && cursor == 0L) items.put(JSONObject().put("message", row("queued").put("id", "sentinel123456789")))
                    if (targetOnThirdPage && cursor == 1L) items.put(JSONObject().put("message", row("queued").put("id", "sentinel223456789")))
                    if (!hideSnapshotRow && (cursor == 0L && !targetOnLaterPage || cursor == 1L && targetOnLaterPage && !targetOnThirdPage || cursor == 2L && targetOnThirdPage))
                        row?.let { items.put(JSONObject().put("message", JSONObject(it.toString()))) }
                    val next = if (paged && cursor == 0L) 1 else if (targetOnThirdPage && cursor == 1L) 2 else JSONObject.NULL
                    val response = result(JSONObject().put("snapshot_id", "snapshot").put("epoch", epoch).put("seq", 0).put("items", items).put("next_cursor", next))
                    val pause = if (number == 2 && pauseSecondSnapshot != null) pauseSecondSnapshot else pauseSnapshot
                    if (number == 1) snapshotStarted.complete(Unit)
                    if (number == 2) secondSnapshotStarted.complete(Unit)
                    if (number == 3) thirdSnapshotStarted.complete(Unit)
                    pause?.await()
                    if (cursor == 0L && laterPageRequested.isCompleted && pauseRestartSnapshot != null) {
                        restartSnapshotStarted.complete(Unit)
                        pauseRestartSnapshot!!.await()
                    }
                    response
                }
                "message_events" -> {
                    if (failNextEvents.getAndSet(false)) error("fixture disconnect")
                    eventCalls++
                    val native = nativeRows
                    if (native != null) {
                        val after = request.getLong("after")
                        val events = JSONArray()
                        native.forEachIndexed { index, row ->
                            val seq = index + 1L
                            if (seq > after) {
                                val fact = JSONObject().put("sid", sid).put("event_id", "native-$seq").put("inode", 1)
                                    .put("offset", row.getLong("offset")).put("end", row.getLong("end")).put("row", row)
                                events.put(JSONObject().put("seq", seq).put("event", JSONObject().put("native_message", fact)))
                            }
                        }
                        nativeDelivered.complete(Unit)
                        result(JSONObject().put("epoch", epoch).put("seq", native.size).put("events", events).put("next_after", native.size))
                    } else if (!growingEvents) result(JSONObject().put("epoch", epoch).put("seq", 0).put("events", JSONArray()).put("next_after", 0))
                    else {
                        val next = request.getLong("after") + 1
                        val event = JSONObject().put("seq", next).put("event", JSONObject().put("message", row ?: row("queued")))
                        result(JSONObject().put("epoch", epoch).put("seq", next + 1).put("events", JSONArray().put(event)).put("next_after", next))
                    }
                }
                "message_status" -> {
                    lastStatus = JSONObject(request.toString())
                    statusPause?.let { statusStarted.complete(Unit); it.await() }
                    result(JSONObject().put("epoch", epoch).put("seq", 0).put("message", row?.takeIf { it.getString("id") == request.getString("message_id") } ?: JSONObject.NULL))
                }
                "message_submit" -> {
                    assertEquals(provider, request.getString("provider"))
                    assertEquals(sid, request.getString("sid"))
                    submitCalls++
                    if (dropSubmit) error("lost acknowledgement")
                    if (row?.optString("id") != request.getString("message_id"))
                        row = row("queued").put("id", request.getString("message_id")).put("text", request.getString("text"))
                    else require(row?.getString("text") == request.getString("text"))
                    if (dropAfterAdmission) {
                        dropAfterAdmission = false
                        error("lost acknowledgement after admission")
                    }
                    result(JSONObject().put("epoch", epoch).put("seq", 0).put("message", row))
                }
                "message_dismiss" -> {
                    assertEquals(provider, request.getString("provider"))
                    assertEquals(sid, request.getString("sid"))
                    dismissCalls++
                    lastDismiss = JSONObject(request.toString())
                    dismissStarted.complete(Unit)
                    dismissPause?.await()
                    if (dropDismissBeforeAdmission) {
                        dropDismissBeforeAdmission = false
                        error("lost dismiss before admission")
                    }
                    val target = request.getString("message_id")
                    if (row == null) row = row("cancelled").put("id", target).put("text", request.getString("text"))
                    val current = requireNotNull(row)
                    require(current.getString("id") == target)
                    if (current.getLong("epoch") != epoch) {
                        result(JSONObject().put("epoch", epoch).put("seq", 0).put("message", JSONObject(current.toString()).put("state", "cancelled")))
                    } else if (current.getString("state") in setOf("sent", "accepted", "started")) {
                        if (directHandedOffReply) result(JSONObject().put("epoch", epoch).put("seq", 0).put("message", current))
                        else JSONObject().put("id", id).put("wire", "@mp\t$id\thistory\terror\t-\t0\n${TranscriptProtocol.END}\n").toString()
                    } else {
                        current.put("state", "cancelled")
                        if (dropDismissAfterAdmission) {
                            dropDismissAfterAdmission = false
                            error("lost dismiss acknowledgement")
                        }
                        result(JSONObject().put("epoch", epoch).put("seq", 0).put("message", current))
                    }
                }
                "message_reset" -> {
                    assertEquals(provider, request.getString("provider"))
                    assertEquals(sid, request.getString("sid"))
                    resetCalls++
                    resetRequested.complete(Unit)
                    if (dropReset) error("lost reset acknowledgement")
                    epoch++
                    paged = false
                    row?.let { current ->
                        if (current.getString("state") in setOf("accepted", "started")) current.put("epoch", epoch)
                        else current.put("state", "cancelled")
                    }
                    result(JSONObject().put("epoch", epoch).put("seq", 0))
                }
                "message_retry" -> {
                    retryCalls++
                    lastRetryId = request.getString("retry_id")
                    if (dropRetryAfterAdmission && admittedRetryId == null) {
                        admittedRetryId = lastRetryId
                        retryAdmissions++
                        row?.put("state", "queued")?.put("attempt", "retry-attempt")
                        dropRetryAfterAdmission = false
                        error("lost retry acknowledgement after admission")
                    }
                    result(JSONObject().put("epoch", epoch).put("seq", 0).put("message", row))
                }
                else -> {
                    historyPause?.let { historyPaused.complete(Unit); it.await() }
                    val wire = "@mp\t$id\thistory\tok\t$sid\t0\t0\t1\t0\t$provider\tchat\t1\t${"a".repeat(64)}\ttail\t0\n${TranscriptProtocol.END}\n"
                    JSONObject().put("id", id).put("wire", wire).toString()
                }
            }
        }
        fun connection(): ChatHistoryConnection = ChatHistoryConnection(app.sshRepository, host, identity, byteArrayOf(),
            ChatHistoryCache(File(context.cacheDir, UUID.randomUUID().toString())), journal, gate,
            connectedExchange = { block -> block { query -> exchange(query) } },
            beforeAdmission = { admissionPause?.let { admissionStarted.complete(Unit); it.await() } })
        suspend fun ready(connection: ChatHistoryConnection) = withTimeout(10_000) { connection.state.first { it.replica != null && it.history.sid == sid } }
    }

    @Test fun journalReadFailuresPreserveRawAndSettleAllCommands() = runBlocking {
        for (failure in listOf("malformed", "non-string", "bad-scope", "throwing")) {
            val f = Fixture("claude")
            val valid = f.journal.create(f.account, f.wire, "retry", messageId, epoch = 1, attempt = "attempt")
            f.journal.markIssued(valid.id)
            val owner = f.connection()
            val badKey = "unrelated12345678"
            try {
                owner.select(true); owner.start(); f.ready(owner)
                f.historyPause = CompletableDeferred()
                owner.nearTop(true)
                withTimeout(10_000) { f.historyPaused.await() }
                when (failure) {
                    "malformed" -> f.prefs.edit().putString(badKey, "not json").commit()
                    "non-string" -> f.prefs.edit().putInt(badKey, 12).commit()
                    "bad-scope" -> f.prefs.edit().putString(badKey, """{"account":"other","provider":"bad","sid":"other","tmux":"chat","operation":"reset","message_id":""}""").commit()
                    else -> f.failJournalRead = true
                }
                val rawBefore = f.prefs.all.toMap()
                val eventsBefore = f.eventCalls
                val snapshotsBefore = f.snapshotCount
                val send = async(start = CoroutineStart.UNDISPATCHED) { owner.submit("must not send") }
                val action = async(start = CoroutineStart.UNDISPATCHED) { owner.replayUnknown(valid.id) }
                val firstSync = owner.resync()
                val secondSync = owner.resync()
                f.historyPause!!.complete(Unit)
                assertTrue(withTimeout(10_000) { send.await() } is PromptAdmission.Rejected)
                assertEquals(MessageActionResult.Unknown, withTimeout(10_000) { action.await() })
                assertTrue(withTimeout(10_000) { runCatching { firstSync.await() }.isFailure })
                assertTrue(withTimeout(10_000) { runCatching { secondSync.await() }.isFailure })
                val failed = withTimeout(10_000) { owner.state.first { it.messageError?.contains("Local storage failure") == true } }
                assertEquals(valid.id, failed.unresolved.single().id)
                assertEquals(eventsBefore, f.eventCalls)
                assertEquals(snapshotsBefore, f.snapshotCount)
                assertEquals(rawBefore, f.prefs.all)
                assertEquals(0, f.submitCalls)
                assertEquals(0, f.retryCalls)
                assertEquals(0, f.resetCalls)
                f.failJournalRead = false
                f.prefs.edit().remove(badKey).commit()
                assertEquals(valid.id, f.journal.entries().single().id)
                assertTrue(f.journal.entries().single().issued)
            } finally { owner.close() }
        }
    }

    @Test fun journalWriteFailurePublishesStorageErrorWithoutSending() = runBlocking {
        val f = Fixture("claude")
        val owner = f.connection()
        try {
            owner.select(true); owner.start(); f.ready(owner)
            f.historyPause = CompletableDeferred()
            owner.nearTop(true)
            withTimeout(10_000) { f.historyPaused.await() }
            f.failJournalWrite = true
            val send = async(start = CoroutineStart.UNDISPATCHED) { owner.submit("must not send") }
            f.historyPause!!.complete(Unit)
            assertTrue(withTimeout(10_000) { send.await() } is PromptAdmission.Rejected)
            withTimeout(10_000) { owner.state.first { it.messageError?.contains("Local storage failure") == true } }
            assertTrue(f.prefs.all.isEmpty())
            assertEquals(0, f.submitCalls)
        } finally { owner.close() }
    }

    @Test fun growingEventHeadRejectsCommandsWithoutSending() = runBlocking {
        val f = Fixture("codex")
        f.row = f.row("queued")
        val owner = f.connection()
        try {
            owner.select(true); owner.start()
            val ready = f.ready(owner)
            val row = requireNotNull(ready.replica?.messages?.get(messageId))
            f.historyPause = CompletableDeferred()
            owner.nearTop(true)
            withTimeout(10_000) { f.historyPaused.await() }
            f.growingEvents = true
            val old = owner.actionToken()
            owner.select(false); owner.select(true)
            val stale = async(start = CoroutineStart.UNDISPATCHED) { owner.checkStatus(messageId, old) }
            val send = async(start = CoroutineStart.UNDISPATCHED) { owner.submit("must not send") }
            val status = async(start = CoroutineStart.UNDISPATCHED) { owner.checkStatus(messageId) }
            val replay = async(start = CoroutineStart.UNDISPATCHED) { owner.replayUnknown(messageId) }
            val remove = async(start = CoroutineStart.UNDISPATCHED) { owner.dismiss(row) }
            f.historyPause!!.complete(Unit)
            assertEquals("Chat changed", (withTimeout(10_000) { stale.await() } as MessageActionResult.Rejected).reason)
            assertEquals("Chat is catching up", (withTimeout(10_000) { send.await() } as PromptAdmission.Rejected).reason)
            for (command in listOf(status, replay, remove)) {
                assertEquals("Chat is catching up", (withTimeout(10_000) { command.await() } as MessageActionResult.Rejected).reason)
            }
            assertTrue(f.eventCalls > 1)
            assertEquals(0, f.submitCalls)
            assertEquals(0, f.retryCalls)
            assertEquals(0, f.dismissCalls)
            assertEquals(MessageActionResult.Applied, withTimeout(10_000) { owner.resetConversation().await() })
            assertEquals(1, f.resetCalls)
            assertTrue(owner.state.value.history.managedMessages(owner.state.value.replica, owner.state.value.unresolved).none { it.text == "hello" })
        } finally { owner.close() }
    }

    @Test fun malformedNativeIdentityKeepsOwnerCursorAndJournalThenAcceptsReplacement() = runBlocking {
        val malformed = listOf(
            "id" to 7, "id" to JSONObject(), "read_id" to 7, "read_id" to JSONObject(),
            "segment" to "0", "segment" to 0.5,
        )
        for ((field, bad) in malformed) {
            val f = Fixture("codex")
            val entry = f.journal.create(f.account, f.wire, "retry", messageId, epoch = 1, attempt = "attempt")
            f.journal.markIssued(entry.id)
            val owner = f.connection()
            fun native(offset: Int, text: String, segment: Int) = JSONObject().put("role", "assistant")
                .put("text", text).put("id", "native-$offset").put("read_id", "read")
                .put("segment", segment).put("offset", offset).put("end", offset + 1)
            try {
                owner.select(true); owner.start(); f.ready(owner)
                f.historyPause = CompletableDeferred()
                owner.nearTop(true)
                withTimeout(10_000) { f.historyPaused.await() }
                f.nativeRows = listOf(native(1, "first", 0).put(field, bad))
                f.historyPause!!.complete(Unit)
                withTimeout(10_000) { f.nativeDelivered.await() }
                val disconnected = withTimeout(10_000) { owner.state.first { it.retrying } }
                assertEquals(0L, disconnected.replica?.cursor)
                assertEquals(entry.id, f.journal.entries().single().id)
                f.nativeRows = listOf(native(1, "first", 0), native(2, "second", 1))
                val recovered = withTimeout(15_000) { owner.state.first { !it.retrying && it.replica?.cursor == 2L } }
                assertEquals(listOf("first", "second"), recovered.history.managedMessages(recovered.replica, recovered.unresolved).map { it.text })
                assertEquals(2, recovered.replica?.nativeFacts?.size)
                assertEquals(entry.id, f.journal.entries().single().id)
            } finally { owner.close() }
        }
    }

    @Test fun cancelledRetryCanBeAcknowledgedBeforeReset() = runBlocking {
        val f = Fixture("claude")
        val entry = f.journal.create(f.account, f.wire, "retry", messageId, epoch = 1, attempt = "attempt")
        f.journal.markIssued(entry.id)
        f.row = f.row("cancelled")
        val owner = f.connection()
        try {
            owner.select(true); owner.start(); f.ready(owner)
            assertTrue(owner.state.value.history.managedMessages(owner.state.value.replica, owner.state.value.unresolved).none { it.text == "hello" })
            assertEquals(MessageActionResult.Applied, withTimeout(10_000) { owner.replayUnknown(entry.id) })
            assertEquals(entry.id, f.lastRetryId)
            assertTrue(f.journal.entries().isEmpty())
            assertEquals(MessageActionResult.Applied, withTimeout(10_000) { owner.resetConversation().await() })
        } finally { owner.close() }
    }

    @Test fun staleRetryNeverCallsHostForNewFailedAttempt() = runBlocking {
        for (provider in listOf("claude", "codex")) {
            val f = Fixture(provider)
            val entry = f.journal.create(f.account, f.wire, "retry", messageId, epoch = 1, attempt = "old")
            f.row = f.row("failed", "new")
            val owner = f.connection()
            try {
                owner.select(true); owner.start(); f.ready(owner)
                assertTrue(owner.replayUnknown(entry.id) is MessageActionResult.Rejected)
                assertEquals(0, f.retryCalls)
                assertEquals(entry.id, f.journal.entries().single().id)
            } finally { owner.close() }
        }
    }

    @Test fun oldEpochAcceptedRetryUsesOriginalId() = runBlocking {
        for (provider in listOf("claude", "codex")) {
            val f = Fixture(provider)
            val entry = f.journal.create(f.account, f.wire, "retry", messageId, epoch = 1, attempt = "old")
            f.journal.markIssued(entry.id)
            f.epoch = 2; f.row = f.row("accepted", "new")
            val owner = f.connection()
            try {
                owner.select(true); owner.start(); f.ready(owner)
                assertEquals(0, f.retryCalls)
                assertEquals(MessageActionResult.Applied, owner.replayUnknown(entry.id))
                assertEquals(1, f.retryCalls)
                assertEquals(entry.id, f.lastRetryId)
                assertTrue(f.journal.entries().isEmpty())
            } finally { owner.close() }
        }
    }

    @Test fun staleSnapshotCannotPublishAfterNavigation() = runBlocking {
        val f = Fixture("claude")
        f.row = f.row("queued")
        f.pauseSnapshot = CompletableDeferred()
        f.pauseSecondSnapshot = CompletableDeferred()
        val owner = f.connection()
        try {
            owner.select(true); owner.start()
            withTimeout(10_000) { f.snapshotStarted.await() }
            owner.select(false)
            f.pauseSnapshot!!.complete(Unit)
            owner.select(true)
            withTimeout(10_000) { f.secondSnapshotStarted.await() }
            assertNull(owner.state.value.replica)
        } finally { owner.close() }
    }

    @Test fun lostSubmitIsNotAutomaticallyReplayed() = runBlocking {
        val f = Fixture("codex")
        f.dropSubmit = true
        val owner = f.connection()
        try {
            owner.select(true); owner.start(); f.ready(owner)
            assertTrue(owner.submit("hello") is PromptAdmission.Recorded)
            withTimeout(10_000) { owner.state.first { it.retrying } }
            f.dropSubmit = false
            withTimeout(10_000) { owner.state.first { !it.retrying && it.replica != null } }
            assertEquals(1, f.submitCalls)
            assertEquals("submit", f.journal.entries().single().operation)
        } finally { owner.close() }
    }
    @Test fun lostAcknowledgementAfterAdmissionReconcilesWithoutResubmit() = runBlocking {
        for (provider in listOf("claude", "codex")) {
            val f = Fixture(provider)
            f.dropAfterAdmission = true
            val owner = f.connection()
            try {
                owner.select(true); owner.start(); f.ready(owner)
                val admission = withTimeout(10_000) { owner.submit("hello") } as PromptAdmission.Recorded
                withTimeout(10_000) { owner.state.first { it.retrying } }
                val admitted = requireNotNull(f.row)
                val attempt = admitted.getString("attempt")
                assertEquals(admission.messageId, admitted.getString("id"))
                val recovered = withTimeout(10_000) {
                    owner.state.first { state ->
                        !state.retrying && state.replica?.messages?.get(admission.messageId)?.attempt == attempt &&
                            state.unresolved.none { it.id == admission.messageId }
                    }
                }
                assertEquals(admission.messageId, recovered.replica?.messages?.get(admission.messageId)?.id)
                assertEquals(1, f.submitCalls)
                assertTrue(f.journal.entries().isEmpty())
            } finally { owner.close() }
        }
    }

    @Test fun lostResetDoesNotFenceAnotherSid() = runBlocking {
        for (provider in listOf("claude", "codex")) {
            val f = Fixture(provider)
            f.dropReset = true
            val owner = f.connection()
            try {
                owner.select(true); owner.start(); f.ready(owner)
                assertEquals(MessageActionResult.Unknown, withTimeout(10_000) { owner.resetConversation().await() })
                val original = f.journal.entries().single()
                assertEquals("sid", original.scope.sid)
                f.sid = "next"
                f.ready(owner)
                assertTrue(withTimeout(10_000) { owner.submit("new conversation") } is PromptAdmission.Recorded)
                assertTrue(f.journal.entries().any { it.id == original.id && it.scope.sid == "sid" })
            } finally { owner.close() }
        }
    }

    @Test fun navigationBeforeAdmissionLeavesNoJournalOrRpc() = runBlocking {
        for (reset in listOf(false, true)) {
            val f = Fixture("claude")
            f.admissionPause = CompletableDeferred()
            val owner = f.connection()
            try {
                owner.select(true); owner.start(); f.ready(owner)
                val action = async { if (reset) owner.resetConversation().await() else owner.submit("hello") }
                withTimeout(10_000) { f.admissionStarted.await() }
                owner.select(false)
                f.admissionPause!!.complete(Unit)
                withTimeout(10_000) { action.await() }
                assertTrue(f.journal.entries().isEmpty())
                assertEquals(0, f.submitCalls)
            } finally { owner.close() }
        }
    }

    @Test fun aliasTakeoverBeforeAdmissionRejectsOldOwner() = runBlocking {
        val f = Fixture("codex")
        f.admissionPause = CompletableDeferred()
        val old = f.connection()
        val current = f.connection()
        try {
            old.select(true); old.start(); f.ready(old)
            val send = async { old.submit("hello") }
            withTimeout(10_000) { f.admissionStarted.await() }
            current.select(true); current.start(); f.ready(current)
            f.admissionPause!!.complete(Unit)
            assertTrue(withTimeout(10_000) { send.await() } is PromptAdmission.Rejected)
            assertTrue(f.journal.entries().isEmpty())
            assertEquals(0, f.submitCalls)
        } finally { old.close(); current.close() }
    }

    @Test fun resetPreemptsFrozenSnapshotWithoutPublishingPartialRows() = runBlocking {
        val f = Fixture("claude")
        f.row = f.row("queued")
        f.paged = true
        f.pauseSnapshot = CompletableDeferred()
        f.blockLaterPage = true
        val owner = f.connection()
        try {
            owner.select(true); owner.start()
            withTimeout(10_000) { f.snapshotStarted.await() }
            val reset = owner.resetConversation()
            assertNull(owner.state.value.replica)
            f.pauseSnapshot!!.complete(Unit)
            withTimeout(10_000) { f.resetRequested.await() }
            assertFalse("Later page was fetched before Reset", f.laterPageRequested.isCompleted)
            assertEquals(MessageActionResult.Applied, withTimeout(10_000) { reset.await() })
            assertEquals(2L, withTimeout(10_000) { owner.state.first { it.replica?.epoch == 2L } }.replica?.epoch)
            assertTrue(f.journal.entries().isEmpty())
        } finally { f.laterPageRelease.complete(Unit); owner.close() }
    }

    @Test fun queuedAndSentNeverProbeAnOldRetry() = runBlocking {
        for (state in listOf("queued", "sent")) {
            val f = Fixture("codex")
            val entry = f.journal.create(f.account, f.wire, "retry", messageId, epoch = 1, attempt = "old")
            f.journal.markIssued(entry.id)
            f.row = f.row(state, "new")
            val owner = f.connection()
            try {
                owner.select(true); owner.start(); f.ready(owner)
                assertTrue(owner.replayUnknown(entry.id) is MessageActionResult.Rejected)
                assertEquals(0, f.retryCalls)
                assertTrue(f.journal.entries().any { it.id == entry.id })
            } finally { owner.close() }
        }
    }

    @Test fun aliasUnknownRemoveUsesCurrentLocatorAndPreservesOtherChats() = runBlocking {
        for (provider in listOf("claude", "codex")) {
            val f = Fixture(provider)
            val old = f.wire.copy(tmux = "old-alias")
            val submit = f.journal.create(f.account, old, "submit", text = "alias original", epoch = 1)
            f.journal.markIssued(submit.id)
            val foreign = listOf(
                f.journal.create("other-account", old, "dismiss", submit.id, submit.text, 1),
                f.journal.create(f.account, MessageProtocol.Scope(if (provider == "claude") "codex" else "claude", f.sid, "chat"), "dismiss", submit.id, submit.text, 1),
                f.journal.create(f.account, MessageProtocol.Scope(provider, "other-sid", "chat"), "dismiss", submit.id, submit.text, 1),
            )
            foreign.forEach { f.journal.markIssued(it.id) }
            val raw = foreign.associate { it.id to f.prefs.getString(it.id, null) }
            val owner = f.connection()
            try {
                owner.select(true); owner.start(); f.ready(owner)
                assertEquals(MessageActionResult.Applied, withTimeout(10_000) { owner.removeUnknown(submit.id) })
                assertEquals(submit.id, f.lastDismiss!!.getString("message_id"))
                assertEquals(submit.text, f.lastDismiss!!.getString("text"))
                assertEquals("chat", f.lastDismiss!!.getString("tmux"))
                assertEquals("chat", f.lastStatus!!.getString("tmux"))
                assertEquals("cancelled", f.row!!.getString("state"))
                assertEquals(raw, foreign.associate { it.id to f.prefs.getString(it.id, null) })
                assertEquals(foreign.map { it.id }.toSet(), f.journal.entries().map { it.id }.toSet())
                assertEquals(0, f.submitCalls); assertEquals(0, f.retryCalls)
                assertEquals(MessageActionResult.Applied, withTimeout(10_000) { owner.resetConversation().await() })
                assertEquals(raw, foreign.associate { it.id to f.prefs.getString(it.id, null) })
            } finally { owner.close() }
        }
    }

    @Test fun aliasDismissBlocksSubmitReplayAndReusesIntent() = runBlocking {
        for (provider in listOf("claude", "codex")) {
            val f = Fixture(provider)
            val submit = f.journal.create(f.account, f.wire.copy(tmux = "old-alias"), "submit", text = "alias pending", epoch = 1)
            f.journal.markIssued(submit.id)
            val dismiss = f.journal.create(f.account, f.wire, "dismiss", submit.id, submit.text, 1)
            f.journal.markIssued(dismiss.id)
            f.dismissPause = CompletableDeferred()
            val owner = f.connection()
            try {
                owner.select(true); owner.start(); f.ready(owner)
                assertEquals(MessageActionResult.Rejected("Remove confirmation is unknown"), owner.replayUnknown(submit.id))
                val remove = async { owner.removeUnknown(submit.id) }
                withTimeout(10_000) { f.dismissStarted.await() }
                assertEquals(listOf(dismiss.id), f.journal.entries().filter { it.operation == "dismiss" }.map { it.id })
                f.dismissPause!!.complete(Unit)
                assertEquals(MessageActionResult.Applied, withTimeout(10_000) { remove.await() })
                assertTrue(f.journal.entries().isEmpty())
                assertEquals(0, f.submitCalls); assertEquals(0, f.retryCalls)
                assertEquals(MessageActionResult.Applied, withTimeout(10_000) { owner.resetConversation().await() })
            } finally { f.dismissPause?.complete(Unit); owner.close() }
        }
    }

    @Test fun aliasAcceptedAndCancelledSnapshotsSettleWithoutMutation() = runBlocking {
        for (provider in listOf("claude", "codex")) for (state in listOf("accepted", "cancelled")) {
            val f = Fixture(provider)
            val submit = f.journal.create(f.account, f.wire.copy(tmux = "old-alias"), "submit", text = "existing", epoch = 1)
            f.journal.markIssued(submit.id)
            if (state == "cancelled") {
                val dismiss = f.journal.create(f.account, f.wire, "dismiss", submit.id, submit.text, 1)
                f.journal.markIssued(dismiss.id)
            }
            f.row = f.row(state).put("id", submit.id).put("text", submit.text)
            f.nativeRows = listOf(JSONObject().put("role", "user").put("text", "native existing").put("id", "native").put("offset", 1).put("end", 2))
            val owner = f.connection()
            try {
                owner.select(true); owner.start()
                val ready = withTimeout(10_000) { owner.state.first {
                    it.replica != null && it.history.sid == f.sid && it.unresolved.isEmpty() &&
                        it.history.managedMessages(it.replica, it.unresolved).any { message -> message.text == "native existing" }
                } }
                assertTrue(ready.unresolved.isEmpty())
                assertTrue(ready.history.managedMessages(ready.replica, ready.unresolved).any { it.text == "native existing" })
                if (state == "accepted") assertTrue(ready.history.managedMessages(ready.replica, ready.unresolved).any { it.text == "existing" })
                assertEquals(0, f.submitCalls); assertEquals(0, f.retryCalls); assertEquals(0, f.dismissCalls)
                assertEquals(MessageActionResult.Applied, withTimeout(10_000) { owner.resetConversation().await() })
                if (state == "accepted") assertEquals("accepted", f.row!!.getString("state"))
            } finally { owner.close() }
        }
    }

    @Test fun aliasKnownRowCancelAckSettlesOriginalFromOtherLocator() = runBlocking {
        for (provider in listOf("claude", "codex")) {
            val f = Fixture(provider)
            val submit = f.journal.create(f.account, f.wire.copy(tmux = "old-alias"), "submit", text = "queued alias", epoch = 1)
            f.journal.markIssued(submit.id)
            val raw = f.prefs.getString(submit.id, null)
            f.journal.remove(submit.id)
            f.row = f.row("queued").put("id", submit.id).put("text", submit.text)
            f.dismissPause = CompletableDeferred()
            val owner = f.connection()
            try {
                owner.select(true); owner.start()
                val row = requireNotNull(f.ready(owner).replica?.messages?.get(submit.id))
                val remove = async { owner.dismiss(row) }
                withTimeout(10_000) { f.dismissStarted.await() }
                f.prefs.edit().putString(submit.id, raw).commit()
                f.dismissPause!!.complete(Unit)
                assertEquals(MessageActionResult.Applied, withTimeout(10_000) { remove.await() })
                assertTrue(f.journal.entries().isEmpty())
                assertEquals("chat", f.lastDismiss!!.getString("tmux"))
                assertEquals(submit.id, f.lastDismiss!!.getString("message_id"))
                assertEquals(0, f.submitCalls); assertEquals(0, f.retryCalls)
                assertEquals(MessageActionResult.Applied, withTimeout(10_000) { owner.resetConversation().await() })
            } finally { f.dismissPause?.complete(Unit); owner.close() }
        }
    }

    @Test fun aliasLostCancelAckReconcilesOnRestart() = runBlocking {
        for (provider in listOf("claude", "codex")) {
            val f = Fixture(provider)
            val submit = f.journal.create(f.account, f.wire.copy(tmux = "old-alias"), "submit", text = "alias lost", epoch = 1)
            f.journal.markIssued(submit.id)
            f.hideSnapshotRow = true
            val first = f.connection()
            var second: ChatHistoryConnection? = null
            try {
                first.select(true); first.start(); f.ready(first)
                f.dropDismissAfterAdmission = true
                assertEquals(MessageActionResult.Unknown, withTimeout(10_000) { first.removeUnknown(submit.id) })
                first.close()
                assertEquals(setOf("submit", "dismiss"), f.journal.entries().map { it.operation }.toSet())
                f.hideSnapshotRow = false
                val recovered = f.connection(); second = recovered
                recovered.select(true); recovered.start(); f.ready(recovered)
                withTimeout(10_000) { recovered.state.first { it.unresolved.isEmpty() } }
                assertEquals(1, f.dismissCalls); assertEquals(0, f.submitCalls); assertEquals(0, f.retryCalls)
                assertEquals(MessageActionResult.Applied, withTimeout(10_000) { recovered.resetConversation().await() })
            } finally { first.close(); second?.close() }
        }
    }

    @Test fun unknownRemoveCreatesTombstoneBeforeAndAfterAdmission() = runBlocking {
        for (provider in listOf("claude", "codex")) for (admitted in listOf(false, true)) {
            val f = Fixture(provider)
            f.hideSnapshotRow = true
            f.dropSubmit = !admitted
            f.dropAfterAdmission = admitted
            val other = f.journal.create(f.account, MessageProtocol.Scope(provider, "other", "chat"), "submit", text = "unrelated", epoch = 1)
            val owner = f.connection()
            try {
                owner.select(true); owner.start(); f.ready(owner)
                val original = (withTimeout(10_000) { owner.submit("original text") } as PromptAdmission.Recorded).messageId
                withTimeout(10_000) { owner.state.first { it.retrying } }
                f.dropSubmit = false
                withTimeout(15_000) { owner.state.first { !it.retrying && it.unresolved.any { e -> e.id == original } } }
                f.dismissPause = CompletableDeferred()
                val removal = async { owner.removeUnknown(original) }
                withTimeout(10_000) { f.dismissStarted.await() }
                val entries = f.journal.entries()
                val dismiss = entries.single { it.operation == "dismiss" }
                assertEquals(original, dismiss.messageId)
                assertEquals("original text", dismiss.text)
                assertNull(dismiss.attempt)
                assertTrue(dismiss.issued)
                assertTrue(entries.any { it.id == original && it.issued })
                assertEquals(original, f.lastDismiss!!.getString("message_id"))
                assertEquals("original text", f.lastDismiss!!.getString("text"))
                f.hideSnapshotRow = false
                f.dismissPause!!.complete(Unit)
                assertEquals(MessageActionResult.Applied, withTimeout(10_000) { removal.await() })
                withTimeout(10_000) { owner.state.first { it.replica?.messages?.get(original)?.state == MessageProtocol.State.CANCELLED } }
                assertEquals(listOf(other.id), f.journal.entries().map { it.id })
                assertEquals(1, f.submitCalls)
                val late = f.exchange(MessageProtocol.request(900, f.wire, "message_submit", JSONObject().put("message_id", original).put("text", "original text")))
                assertEquals(MessageProtocol.State.CANCELLED, MessageProtocol.requiredMutation(late, 900, f.wire, original, "original text").row?.state)
                assertEquals(MessageActionResult.Applied, withTimeout(10_000) { owner.resetConversation().await() })
                assertEquals(listOf(other.id), f.journal.entries().map { it.id })
            } finally { f.dismissPause?.complete(Unit); owner.close() }
        }
    }

    @Test fun lostCancelAckReconcilesAfterRestartWithoutResending() = runBlocking {
        for (provider in listOf("claude", "codex")) {
            val f = Fixture(provider)
            f.hideSnapshotRow = true
            f.dropSubmit = true
            val first = f.connection()
            var second: ChatHistoryConnection? = null
            try {
                first.select(true); first.start(); f.ready(first)
                val id = (first.submit("lost cancel") as PromptAdmission.Recorded).messageId
                withTimeout(10_000) { first.state.first { it.retrying } }
                f.dropSubmit = false
                withTimeout(15_000) { first.state.first { !it.retrying && it.unresolved.any { e -> e.id == id } } }
                f.dropDismissAfterAdmission = true
                assertEquals(MessageActionResult.Unknown, withTimeout(10_000) { first.removeUnknown(id) })
                first.close()
                assertEquals(setOf("submit", "dismiss"), f.journal.entries().map { it.operation }.toSet())
                assertEquals("cancelled", f.row!!.getString("state"))
                f.hideSnapshotRow = false
                val recovered = f.connection(); second = recovered
                recovered.select(true); recovered.start(); f.ready(recovered)
                withTimeout(10_000) { recovered.state.first { it.unresolved.isEmpty() } }
                assertEquals(1, f.dismissCalls)
                assertEquals(1, f.submitCalls)
                assertEquals(MessageActionResult.Applied, withTimeout(10_000) { recovered.resetConversation().await() })
            } finally { first.close(); second?.close() }
        }
    }

    @Test fun lostCancelBeforeAdmissionNeedsExplicitSameIntentRecovery() = runBlocking {
        for (provider in listOf("claude", "codex")) {
            val f = Fixture(provider)
            val submit = f.journal.create(f.account, f.wire, "submit", text = "recover", epoch = 1)
            f.journal.markIssued(submit.id)
            val owner = f.connection()
            try {
                owner.select(true); owner.start(); f.ready(owner)
                f.dropDismissBeforeAdmission = true
                assertEquals(MessageActionResult.Unknown, withTimeout(10_000) { owner.removeUnknown(submit.id) })
                val dismiss = f.journal.entries().single { it.operation == "dismiss" }
                assertTrue(dismiss.issued)
                withTimeout(10_000) { owner.state.first { it.retrying } }
                withTimeout(15_000) { owner.state.first { !it.retrying && it.unresolved.any { e -> e.id == dismiss.id } } }
                assertEquals(1, f.dismissCalls)
                assertEquals(0, f.submitCalls)
                assertTrue(owner.replayUnknown(submit.id) is MessageActionResult.Rejected)
                assertEquals(MessageActionResult.Applied, withTimeout(10_000) { owner.removeUnknown(dismiss.id) })
                assertEquals(2, f.dismissCalls)
                assertEquals(submit.id, f.lastDismiss!!.getString("message_id"))
                assertEquals("recover", f.lastDismiss!!.getString("text"))
                assertTrue(f.journal.entries().isEmpty())
                assertEquals(0, f.submitCalls)
            } finally { owner.close() }
        }
    }

    @Test fun mismatchedCanonicalTextNeverSettlesOrCancelsUnknownIntent() = runBlocking {
        for (provider in listOf("claude", "codex")) {
            val f = Fixture(provider)
            val submit = f.journal.create(f.account, f.wire, "submit", text = "original", epoch = 1)
            f.journal.markIssued(submit.id)
            val dismiss = f.journal.create(f.account, f.wire, "dismiss", submit.id, submit.text, 1, null)
            f.journal.markIssued(dismiss.id)
            f.row = f.row("cancelled").put("id", submit.id).put("text", "different")
            val owner = f.connection()
            try {
                owner.select(true); owner.start()
                val ready = f.ready(owner)
                assertEquals(setOf(submit.id, dismiss.id), f.journal.entries().map { it.id }.toSet())
                assertTrue(ready.history.managedMessages(ready.replica, ready.unresolved).any { it.text == "original" })
                assertEquals(MessageActionResult.Rejected("Message changed"), owner.removeUnknown(submit.id))
                assertEquals(0, f.dismissCalls)
                assertEquals(0, f.submitCalls)
                assertEquals(setOf(submit.id, dismiss.id), f.journal.entries().map { it.id }.toSet())
            } finally { owner.close() }
        }
    }

    @Test fun oldEpochSubmitWithoutCancelStillCannotReplay() = runBlocking {
        for (provider in listOf("claude", "codex")) {
            val f = Fixture(provider)
            val submit = f.journal.create(f.account, f.wire, "submit", text = "old", epoch = 1)
            f.journal.markIssued(submit.id)
            f.epoch = 2
            val owner = f.connection()
            try {
                owner.select(true); owner.start(); f.ready(owner)
                assertEquals(MessageActionResult.Rejected("Message belongs to an earlier reset"), owner.replayUnknown(submit.id))
                assertEquals(0, f.submitCalls)
                assertEquals(submit.id, f.journal.entries().single().id)
            } finally { owner.close() }
        }
    }

    @Test fun badRawJournalBlocksUnknownRemoveWithoutDeletingAnything() = runBlocking {
        val f = Fixture("codex")
        val submit = f.journal.create(f.account, f.wire, "submit", text = "preserve", epoch = 1)
        f.journal.markIssued(submit.id)
        val owner = f.connection()
        try {
            owner.select(true); owner.start(); f.ready(owner)
            f.admissionPause = CompletableDeferred()
            val remove = async { owner.removeUnknown(submit.id) }
            withTimeout(10_000) { f.admissionStarted.await() }
            f.prefs.edit().putString("badraw1234567890", "not json").commit()
            val before = f.prefs.all.toMap()
            f.admissionPause!!.complete(Unit)
            assertEquals(MessageActionResult.Unknown, withTimeout(10_000) { remove.await() })
            withTimeout(10_000) { owner.state.first { it.messageError?.contains("Local storage failure") == true } }
            assertEquals(before, f.prefs.all)
            assertEquals(0, f.dismissCalls)
            assertEquals(0, f.submitCalls)
        } finally { f.admissionPause?.complete(Unit); owner.close() }
    }

    @Test fun oldEpochCancelRecoveryPreservesAcceptedHostWork() = runBlocking {
        for (provider in listOf("claude", "codex")) {
            val f = Fixture(provider)
            val submit = f.journal.create(f.account, f.wire, "submit", text = "old accepted", epoch = 1)
            f.journal.markIssued(submit.id)
            val dismiss = f.journal.create(f.account, f.wire, "dismiss", submit.messageId, submit.text, 1, null)
            f.journal.markIssued(dismiss.id)
            f.epoch = 2
            f.row = f.row("accepted", epoch = 1).put("id", submit.id).put("text", submit.text)
            f.hideSnapshotRow = true
            val owner = f.connection()
            try {
                owner.select(true); owner.start(); f.ready(owner)
                assertTrue(owner.replayUnknown(submit.id) is MessageActionResult.Rejected)
                assertEquals(0, f.submitCalls)
                assertEquals(0, f.dismissCalls)
                assertEquals(MessageActionResult.Applied, withTimeout(10_000) { owner.removeUnknown(dismiss.id) })
                assertEquals("accepted", f.row!!.getString("state"))
                assertTrue(f.journal.entries().isEmpty())
                assertEquals(1, f.dismissCalls)
                assertEquals(0, f.submitCalls)
                assertEquals(MessageActionResult.Applied, withTimeout(10_000) { owner.resetConversation().await() })
            } finally { owner.close() }
        }
    }

    @Test fun handedOffUnknownIsNotRemovedAndDoesNotBlockReset() = runBlocking {
        for (provider in listOf("claude", "codex")) for (state in listOf("sent", "accepted", "started")) for (direct in listOf(false, true)) {
            val f = Fixture(provider)
            f.directHandedOffReply = direct
            val submit = f.journal.create(f.account, f.wire, "submit", text = "real work", epoch = 1)
            f.journal.markIssued(submit.id)
            f.row = f.row(state).put("id", submit.id).put("text", submit.text)
            f.hideSnapshotRow = true
            f.nativeRows = listOf(JSONObject().put("role", "user").put("text", "real transcript").put("id", "native")
                .put("offset", 1).put("end", 2))
            val owner = f.connection()
            try {
                owner.select(true); owner.start(); f.ready(owner)
                f.dismissPause = CompletableDeferred()
                val removal = async { owner.removeUnknown(submit.id) }
                withTimeout(10_000) { f.dismissStarted.await() }
                f.hideSnapshotRow = false
                f.dismissPause!!.complete(Unit)
                assertEquals(MessageActionResult.Rejected("Could not remove message"), withTimeout(10_000) { removal.await() })
                val refreshed = withTimeout(10_000) { owner.state.first {
                    it.replica?.messages?.get(submit.id)?.state?.name?.lowercase() == state && it.unresolved.isEmpty() &&
                        it.history.managedMessages(it.replica, it.unresolved).any { message -> message.text == "real transcript" }
                } }
                assertTrue(refreshed.history.managedMessages(refreshed.replica, refreshed.unresolved).any { it.text == "real work" })
                assertTrue(refreshed.history.managedMessages(refreshed.replica, refreshed.unresolved).any { it.text == "real transcript" })
                assertEquals(state, f.row!!.getString("state"))
                assertEquals(0, f.submitCalls)
                assertEquals(MessageActionResult.Applied, withTimeout(10_000) { owner.resetConversation().await() })
            } finally { f.dismissPause?.complete(Unit); owner.close() }
        }
    }

    @Test fun handedOffOutcomeSurvivesRestartUntilCanonicalRefresh() = runBlocking {
        for (provider in listOf("claude", "codex")) {
            val f = Fixture(provider)
            val submit = f.journal.create(f.account, f.wire, "submit", text = "accepted work", epoch = 1)
            f.journal.markIssued(submit.id)
            f.row = f.row("accepted").put("id", submit.id).put("text", submit.text)
            f.hideSnapshotRow = true
            val first = f.connection()
            var second: ChatHistoryConnection? = null
            try {
                first.select(true); first.start(); f.ready(first)
                assertEquals(MessageActionResult.Rejected("Could not remove message"), withTimeout(10_000) { first.removeUnknown(submit.id) })
                first.close()
                val pending = f.journal.entries()
                assertTrue(pending.any { it.operation == "submit" && it.id == submit.id })
                assertTrue(pending.single { it.operation == "dismiss" }.notRemovable)
                f.hideSnapshotRow = false
                val recovered = f.connection(); second = recovered
                recovered.select(true); recovered.start()
                val refreshed = withTimeout(10_000) { recovered.state.first { it.replica?.messages?.get(submit.id)?.state == MessageProtocol.State.ACCEPTED && it.unresolved.isEmpty() } }
                assertTrue(refreshed.history.managedMessages(refreshed.replica, refreshed.unresolved).any { it.text == "accepted work" })
                assertEquals(1, f.dismissCalls)
                assertEquals(0, f.submitCalls)
                assertEquals(MessageActionResult.Applied, withTimeout(10_000) { recovered.resetConversation().await() })
            } finally { first.close(); second?.close() }
        }
    }

    @Test fun unknownRemoveSurvivesGrowingHeadThenReset() = runBlocking {
        for (provider in listOf("claude", "codex")) {
            val f = Fixture(provider)
            val submit = f.journal.create(f.account, f.wire, "submit", text = "repair", epoch = 1)
            f.journal.markIssued(submit.id)
            val owner = f.connection()
            try {
                owner.select(true); owner.start(); f.ready(owner)
                f.historyPause = CompletableDeferred()
                owner.nearTop(true)
                withTimeout(10_000) { f.historyPaused.await() }
                f.row = f.row("queued").put("id", submit.id).put("text", submit.text)
                f.growingEvents = true
                val remove = async(start = CoroutineStart.UNDISPATCHED) { owner.removeUnknown(submit.id) }
                f.historyPause!!.complete(Unit)
                assertEquals(MessageActionResult.Applied, withTimeout(10_000) { remove.await() })
                assertTrue(f.eventCalls > 1)
                assertEquals(1, f.dismissCalls)
                assertEquals(0, f.submitCalls)
                assertEquals("cancelled", f.row!!.getString("state"))
                assertEquals(MessageActionResult.Applied, withTimeout(10_000) { owner.resetConversation().await() })
                assertTrue(f.journal.entries().isEmpty())
            } finally { f.historyPause?.complete(Unit); owner.close() }
        }
    }

    @Test fun unknownRemoveNavigationAndTakeoverBeforeAdmissionPreserveJournal() = runBlocking {
        for (provider in listOf("claude", "codex")) for (takeover in listOf(false, true)) {
            val f = Fixture(provider)
            val submit = f.journal.create(f.account, f.wire, "submit", text = "preserve", epoch = 1)
            f.journal.markIssued(submit.id)
            val old = f.connection()
            var current: ChatHistoryConnection? = null
            try {
                old.select(true); old.start(); f.ready(old)
                val before = f.prefs.all.toMap()
                f.admissionPause = CompletableDeferred()
                val remove = async { old.removeUnknown(submit.id) }
                withTimeout(10_000) { f.admissionStarted.await() }
                if (takeover) {
                    val next = f.connection(); current = next
                    next.select(true); next.start(); f.ready(next)
                } else old.select(false)
                f.admissionPause!!.complete(Unit)
                assertTrue(withTimeout(10_000) { remove.await() } is MessageActionResult.Rejected)
                assertEquals(before, f.prefs.all)
                assertEquals(0, f.dismissCalls)
                assertEquals(0, f.submitCalls)
            } finally { f.admissionPause?.complete(Unit); old.close(); current?.close() }
        }
    }

    @Test fun oldKnownDismissRecoversAcrossResetWithoutChangingAcceptedWork() = runBlocking {
        for (provider in listOf("claude", "codex")) {
            val f = Fixture(provider)
            val entry = f.journal.create(f.account, f.wire.copy(tmux = "old-alias"), "dismiss", messageId, epoch = 0, attempt = "old")
            f.journal.markIssued(entry.id)
            f.epoch = 1
            f.row = f.row("accepted", epoch = 0)
            f.nativeRows = listOf(JSONObject().put("role", "user").put("text", "native accepted").put("id", "native").put("offset", 1).put("end", 2))
            val owner = f.connection()
            try {
                owner.select(true); owner.start()
                val ready = withTimeout(10_000) { owner.state.first {
                    it.replica != null && it.history.sid == f.sid &&
                        it.history.managedMessages(it.replica, it.unresolved).any { message -> message.text == "native accepted" }
                } }
                assertTrue(ready.history.managedMessages(ready.replica, ready.unresolved).any { it.text == "native accepted" })
                assertEquals(MessageActionResult.BlockedRecovery, withTimeout(10_000) { owner.resetConversation().await() })
                assertEquals(MessageActionResult.Applied, withTimeout(10_000) { owner.replayUnknown(entry.id) })
                assertEquals(messageId, f.lastDismiss!!.getString("message_id"))
                assertFalse(f.lastDismiss!!.has("text"))
                assertEquals("chat", f.lastDismiss!!.getString("tmux"))
                assertEquals("accepted", f.row!!.getString("state"))
                assertTrue(f.journal.entries().isEmpty())
                assertEquals(0, f.submitCalls); assertEquals(0, f.retryCalls)
                val after = withTimeout(10_000) { owner.state.first {
                    it.replica?.messages?.get(messageId)?.state == MessageProtocol.State.ACCEPTED && it.unresolved.isEmpty() &&
                        it.history.managedMessages(it.replica, it.unresolved).any { message -> message.text == "native accepted" }
                } }
                assertTrue(after.history.managedMessages(after.replica, after.unresolved).any { it.text == "native accepted" })
                assertEquals(MessageActionResult.Applied, withTimeout(10_000) { owner.resetConversation().await() })
                assertEquals("accepted", f.row!!.getString("state"))
            } finally { owner.close() }
        }
    }

    @Test fun reissuedNotRemovableDismissLosingAckRemainsRecoverable() = runBlocking {
        for (provider in listOf("claude", "codex")) {
            val f = Fixture(provider)
            val submit = f.journal.create(f.account, f.wire.copy(tmux = "old-alias"), "submit", text = "reissue", epoch = 1)
            f.journal.markIssued(submit.id)
            val dismiss = f.journal.create(f.account, f.wire, "dismiss", submit.id, submit.text, 1)
            f.journal.markIssued(dismiss.id)
            f.journal.markNotRemovable(dismiss.id)
            f.hideSnapshotRow = true
            f.row = f.row("failed").put("id", submit.id).put("text", submit.text)
            f.dropDismissAfterAdmission = true
            val first = f.connection()
            var second: ChatHistoryConnection? = null
            try {
                first.select(true); first.start(); f.ready(first)
                assertEquals(MessageActionResult.Unknown, withTimeout(10_000) { first.removeUnknown(dismiss.id) })
                first.close()
                val saved = f.journal.entries().single { it.id == dismiss.id }
                assertFalse(saved.notRemovable)
                assertTrue(saved.issued)
                assertTrue(f.journal.entries().any { it.id == submit.id })
                assertEquals("cancelled", f.row!!.getString("state"))
                f.hideSnapshotRow = false
                val recovered = f.connection(); second = recovered
                recovered.select(true); recovered.start(); f.ready(recovered)
                withTimeout(10_000) { recovered.state.first { it.unresolved.isEmpty() } }
                assertEquals(1, f.dismissCalls); assertEquals(0, f.submitCalls); assertEquals(0, f.retryCalls)
                assertEquals(MessageActionResult.Applied, withTimeout(10_000) { recovered.resetConversation().await() })
            } finally { first.close(); second?.close() }
        }
    }

    @Test fun snapshotPreemptionChecksUnseenConflictingBodyBeforeDismiss() = runBlocking {
        for (provider in listOf("claude", "codex")) for (state in listOf("queued", "failed")) for (later in listOf(false, true)) {
            val f = Fixture(provider)
            val submit = f.journal.create(f.account, f.wire.copy(tmux = "old-alias"), "submit", text = "original", epoch = 1)
            f.journal.markIssued(submit.id)
            f.row = f.row(state).put("id", submit.id).put("text", "different")
            f.paged = true
            f.targetOnLaterPage = later
            f.pauseSnapshot = CompletableDeferred()
            f.pauseSecondSnapshot = CompletableDeferred()
            f.blockLaterPage = true
            val raw = f.prefs.all.toMap()
            val owner = f.connection()
            try {
                owner.select(true); owner.start()
                withTimeout(10_000) { f.snapshotStarted.await() }
                val remove = async(start = CoroutineStart.UNDISPATCHED) { owner.removeUnknown(submit.id) }
                f.pauseSnapshot!!.complete(Unit)
                assertEquals(MessageActionResult.Rejected("Message changed"), withTimeout(10_000) { remove.await() })
                withTimeout(10_000) { f.secondSnapshotStarted.await() }
                assertNull(owner.state.value.replica)
                assertFalse(f.laterPageRequested.isCompleted)
                assertEquals(state, f.row!!.getString("state"))
                assertEquals("different", f.row!!.getString("text"))
                assertEquals(raw, f.prefs.all)
                assertEquals(submit.id, f.lastStatus!!.getString("message_id"))
                assertEquals("chat", f.lastStatus!!.getString("tmux"))
                assertEquals(0, f.dismissCalls); assertEquals(0, f.submitCalls); assertEquals(0, f.retryCalls)
            } finally { f.pauseSecondSnapshot?.complete(Unit); f.laterPageRelease.complete(Unit); owner.close() }
        }
    }

    @Test fun navigationDuringCancelStatusDoesNotAdmitOrMutate() = runBlocking {
        for (provider in listOf("claude", "codex")) for (takeover in listOf(false, true)) {
            val f = Fixture(provider)
            val submit = f.journal.create(f.account, f.wire.copy(tmux = "old-alias"), "submit", text = "status race", epoch = 1)
            f.journal.markIssued(submit.id)
            val owner = f.connection()
            var current: ChatHistoryConnection? = null
            try {
                owner.select(true); owner.start(); f.ready(owner)
                val raw = f.prefs.all.toMap()
                f.statusPause = CompletableDeferred()
                val remove = async { owner.removeUnknown(submit.id) }
                withTimeout(10_000) { f.statusStarted.await() }
                if (takeover) {
                    val next = f.connection(); current = next
                    next.select(true); next.start()
                    withTimeout(10_000) { f.secondSnapshotStarted.await() }
                } else owner.select(false)
                f.statusPause!!.complete(Unit)
                assertTrue(withTimeout(10_000) { remove.await() } is MessageActionResult.Rejected)
                assertEquals(raw, f.prefs.all)
                assertEquals(0, f.dismissCalls); assertEquals(0, f.submitCalls); assertEquals(0, f.retryCalls)
            } finally { f.statusPause?.complete(Unit); owner.close(); current?.close() }
        }
    }

    @Test fun unknownRemovePreemptsSnapshotWithoutPublishingPartialRows() = runBlocking {
        val f = Fixture("claude")
        val submit = f.journal.create(f.account, f.wire, "submit", text = "snapshot", epoch = 1)
        f.journal.markIssued(submit.id)
        f.row = f.row("queued").put("id", submit.id).put("text", submit.text)
        f.paged = true
        f.pauseSnapshot = CompletableDeferred()
        f.blockLaterPage = true
        val owner = f.connection()
        try {
            owner.select(true); owner.start()
            withTimeout(10_000) { f.snapshotStarted.await() }
            val removal = async(start = CoroutineStart.UNDISPATCHED) { owner.removeUnknown(submit.id) }
            assertNull(owner.state.value.replica)
            f.pauseSnapshot!!.complete(Unit)
            withTimeout(10_000) { f.dismissStarted.await() }
            assertFalse(f.laterPageRequested.isCompleted)
            assertEquals(MessageActionResult.Applied, withTimeout(10_000) { removal.await() })
            assertNull(owner.state.value.replica)
            assertTrue(f.journal.entries().isEmpty())
        } finally { f.laterPageRelease.complete(Unit); owner.close() }
    }

    @Test fun bothProvidersAdmitAndResetThroughProductionOwner() = runBlocking {
        for (provider in listOf("claude", "codex")) {
            val f = Fixture(provider)
            val owner = f.connection()
            try {
                owner.select(true); owner.start(); f.ready(owner)
                val admission = withTimeout(10_000) { owner.submit("hello") }
                assertTrue(admission is PromptAdmission.Recorded)
                val id = (admission as PromptAdmission.Recorded).messageId
                val queued = withTimeout(10_000) {
                    owner.state.first { it.replica?.messages?.get(id)?.state == MessageProtocol.State.QUEUED && it.unresolved.isEmpty() }
                }
                assertTrue(queued.history.managedMessages(queued.replica, queued.unresolved).any { it.requestId == id })
                assertEquals(MessageActionResult.Applied, withTimeout(10_000) { owner.resetConversation().await() })
                val reset = withTimeout(10_000) { owner.state.first { it.replica?.epoch == 2L } }
                assertEquals(MessageProtocol.State.CANCELLED, reset.replica?.messages?.get(id)?.state)
                assertFalse(reset.history.managedMessages(reset.replica, reset.unresolved).any { it.requestId == id })
                assertEquals(1, f.submitCalls)
                assertEquals(1, f.resetCalls)
            } finally { owner.close() }
        }
    }

}

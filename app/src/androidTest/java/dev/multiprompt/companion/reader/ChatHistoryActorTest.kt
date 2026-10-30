package dev.multiprompt.companion.reader

import androidx.test.platform.app.InstrumentationRegistry
import dev.multiprompt.companion.MultipromptApplication
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.model.HostProfile
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

    private inner class Fixture(val provider: String) {
        val host = HostProfile(UUID.randomUUID().toString(), "test", "host", username = "user")
        val identity = ChatHistoryProtocol.Identity(if (provider == "claude") AgentKind.CLAUDE else AgentKind.CODEX, "chat")
        val account = listOf(host.id, host.hostname, host.port.toString(), host.username, "", "", "").joinToString("\u0000")
        val prefs = context.getSharedPreferences(UUID.randomUUID().toString(), 0)
        val journal = MessageJournal(prefs)
        val gate = MessageGate()
        var sid = "sid"
        var epoch = 1L
        var row: JSONObject? = null
        var retryCalls = 0
        var lastRetryId: String? = null
        var submitCalls = 0
        var resetCalls = 0
        var dropSubmit = false
        var dropAfterAdmission = false
        var dropReset = false
        var paged = false
        var pauseSnapshot: CompletableDeferred<Unit>? = null
        val snapshotStarted = CompletableDeferred<Unit>()
        val secondSnapshotStarted = CompletableDeferred<Unit>()
        var pauseSecondSnapshot: CompletableDeferred<Unit>? = null
        var snapshotCount = 0
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
                "engine_info" -> JSONObject().put("id", id).put("engine_info", JSONObject().put("protocol", 1).put("generation", 17).put("sha256", "a".repeat(64))).put("message_protocols", JSONArray().put(1)).toString()
                "message_snapshot" -> {
                    val number = ++snapshotCount
                    val cursor = request.optLong("cursor", 0)
                    val items = JSONArray()
                    if (cursor == 0L) row?.let { items.put(JSONObject().put("message", JSONObject(it.toString()))) }
                    val next = if (paged && cursor == 0L) 1 else JSONObject.NULL
                    val response = result(JSONObject().put("snapshot_id", "snapshot").put("epoch", epoch).put("seq", 0).put("items", items).put("next_cursor", next))
                    val pause = if (number == 2 && pauseSecondSnapshot != null) pauseSecondSnapshot else pauseSnapshot
                    if (number == 1) snapshotStarted.complete(Unit)
                    if (number == 2) secondSnapshotStarted.complete(Unit)
                    pause?.await()
                    response
                }
                "message_events" -> result(JSONObject().put("epoch", epoch).put("seq", 0).put("events", JSONArray()).put("next_after", 0))
                "message_status" -> result(JSONObject().put("epoch", epoch).put("seq", 0).put("message", JSONObject.NULL))
                "message_submit" -> {
                    assertEquals(provider, request.getString("provider"))
                    assertEquals(sid, request.getString("sid"))
                    submitCalls++
                    if (dropSubmit) error("lost acknowledgement")
                    row = row("queued").put("id", request.getString("message_id")).put("text", request.getString("text"))
                    if (dropAfterAdmission) {
                        dropAfterAdmission = false
                        error("lost acknowledgement after admission")
                    }
                    result(JSONObject().put("epoch", epoch).put("seq", 0).put("message", row))
                }
                "message_reset" -> {
                    assertEquals(provider, request.getString("provider"))
                    assertEquals(sid, request.getString("sid"))
                    resetCalls++
                    if (dropReset) error("lost reset acknowledgement")
                    epoch++
                    paged = false
                    row?.put("state", "cancelled")
                    result(JSONObject().put("epoch", epoch).put("seq", 0))
                }
                "message_retry" -> {
                    retryCalls++
                    lastRetryId = request.getString("retry_id")
                    result(JSONObject().put("epoch", epoch).put("seq", 0).put("message", row))
                }
                else -> {
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
        val owner = f.connection()
        try {
            owner.select(true); owner.start()
            withTimeout(10_000) { f.snapshotStarted.await() }
            val reset = owner.resetConversation()
            assertNull(owner.state.value.replica)
            f.pauseSnapshot!!.complete(Unit)
            assertEquals(MessageActionResult.Applied, withTimeout(10_000) { reset.await() })
            assertEquals(2L, withTimeout(10_000) { owner.state.first { it.replica?.epoch == 2L } }.replica?.epoch)
            assertTrue(f.journal.entries().isEmpty())
        } finally { owner.close() }
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

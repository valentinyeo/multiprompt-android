package dev.multiprompt.companion.reader

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class MessageProtocolTest {
    private val scope = MessageProtocol.Scope("codex", "sid", "chat")
    private val id = "1234567890123456"
    private fun row(state: String = "queued") = JSONObject("""{"id":"$id","provider":"codex","sid":"sid","text":"hello","position":1,"attempt":"attempt","state":"$state","deadline":null,"receipt":"not_sent","epoch":1,"created":1.0}""")
    private fun envelope(result: JSONObject) = JSONObject().put("id", 1).put("message_protocol", 1).put("result", result).toString()

    @Test fun engineRequiresPublishedGenerationAndAcceptsNewerCompatibleRuntime() {
        fun engine(generation: Int, protocols: JSONArray = JSONArray().put(1)) = JSONObject()
            .put("id", 1).put("engine_info", JSONObject().put("protocol", 1).put("generation", generation).put("sha256", "a".repeat(64)))
            .put("message_protocols", protocols).toString()
        assertTrue(runCatching { MessageProtocol.engine(engine(19), 1) }.isFailure)
        assertTrue(runCatching { MessageProtocol.engine(engine(20), 1) }.isFailure)
        assertTrue(runCatching { MessageProtocol.engine(engine(21), 1) }.isFailure)
        assertEquals(22L, MessageProtocol.engine(engine(22), 1).first)
        assertEquals(23L, MessageProtocol.engine(engine(23), 1).first)
        assertTrue(runCatching { MessageProtocol.engine(engine(22, JSONArray().put(2)), 1) }.isFailure)
    }

    @Test fun correlatesHostRowAndOpaqueReceipt() {
        val reply = envelope(JSONObject().put("epoch", 1).put("seq", 4).put("message", row("failed")))
        val result = MessageProtocol.mutation(reply, 1, scope, id, "hello")
        assertEquals(MessageProtocol.State.FAILED, result.row?.state)
        assertEquals("not_sent", result.row?.receipt)
        assertTrue(runCatching { MessageProtocol.mutation(reply, 2, scope, id) }.isFailure)
        assertTrue(runCatching { MessageProtocol.mutation(reply, 1, scope, id, "different") }.isFailure)
    }

    @Test fun pagesMaterializeBeforeEventsAndUseNextAfter() {
        val first = MessageProtocol.SnapshotPage("snap", 1, 3, listOf(MessageProtocol.Item.Message(
            MessageProtocol.mutation(envelope(JSONObject().put("epoch", 1).put("seq", 3).put("message", row())), 1, scope, id).row!!)), 1)
        val second = MessageProtocol.SnapshotPage("snap", 1, 3, emptyList(), null)
        val replica = MessageReplica.snapshot(listOf(first, second))
        val result = JSONObject("""{"epoch":1,"seq":100,"events":[{"seq":4,"event":${row("accepted")}}],"next_after":4}""")
        val updated = replica.apply(MessageProtocol.events(envelope(result), 1, scope, 3))
        assertEquals(4L, updated.cursor)
        assertEquals(MessageProtocol.State.ACCEPTED, updated.messages[id]?.state)
        result.put("next_after", 100)
        assertTrue(runCatching { MessageProtocol.events(envelope(result), 1, scope, 3) }.isFailure)
        assertEquals(3L, replica.cursor)
    }

    @Test fun journalSurvivesRecreationAndRetainsIdentity() {
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("message-journal-test", 0)
        prefs.edit().clear().commit()
        val submitted = MessageJournal(prefs).create("host:user", scope, "submit", text = "hello")
        val restored = MessageJournal(prefs).entries().single()
        assertEquals(submitted, restored)
        assertEquals(submitted.id, restored.messageId)
        MessageJournal(prefs).remove(submitted.id)
        assertTrue(MessageJournal(prefs).entries().isEmpty())
    }

    @Test fun dismissJournalRoundTripsOldAndUnknownForms() {
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("message-dismiss-test", 0)
        prefs.edit().clear().commit()
        val journal = MessageJournal(prefs)
        val old = journal.create("host:user", scope, "dismiss", id, epoch = 1, attempt = "attempt")
        val unknown = journal.create("host:user", scope, "dismiss", "other123456789012", text = "original", epoch = 1)
        assertEquals(setOf(old, unknown), MessageJournal(prefs).entries().toSet())
        assertNull(old.text)
        assertNull(unknown.attempt)
        assertTrue(runCatching { journal.create("host:user", scope, "dismiss", id, text = "bad\u0000text") }.isFailure)
        journal.remove(old.id); journal.remove(unknown.id)
    }

    @Test fun dismissErrorMustBeCorrelated() {
        val wire = "@mp\t1\thistory\terror\t-\t0\n${TranscriptProtocol.END}\n"
        val reply = JSONObject().put("id", 1).put("wire", wire).toString()
        assertTrue(MessageProtocol.rejectedMutation(reply, 1))
        assertTrue(runCatching { MessageProtocol.rejectedMutation(reply, 2) }.isFailure)
        assertFalse(MessageProtocol.rejectedMutation(JSONObject().put("id", 1).put("wire", "error").toString(), 1))
    }

    @Test fun rejectsNullMutationAndLegacyError() {
        val nullRow = envelope(JSONObject().put("epoch", 1).put("seq", 1).put("message", JSONObject.NULL))
        assertTrue(runCatching { MessageProtocol.requiredMutation(nullRow, 1, scope, id) }.isFailure)
        assertNull(MessageProtocol.mutation(nullRow, 1, scope, id).row)
        assertTrue(runCatching { MessageProtocol.mutation("""{"id":1,"wire":"error"}""", 1, scope, id) }.isFailure)
    }

    @Test fun snapshotMustBeCompleteAndFrozen() {
        val items = List(129) { index -> MessageProtocol.Item.Message(MessageProtocol.Row(
            "message%09d".format(index), "codex", "sid", "same", index + 1L, "attempt", MessageProtocol.State.QUEUED, null, null, 5, 1.0)) }
        val first = MessageProtocol.SnapshotPage("snapshot", 5, 10, items.take(128), 128)
        val last = MessageProtocol.SnapshotPage("snapshot", 5, 10, items.drop(128), null)
        assertEquals(129, MessageReplica.snapshot(listOf(first, last)).messages.size)
        assertTrue(runCatching { MessageReplica.snapshot(listOf(first)) }.isFailure)
        assertTrue(runCatching { MessageReplica.snapshot(listOf(first, last.copy(epoch = 6))) }.isFailure)
    }

    @Test fun retryJournalKeepsObservedAttemptAcrossRestart() {
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("message-retry-test", 0)
        prefs.edit().clear().commit()
        val entry = MessageJournal(prefs).create("host:user", scope, "retry", id, epoch = 9, attempt = "old-attempt")
        assertEquals(entry, MessageJournal(prefs).entries().single())
        assertEquals(9L, entry.epoch!!)
        assertEquals("old-attempt", entry.attempt)
        MessageJournal(prefs).remove(entry.id)
    }

    @Test fun resetAndEpochChangeNeverAdvanceReplica() {
        val replica = MessageReplica(4, 7)
        val changed = MessageProtocol.Events(5, 8, listOf(MessageProtocol.Event(8, MessageProtocol.Item.Reset(5))), 8)
        assertTrue(runCatching { replica.apply(changed) }.isFailure)
        assertEquals(7L, replica.cursor)
    }

    @Test fun acceptedCopyDoesNotDisappearForIdenticalHistoryText() {
        val row = MessageProtocol.Row(id, "codex", "sid", "hello", 1, "attempt", MessageProtocol.State.ACCEPTED, null, null, 1, 1.0)
        val history = ChatHistory(sid = "sid", inode = 3, offset = 20, rows = listOf(
            ChatHistoryProtocol.Row("user", "hello", "one", "", 1, 2),
            ChatHistoryProtocol.Row("user", "hello", "two", "", 3, 4),
        ))
        val messages = history.managedMessages(MessageReplica(1, 2, mapOf(id to row)), emptyList())
        assertEquals(3, messages.size)
        assertEquals("Accepted", messages.last().delivery)
        assertEquals(2, messages.map { it.key }.take(2).distinct().size)
    }

    @Test fun eventPagesAdvanceOnlyThroughMaterializedEvents() {
        val events = JSONArray()
        for (seq in 1..256) events.put(JSONObject().put("seq", seq).put("event", row()))
        val first = JSONObject().put("epoch", 1).put("seq", 257).put("events", events).put("next_after", 256)
        val initial = MessageReplica(1, 0)
        val afterFirst = initial.apply(MessageProtocol.events(envelope(first), 1, scope, 0))
        assertEquals(256L, afterFirst.cursor)
        val second = JSONObject().put("epoch", 1).put("seq", 257)
            .put("events", JSONArray().put(JSONObject().put("seq", 257).put("event", row("accepted"))))
            .put("next_after", 257)
        val afterSecond = afterFirst.apply(MessageProtocol.events(envelope(second), 1, scope, 256))
        assertEquals(257L, afterSecond.cursor)
        assertEquals(MessageProtocol.State.ACCEPTED, afterSecond.messages[id]?.state)
        second.put("events", JSONArray().put(JSONObject().put("seq", 257).put("event", JSONObject().put("garbage", true))))
        assertTrue(runCatching { MessageProtocol.events(envelope(second), 1, scope, 256) }.isFailure)
        assertEquals(256L, afterFirst.cursor)
    }

    @Test fun navigationTakeoverInvalidatesPreviousOwnerWithoutReplacingMutex() {
        val gate = MessageGate()
        val old = Any()
        val current = Any()
        val lock = gate.claim("account:codex:sid", old)
        assertTrue(gate.owns("account:codex:sid", old))
        assertSame(lock, gate.claim("account:codex:sid", current))
        assertFalse(gate.owns("account:codex:sid", old))
        gate.release("account:codex:sid", old)
        assertTrue(gate.owns("account:codex:sid", current))
        assertSame(lock, gate.claimIfOwned("account:codex:sid", current))
    }

    @Test fun resetIdentityRemainsStableAcrossJournalRecreation() {
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("message-reset-test", 0)
        prefs.edit().clear().commit()
        val entry = MessageJournal(prefs).create("host:user", scope, "reset", epoch = 17)
        val restored = MessageJournal(prefs).entries().single()
        assertEquals(entry.id, restored.id)
        assertEquals(17L, restored.epoch!!)
        MessageJournal(prefs).remove(restored.id)
    }

    @Test fun malformedNativeFactCannotAdvanceCursor() {
        val initial = MessageReplica(1, 3)
        val event = JSONObject().put("seq", 4).put("event", JSONObject().put("native_message",
            JSONObject().put("sid", "different").put("event_id", "event").put("inode", 1).put("offset", 1).put("end", 2).put("row", JSONObject())))
        val result = JSONObject().put("epoch", 1).put("seq", 4).put("events", JSONArray().put(event)).put("next_after", 4)
        assertTrue(runCatching { MessageProtocol.events(envelope(result), 1, scope, 3) }.isFailure)
        assertEquals(3L, initial.cursor)
    }
    @Test fun malformedSameSidNativeRowsCannotAdvanceCursor() {
        val initial = MessageReplica(1, 3)
        val valid = JSONObject().put("role", "assistant").put("text", "hello").put("offset", 1).put("end", 2)
        val badRows = listOf(JSONObject(), JSONObject(valid.toString()).put("offset", "1"),
            JSONObject(valid.toString()).put("end", 2.5), JSONObject(valid.toString()).put("end", 3),
            JSONObject(valid.toString()).put("id", 7), JSONObject(valid.toString()).put("id", JSONObject()),
            JSONObject(valid.toString()).put("read_id", 7), JSONObject(valid.toString()).put("read_id", JSONObject()),
            JSONObject(valid.toString()).put("segment", "0"), JSONObject(valid.toString()).put("segment", 0.5))
        for (bad in badRows) {
            val fact = JSONObject().put("sid", "sid").put("event_id", "event").put("inode", 1)
                .put("offset", 1).put("end", 2).put("row", bad)
            val item = JSONObject().put("native_message", fact)
            val event = JSONObject().put("seq", 4).put("event", item)
            val result = JSONObject().put("epoch", 1).put("seq", 4).put("events", JSONArray().put(event)).put("next_after", 4)
            assertTrue(runCatching { initial.apply(MessageProtocol.events(envelope(result), 1, scope, 3)) }.isFailure)
            assertEquals(3L, initial.cursor)
        }
        val fact = JSONObject().put("sid", "sid").put("event_id", "event").put("inode", 1)
            .put("offset", 1).put("end", 2).put("row", valid)
        val ambiguous = JSONObject().put("native_message", fact).put("message", row())
        val event = JSONObject().put("seq", 4).put("event", ambiguous)
        val result = JSONObject().put("epoch", 1).put("seq", 4).put("events", JSONArray().put(event)).put("next_after", 4)
        assertTrue(runCatching { MessageProtocol.events(envelope(result), 1, scope, 3) }.isFailure)
        ambiguous.remove("message")
        valid.put("id", "native").put("read_id", "read").put("segment", 0)
        fact.put("row", valid)
        assertEquals(4L, initial.apply(MessageProtocol.events(envelope(result), 1, scope, 3)).cursor)
    }

    @Test fun cancelledCopiesStayInReplicaButDisappearFromChat() {
        val cancelled = MessageProtocol.Row(id, "codex", "sid", "hello", 1, "attempt", MessageProtocol.State.CANCELLED, null, null, 1, 1.0)
        val replica = MessageReplica(1, 2, mapOf(id to cancelled))
        assertTrue(ChatHistory(sid = "sid").managedMessages(replica, emptyList()).isEmpty())
        assertEquals(cancelled, replica.messages[id])
    }

    @Test fun nativePresentationGroupsOnlyMatchingReadAndSegment() {
        val history = ChatHistory(sid = "sid", inode = 3, offset = 10, rows = listOf(
            ChatHistoryProtocol.Row("assistant", "Hello", "native@1", "", 1, 2, normalized = """{"read_id":"r","segment":0}"""),
            ChatHistoryProtocol.Row("assistant", "Hello world", "native@2", "", 2, 3, normalized = """{"read_id":"r","segment":0}"""),
            ChatHistoryProtocol.Row("assistant", "More", "native@3", "", 3, 4, normalized = """{"read_id":"r","segment":1}"""),
            ChatHistoryProtocol.Row("assistant", "Other", "native@4", "", 4, 5, normalized = """{"read_id":"other","segment":0}"""),
            ChatHistoryProtocol.Row("assistant", "continued", "native@5", "", 5, 6, normalized = """{"read_id":"r","segment":0}""")
        ))
        assertEquals(listOf("Hello world\ncontinued", "More", "Other"), history.managedMessages(null, emptyList()).map { it.text })
        assertEquals(5, history.rows.size)
    }

    @Test fun readIdentityMergesContinuationButKeepsOtherSegment() {
        val history = ChatHistory(sid = "sid", inode = 3, offset = 10, rows = listOf(
            ChatHistoryProtocol.Row("assistant", "First", "native@10", "", 1, 2, normalized = """{"read_id":"stable","segment":0}"""),
            ChatHistoryProtocol.Row("assistant", "Second", "native@20", "", 2, 3, normalized = """{"read_id":"stable","segment":0}"""),
            ChatHistoryProtocol.Row("assistant", "Separate", "native@30", "", 3, 4, normalized = """{"read_id":"stable","segment":1}""")
        ))
        assertEquals(listOf("First\nSecond", "Separate"), history.managedMessages(null, emptyList()).map { it.text })
        assertEquals(listOf("native@10", "native@20", "native@30"), history.rows.map { it.id })
    }

}

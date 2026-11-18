package dev.multiprompt.companion.reader

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MessageProjectionTest {
    private val sid = "projection-proof"
    private val created = java.time.Instant.parse("2026-11-13T10:00:00Z").epochSecond.toDouble()
    private fun id(n: Int) = "mp-projection00000$n"
    private fun row(n: Int, provider: String = "claude") = MessageProtocol.Row(id(n), provider, sid, "same", n.toLong(),
        "attempt0123456789", MessageProtocol.State.ACCEPTED, null, "receipt-$n", 1, created + n)

    private fun user(n: Int, native: String = "u-$n", text: String = "same") =
        ChatHistoryProtocol.Row("user", text, native, "2026-11-13T10:00:10Z", n * 100L, n * 100L + 40)

    private fun reply(n: Int) = ChatHistoryProtocol.Row("assistant", "answer $n", "msg_$n", "", n * 100L + 50, n * 100L + 90)

    private fun host(n: Int, provider: String = "claude", fresh: Boolean = false): List<MessageProtocol.Item> {
        val scope = MessageProtocol.Scope(provider, sid, "verify-session")
        val start = JSONObject().put("kind", "turn.started").put("sid", sid).put("turn", "t-$n")
            .put("payload", JSONObject().put("message_id", id(n)).also { if (!fresh) it.put("uuids", JSONArray(listOf("u-$n"))) })
        val done = JSONObject().put("kind", "text.done").put("sid", sid).put("turn", "t-$n").put("item", "msg_$n:text:0")
            .put("payload", JSONObject().put("text", "answer $n"))
        val envelope = JSONObject().put("id", 1).put("message_protocol", 1).put("result", JSONObject()
            .put("snapshot_id", "proof").put("epoch", 1).put("seq", 1).put("next_cursor", JSONObject.NULL)
            .put("items", JSONArray(listOf(start, done))))
        return MessageProtocol.snapshot(envelope.toString(), 1, scope).items
    }

    private fun replica(cards: List<MessageProtocol.Row>, items: List<MessageProtocol.Item>) =
        MessageReplica.snapshot(listOf(MessageProtocol.SnapshotPage("proof", 1, 1, cards.map { MessageProtocol.Item.Message(it) } + items, null)))

    private fun once(messages: List<ChatHistoryMessage>, expected: List<String>) {
        assertEquals(expected, messages.map { it.text })
        val counts = messages.groupingBy { it.key }.eachCount()
        assertFalse("MESSAGE_PROJECTION_DUPLICATE: reconcile identities in ChatHistory; do not append an independent echo. $counts", counts.any { it.value > 1 })
        val phones = messages.filter { it.role == "user" && it.requestId.isNotEmpty() }.groupingBy { it.requestId }.eachCount()
        assertFalse("MESSAGE_PROJECTION_DUPLICATE: phone message produced two visible rows. Reconcile it in ChatHistory; do not append an independent echo. $phones", phones.any { it.value > 1 })
    }

    @Test fun archivedRepliesStayLastWhenOlderAcceptedMessagesAreOutsideTheHistoryTail() {
        val old = row(1).copy(text = "old incoming")
        val current = row(2).copy(text = "current question")
        val history = ChatHistory(sid = sid, inode = 8, rows = listOf(
            user(2, current.id, current.text).copy(timestamp = "2026-11-13T11:00:02.123456"), reply(2),
        ))
        once(history.projectMessages(replica(listOf(old, current), emptyList())), listOf("current question", "answer 2"))
    }

    @Test fun carriedRepliesOutsideTheArchiveTailDoNotReappearAfterTheNewestReply() {
        val old = row(1)
        val current = row(2)
        val history = ChatHistory(sid = sid, inode = 8, rows = listOf(
            user(2, current.id).copy(timestamp = "2026-11-13T11:00:02.123456"), reply(2),
        ))
        once(history.projectMessages(replica(listOf(old, current), host(1))), listOf("same", "answer 2"))
    }

    @Test fun newerAcceptedMessagesRemainVisibleUntilTheirHistoryArrives() {
        val current = row(1).copy(text = "current question")
        val newer = row(2).copy(text = "new incoming")
        val history = ChatHistory(sid = sid, inode = 8, rows = listOf(
            user(1, current.id, current.text).copy(timestamp = "2026-11-13T11:00:02.123456"), reply(1),
        ))
        once(history.projectMessages(replica(listOf(current, newer), emptyList())), listOf("current question", "answer 1", "new incoming"))
    }

    @Test fun waitingPromptsBecomeJournalAndHostRowsWithoutAnIndependentEcho() {
        for (provider in listOf("claude", "codex", "pi", "hax", "cursor")) {
            val waiting = listOf(PendingPrompts.Entry(id(1), "same"), PendingPrompts.Entry(id(2), "same"))
            val empty = ChatHistory(sid = sid, inode = 7)
            val before = empty.projectMessages(waiting = waiting + waiting.first())
            once(before, listOf("same", "same"))
            assertEquals(waiting.map { "@shared:${it.id}" }, before.map { it.key })
            assertEquals(listOf(PendingPrompts.WAITING_LABEL, PendingPrompts.WAITING_LABEL), before.map { it.delivery })
            val journal = MessageJournal.Entry(id(1), "account", MessageProtocol.Scope(provider, sid, "verify-session"), "submit", id(1), "same")
            val issuing = listOf(waiting.first().copy(issuing = true), waiting.last())
            val recorded = empty.projectMessages(unresolved = listOf(journal), waiting = issuing)
            once(recorded, listOf("same", "same"))
            assertEquals(before.map { it.key }, recorded.map { it.key })
            val queued = replica(listOf(row(1, provider).copy(state = MessageProtocol.State.QUEUED)), emptyList())
            val admitted = empty.projectMessages(queued, listOf(journal), waiting = issuing)
            once(admitted, listOf("same", "same"))
            assertEquals(before.map { it.key }, admitted.map { it.key })
            assertEquals(null, admitted.first().delivery)
            val accepted = replica(listOf(row(1, provider)), host(1, provider))
            val landed = empty.copy(rows = listOf(user(1), reply(1)))
            val caughtUp = landed.projectMessages(accepted, listOf(journal), waiting = issuing)
            once(caughtUp, listOf("same", "answer 1", "same"))
            assertEquals(before.map { it.key }, caughtUp.filter { it.role == "user" }.map { it.key })
            val afterLocalRemoval = landed.projectMessages(accepted, waiting = waiting.drop(1))
            assertEquals(caughtUp, afterLocalRemoval)
        }
    }

    @Test fun aTranscriptReceiptCanReplaceTheWaitingRowBeforeTheHostSnapshotArrives() {
        val waiting = listOf(PendingPrompts.Entry(id(1), "same"), PendingPrompts.Entry(id(2), "same"))
        val history = ChatHistory(sid = sid, inode = 7, rows = listOf(user(1, id(1)), reply(1)))
        val projected = history.projectMessages(waiting = waiting)
        once(projected, listOf("same", "answer 1", "same"))
        assertEquals(listOf("@shared:${id(1)}", "@shared:${id(2)}"), projected.filter { it.role == "user" }.map { it.key })
        assertEquals(null, projected.first().delivery)
        assertEquals(PendingPrompts.WAITING_LABEL, projected.last().delivery)
    }

    @Test fun knownWaitingIdentityCannotBeClaimedByAnEqualBodyHostCard() {
        val waiting = listOf(PendingPrompts.Entry(id(2), "same"))
        val queued = replica(listOf(row(1).copy(state = MessageProtocol.State.QUEUED)), emptyList())
        val history = ChatHistory(sid = sid, inode = 7, rows = listOf(user(2, id(2)), reply(2)))
        val projected = history.projectMessages(queued, waiting = waiting)
        once(projected, listOf("same", "answer 2", "same"))
        assertEquals(listOf(id(2), id(1)), projected.filter { it.role == "user" }.map { it.requestId })
        assertEquals(null, projected.first().delivery)
        assertFalse(projected.first().pending)
    }

    @Test fun cancelledHostRowsCannotBeResurrectedByAWaitingStateSnapshot() {
        val cancelled = replica(listOf(row(1).copy(state = MessageProtocol.State.CANCELLED)), emptyList())
        once(ChatHistory(sid = sid).projectMessages(cancelled, waiting = listOf(PendingPrompts.Entry(id(1), "same"))), emptyList())
    }

    @Test fun supportedProtocolsReplaySendReceiptTranscriptFreshStartAndReconnect() {
        for (provider in listOf("claude", "codex", "pi", "hax", "cursor")) {
            val first = row(1, provider)
            val second = row(2, provider)
            val empty = ChatHistory(sid = sid, inode = 7)
            val queued = replica(listOf(first.copy(state = MessageProtocol.State.QUEUED)), emptyList())
            once(empty.projectMessages(queued), listOf("same"))
            val accepted = replica(listOf(first), host(1, provider))
            once(empty.projectMessages(accepted), listOf("same", "answer 1"))
            val caughtUp = empty.copy(rows = listOf(user(1), reply(1)))
            once(caughtUp.projectMessages(accepted), listOf("same", "answer 1"))
            val restarted = replica(listOf(first, second), host(1, provider) + host(2, provider, fresh = true))
            val divider = ChatHistoryProtocol.Row("assistant", "---\nFresh start\n---", "divider", "", 195, 199)
            val fresh = caughtUp.copy(rows = caughtUp.rows + divider + user(2, id(2)))
            once(fresh.projectMessages(restarted), listOf("same", "answer 1", divider.text, "same", "answer 2"))
            val landed = fresh.copy(rows = fresh.rows + reply(2))
            val reconnect = replica(listOf(first, second), host(1, provider) + host(2, provider, fresh = true))
            once(landed.projectMessages(reconnect), listOf("same", "answer 1", divider.text, "same", "answer 2"))
            assertEquals(listOf(id(1), id(2)), landed.projectMessages(reconnect).filter { it.role == "user" }.map { it.requestId })
        }
    }

    @Test fun tenTurnsKeepEveryDelayedReplyAndReceiptAlias() {
        val replica = replica((1..10).map { row(it) }, (1..10).flatMap { host(it) })
        val history = ChatHistory(sid = sid, inode = 7, rows = (1..10).map { user(it, text = "[Image #1] same") })
        once(history.projectMessages(replica), (1..10).flatMap { listOf("[Image #1] same", "answer $it") })
        val landed = history.copy(rows = (1..10).flatMap { listOf(user(it, text = "[Image #1] same"), reply(it)) })
        once(landed.projectMessages(replica), (1..10).flatMap { listOf("[Image #1] same", "answer $it") })
    }

    @Test fun aliasesAndNativeFactsCannotAppendAnotherOccurrenceOfThePhoneMessage() {
        val card = row(1)
        val native = user(1).json().put("read_id", "different-reader-id").put("offset", 100).put("end", 140)
        val fact = MessageProtocol.Fact(sid, "fact-1", 7, 100, 140, JSONObject().put("row", native).toString())
        val replica = replica(listOf(card), host(1)).copy(nativeFacts = mapOf("fact-1" to fact))
        val journal = MessageJournal.Entry("submit-proof", "account", MessageProtocol.Scope("claude", sid, "verify-session"), "submit", card.id, "same")
        val history = ChatHistory(sid = sid, inode = 7, rows = listOf(user(1), user(2, id(1)), reply(1)))
        once(history.projectMessages(replica, listOf(journal, journal.copy(id = "replayed-submit"))), listOf("same", "answer 1"))
    }

    @Test fun receiptIdentitySurvivesWithoutAQueuedCard() {
        val replica = replica(emptyList(), host(1))
        val history = ChatHistory(sid = sid, inode = 7, rows = listOf(user(1), user(2, id(1))))
        once(history.projectMessages(replica), listOf("same", "answer 1"))
        assertEquals(id(1), history.projectMessages(replica).first().requestId)
    }

    @Test fun equalRepliesWithDifferentOwnedIdsStaySeparate() {
        val replica = replica(listOf(row(1)), host(1))
        val earlier = ChatHistoryProtocol.Row("assistant", "answer 1", "different-reply", "", 0, 50)
        val history = ChatHistory(sid = sid, inode = 7, rows = listOf(earlier, user(1)))
        once(history.projectMessages(replica), listOf("answer 1", "same", "answer 1"))
    }

    @Test fun anOwnedStreamCannotClaimAnOlderArchiveOrADistinctNativeReply() {
        val replica = replica(listOf(row(1)), host(1))
        val older = ChatHistoryProtocol.Row("assistant", "answer 1", "history-older", "", 0, 50)
        val native = ChatHistoryProtocol.Row("assistant", "answer 1", "different-native-reply", "", 175, 190)
        val history = ChatHistory(sid = sid, inode = 7, rows = listOf(older, user(1), native))
        val before = history.projectMessages(replica)
        once(before, listOf("answer 1", "same", "answer 1", "answer 1"))
        assertEquals(1, before.count { it.key.startsWith("@live:") })
        val archived = reply(1).copy(id = "history-current")
        val after = history.copy(rows = history.rows + archived).projectMessages(replica)
        once(after, listOf("answer 1", "same", "answer 1", "answer 1"))
        assertEquals(0, after.count { it.key.startsWith("@live:") })
    }

    @Test fun handoverRepliesMatchOnlyArchivesAfterTheirDivider() {
        for (text in listOf("---\nSwitched AI: Pi to Claude\n---", "---\nFresh start\n---")) {
            val replica = MessageReplica(1, 1, host = HostStream(turn = "handover", blocks = listOf(
                HostBlock("handover:text:0", "text", "same reply", true, messageId = "fresh-handover"))))
            val old = ChatHistoryProtocol.Row("assistant", "same reply", "history-old", "", 0, 50)
            val divider = ChatHistoryProtocol.Row("assistant", text, "history-divider", "", 100, 140)
            val history = ChatHistory(sid = sid, inode = 7, rows = listOf(old, divider))
            once(history.projectMessages(replica), listOf("same reply", text, "same reply"))
            val archived = ChatHistoryProtocol.Row("assistant", "same reply", "history-handover", "", 150, 190)
            val projected = history.copy(rows = history.rows + archived).projectMessages(replica)
            once(projected, listOf("same reply", text, "same reply"))
            assertEquals(0, projected.count { it.key.startsWith("@live:") })
        }
    }

    @Test fun equalBlocksWithinOneTurnEachNeedTheirOwnArchivedOccurrence() {
        val first = replica(listOf(row(1)), host(1))
        val replica = first.copy(host = first.host.copy(blocks = first.host.blocks +
            HostBlock("different-item:text:0", "text", "answer 1", true, messageId = id(1))))
        val history = ChatHistory(sid = sid, inode = 7, rows = listOf(user(1), reply(1).copy(id = "history-first")))
        once(history.projectMessages(replica), listOf("same", "answer 1", "answer 1"))
        val second = reply(1).copy(id = "history-second", offset = 191, end = 199)
        once(history.copy(rows = history.rows + second).projectMessages(replica), listOf("same", "answer 1", "answer 1"))
    }

    @Test fun knownIdsNeverClaimADifferentMessageWithEqualText() {
        val replica = replica(listOf(row(1)), host(1))
        val history = ChatHistory(sid = sid, inode = 7, rows = listOf(user(1, "different-id")))
        once(history.projectMessages(replica), listOf("same", "same", "answer 1"))
        assertEquals(listOf("different-id", id(1)), history.projectMessages(replica).filter { it.role == "user" }.map { it.requestId })
    }

    @Test fun aConflictingReceiptCannotEraseTheUnconfirmedLocalIntention() {
        val scope = MessageProtocol.Scope("claude", sid, "verify-session")
        val journal = MessageJournal.Entry("submit-proof", "account", scope, "submit", id(1), "original")
        for (state in listOf(MessageProtocol.State.ACCEPTED, MessageProtocol.State.CANCELLED)) {
            val other = row(1).copy(text = "different", state = state)
            val replica = MessageReplica(1, 1, mapOf(other.id to other))
            val drawn = ChatHistory(sid = sid, inode = 7).projectMessages(replica, listOf(journal))
            val local = drawn.single { it.unknownId == journal.id }
            assertEquals("original", local.text)
            assertEquals("@unknown:${journal.id}", local.key)
            assertEquals(drawn.size, drawn.map { it.key }.toSet().size)
            assertEquals(if (state == MessageProtocol.State.ACCEPTED) listOf("different") else emptyList<String>(),
                drawn.filter { it.unknownId == null }.map { it.text })
        }
    }

    @Test fun anotherSessionCannotClaimAnEqualBodyOrAppendAJournalEcho() {
        val other = row(1).copy(sid = "other-session")
        val journal = MessageJournal.Entry("submit-proof", "account", MessageProtocol.Scope("claude", "other-session", "verify-session"), "submit", other.id, "same")
        val history = ChatHistory(sid = sid, inode = 7, rows = listOf(user(1)))
        once(history.projectMessages(MessageReplica(1, 1, mapOf(other.id to other)), listOf(journal)), listOf("same"))
        assertEquals("u-1", history.projectMessages(MessageReplica(1, 1, mapOf(other.id to other))).single().requestId)
    }

    @Test fun repeatedLegacyIntentionsAndCrlfReceiptUseTheSameProjection() {
        val prompt = QueuedMessages.SentPrompt("one\ntwo", 1, id(1), QueuedMessages.Delivery.DELIVERED, sid, 7, 0)
        val history = ChatHistory(sid = sid, inode = 7, rows = listOf(user(1, text = "one\r\ntwo")))
        once(history.projectMessages(sent = listOf(prompt, prompt)), listOf("one\r\ntwo"))
    }
}

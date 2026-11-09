package dev.multiprompt.companion.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveMessageOnceTest {
    private val sid = "live-once-proof"
    private val sentAt = java.time.Instant.parse("2026-11-09T00:00:00Z").epochSecond.toDouble()

    private fun at(second: Int) = "2026-11-09T00:00:%02dZ".format(second)

    private fun card(n: Int, text: String) = MessageProtocol.Row("mp-liveonce0000000$n", "claude", sid, text, n.toLong(),
        "attempt0123456789", MessageProtocol.State.ACCEPTED, null, null, 1, sentAt + n * 10)

    private fun user(n: Int, text: String) = ChatHistoryProtocol.Row("user", text, "u-$n", at(n * 10 + 1), n * 100L, n * 100L + 40)

    private fun reply(n: Int, text: String) = ChatHistoryProtocol.Row("assistant", text, "msg_$n", at(n * 10 + 2), n * 100L + 50, n * 100L + 90)

    private fun started(host: HostStream, n: Int) = host.apply(MessageProtocol.HostEvent("turn.started", "t-$n", null, 0L, null, false, null,
        sent = "mp-liveonce0000000$n" to listOf("u-$n")))

    private fun text(host: HostStream, item: String, body: String, done: Boolean, n: Int) = host.apply(MessageProtocol.HostEvent(
        if (done) "text.done" else "text.delta", "t-$n", item, 0L, body, done, null))

    @Test fun fiveMessagesAndRepliesEachShowOnce() {
        val rows = (1..5).flatMap { listOf(user(it, "[Image #1] question $it"), reply(it, "answer $it")) }
        var host = HostStream()
        for (n in 1..5) {
            host = started(host, n)
            host = text(host, "msg_$n:text:0", if (n == 5) "answer" else "answer $n", done = n != 5, n)
        }
        val replica = MessageReplica(1, 1, (1..5).associate { "mp-liveonce0000000$it" to card(it, "question $it") }, host = host)

        val drawn = ChatHistory(sid = sid, inode = 1, offset = 0, rows = rows).managedMessages(replica, emptyList())

        assertEquals((1..5).flatMap { listOf("[Image #1] question $it", "answer $it") }, drawn.map { it.text })
        assertEquals((1..5).map { "@shared:mp-liveonce0000000$it" }, drawn.filter { it.role == "user" }.map { it.key })
    }

    @Test fun textAfterToolResultsStaysVisible() {
        val rows = listOf(user(1, "run it"), reply(1, "Running the tests."),
            ChatHistoryProtocol.Row("tool", "Bash", "toolu_1", at(13), 150, 160))
        var host = started(HostStream(), 1)
        host = text(host, "msg_1:text:0", "Running the tests.", true, 1)
        host = text(host, "msg_2:text:0", "All tests pass.", false, 1)
        val replica = MessageReplica(1, 1, mapOf("mp-liveonce00000001" to card(1, "run it")), host = host)

        val drawn = ChatHistory(sid = sid, inode = 1, offset = 0, rows = rows).managedMessages(replica, emptyList())

        assertEquals(listOf("Running the tests.", "All tests pass."), drawn.filter { it.role == "assistant" }.map { it.text })
    }

    @Test fun anOlderDesktopWithoutSentIdsStillShowsEachMessageOnce() {
        val rows = listOf(user(1, "hello"), ChatHistoryProtocol.Row("assistant", "hi there", "", at(12), 150, 190))
        var host = HostStream().apply(MessageProtocol.HostEvent("turn.started", "t-1", null, 0L, null, false, null))
        host = text(host, "text.done", "hi there", true, 1)
        val replica = MessageReplica(1, 1, mapOf("mp-liveonce00000001" to card(1, "hello")), host = host)

        val drawn = ChatHistory(sid = sid, inode = 1, offset = 0, rows = rows).managedMessages(replica, emptyList())

        assertEquals(listOf("hello", "hi there"), drawn.map { it.text })
    }

    @Test fun aLiveStepWithoutAnIdIsNeverHidden() {
        val rows = listOf(user(1, "hello"), reply(1, "first"))
        var host = started(HostStream(), 1)
        host = text(host, "text.delta", "still typing", false, 1)
        val replica = MessageReplica(1, 1, mapOf("mp-liveonce00000001" to card(1, "hello")), host = host)

        val drawn = ChatHistory(sid = sid, inode = 1, offset = 0, rows = rows).managedMessages(replica, emptyList())

        assertEquals(listOf("hello", "first", "still typing"), drawn.map { it.text })
    }
}

package dev.multiprompt.companion.reader

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PiLoginPromptTest {
    private val open = "Open this link\nhttps://auth.example/device"
    private val confirm = "Logged in to anthropic. The next reply uses that login."

    private fun reply(text: String) = ChatHistoryMessage("assistant-key", "assistant", text, "", 0, 0, "history-1")

    private fun said(text: String) = ChatHistoryMessage("user-key", "user", text, "", 0, 0, "message-1")

    private fun host(text: String, number: Int = 1) = reply(text).copy(hostItem = "pi-login-$number")

    @Test fun ordinaryRepliesNeverShowTheCard() {
        for (text in listOf(
            "I fixed the sign-in tests.\nhttps://github.com/valentinyeo/multiprompt-android/pull/554",
            "https://auth.example/device",
            open,
            "Paste the code",
            "Pi needs an answer.\nEnter the code",
            "Log in to a Pi provider:\nAnthropic (anthropic, not signed in)",
        )) {
            assertNull(PiLoginPrompt.prompt(reply(text)))
            assertNull(PiLoginPrompt.current(listOf(reply(text))))
        }
        assertNull(PiLoginPrompt.prompt(reply(open).copy(requestId = "pi-login-1")))
    }

    @Test fun userMessagesNeverShowTheCardEvenWithAHostItem() {
        for (text in listOf("fix the login bug", "sign in to pi and try again", open)) {
            assertNull(PiLoginPrompt.prompt(said(text)))
            assertNull(PiLoginPrompt.prompt(said(text).copy(hostItem = "pi-login-1")))
        }
    }

    @Test fun hostLoginRowsShowTheHttpsLinkWithoutCueWords() {
        val prompt = PiLoginPrompt.prompt(host(open))!!
        assertEquals(open, prompt.text)
        assertEquals("https://auth.example/device", prompt.link)
        assertEquals("https://auth.example/device", PiLoginPrompt.prompt(host("https://auth.example/device"))!!.link)
        assertEquals(
            "https://auth.example/token?code=1&state=2",
            PiLoginPrompt.prompt(host("Continue at https://auth.example/token?code=1&state=2."))!!.link,
        )
        assertNull(PiLoginPrompt.prompt(reply(open).copy(hostItem = "pi-reply-1")))
    }

    @Test fun onlyTheNextHostLoginRowCanClearOrReplaceTheCard() {
        assertNotNull(PiLoginPrompt.current(listOf(host(open), said(confirm), reply(confirm))))
        assertEquals(0, PiLoginPrompt.current(listOf(host(open), reply("hello")))!!.index)
        for (result in listOf(confirm, "Pi login failed", "Provider refused the request", "")) {
            assertNull(PiLoginPrompt.current(listOf(host(open), host(result, 2))))
        }
        assertEquals(2, PiLoginPrompt.current(listOf(host(open), host(confirm, 2), host(open, 3)))!!.index)
    }

    @Test fun httpAndLinklessHostRowsHaveNoCardOrButton() {
        for (text in listOf("Open this link\nhttp://auth.example/device", "Paste the code", "Pi needs an answer.", "")) {
            assertNull(PiLoginPrompt.prompt(host(text)))
            assertNull(PiLoginPrompt.current(listOf(host(text))))
        }
        assertNull(PiLoginPrompt.link("http://auth.example/device"))
        assertEquals("https://auth.example/device", PiLoginPrompt.link("Continue at (https://auth.example/device)."))
    }

    @Test fun protocolHostItemsSurviveLiveProjectionAndTranscriptCatchup() {
        val scope = MessageProtocol.Scope("pi", "pi-login-session", "verify-session")
        fun replica(vararg texts: String): MessageReplica {
            val items = texts.mapIndexed { index, text ->
                JSONObject().put("kind", "text.done").put("sid", scope.sid).put("turn", "login-turn")
                    .put("item", "pi-login-${index + 1}").put("payload", JSONObject().put("text", text))
            }
            val envelope = JSONObject().put("id", 1).put("message_protocol", 1).put("result", JSONObject()
                .put("snapshot_id", "login").put("epoch", 1).put("seq", texts.size).put("next_cursor", JSONObject.NULL)
                .put("items", JSONArray(items)))
            return MessageReplica.snapshot(listOf(MessageProtocol.snapshot(envelope.toString(), 1, scope)))
        }
        val history = ChatHistory(sid = scope.sid, inode = 7)
        val caughtUp = history.copy(rows = listOf(ChatHistoryProtocol.Row("assistant", open, "history-open", "", 0, 100)))
        for (source in listOf(history, caughtUp)) {
            val messages = source.projectMessages(replica(open))
            assertEquals(1, messages.size)
            assertEquals("https://auth.example/device", PiLoginPrompt.current(messages)!!.prompt.link)
            assertNull(PiLoginPrompt.current(source.projectMessages(replica(open, confirm))))
        }
        val completed = caughtUp.copy(rows = caughtUp.rows + ChatHistoryProtocol.Row("assistant", confirm, "history-result", "", 100, 200))
        val messages = completed.projectMessages(replica(open, confirm))
        assertEquals(2, messages.size)
        assertNull(PiLoginPrompt.current(messages))
    }
}

package dev.multiprompt.companion.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class PiLoginPromptProjectionTest {
    private val sid = "pi-login-2347"
    private val prompt = "Sign in at https://auth.example/anthropic\nPaste the code when prompted."

    @Test fun piLoginPromptShowsFromHistoryAndFromAFinishedHostBlock() {
        val history = ChatHistory(
            sid = sid,
            inode = 7,
            rows = listOf(
                ChatHistoryProtocol.Row("user", "hello", "u-1", "", 100, 140),
                ChatHistoryProtocol.Row("assistant", prompt, "history-login", "", 141, 220),
            ),
        )
        val host = HostStream(
            earlier = listOf(HostBlock("pi-login-1:text:0", "text", prompt, true, turn = "t-1")),
        )
        val projected = history.projectMessages(MessageReplica(1, 1, host = host))
        assertEquals(listOf("hello", prompt), projected.map { it.text })
    }
}
package dev.multiprompt.companion.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatPollGateTest {
    @Test
    fun hiddenChatWaitsForWakeInsteadOfPolling() {
        assertEquals(ChatPollGate.Step.WAIT_FOR_WAKE, ChatPollGate.step(selected = false))
    }

    @Test
    fun selectedChatPollsAtOnce() {
        assertEquals(ChatPollGate.Step.POLL_NOW, ChatPollGate.step(selected = true))
    }

    @Test
    fun reopeningFlipsWaitIntoPoll() {
        val steps = listOf(false, false, true, true, false, true).map(ChatPollGate::step)
        assertEquals(
            listOf(
                ChatPollGate.Step.WAIT_FOR_WAKE, ChatPollGate.Step.WAIT_FOR_WAKE,
                ChatPollGate.Step.POLL_NOW, ChatPollGate.Step.POLL_NOW,
                ChatPollGate.Step.WAIT_FOR_WAKE, ChatPollGate.Step.POLL_NOW,
            ),
            steps,
        )
    }
}

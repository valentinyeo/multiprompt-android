package dev.multiprompt.companion.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendRecoveryTest {
    private fun row(
        state: MessageProtocol.State = MessageProtocol.State.FAILED,
        receipt: String? = null,
        text: String = "sent while the reply streamed",
    ) = MessageProtocol.Row("message3031000000001", "pi", "sid1", text, 1, "attempt1", state, null, receipt, 1, 1.0)

    @Test fun aSubmitTheChatDroppedWithoutAnAttemptIsRetriedWhileTheChatIsConnected() {
        assertTrue(SendRecovery.due(row(), recovered = 0, connected = true))
    }

    @Test fun aReceiptMeansTheChatTriedAndIsNeverRetriedAutomatically() {
        assertTrue(SendRecovery.neverAttempted(row(receipt = "")))
        listOf("not_sent", "host_stdin_write_failed", "waiting_too_long", "connector_unavailable").forEach {
            assertFalse(it, SendRecovery.neverAttempted(row(receipt = it)))
            assertFalse(it, SendRecovery.due(row(receipt = it), recovered = 0, connected = true))
        }
    }

    @Test fun onlyAFailedSubmitWithTextIsRecovered() {
        listOf(MessageProtocol.State.QUEUED, MessageProtocol.State.SENT, MessageProtocol.State.ACCEPTED,
            MessageProtocol.State.STARTED, MessageProtocol.State.CANCELLED).forEach { state ->
            assertFalse(state.name, SendRecovery.due(row(state = state), recovered = 0, connected = true))
        }
        assertFalse(SendRecovery.due(row(text = "  "), recovered = 0, connected = true))
    }

    @Test fun anOfflineChatWaitsForItsLinkBeforeRetrying() {
        assertFalse(SendRecovery.due(row(), recovered = 0, connected = false))
    }

    @Test fun retriesAreBounded() {
        assertTrue(SendRecovery.due(row(), recovered = SendRecovery.ATTEMPTS - 1, connected = true))
        assertFalse(SendRecovery.due(row(), recovered = SendRecovery.ATTEMPTS, connected = true))
    }
}

package dev.multiprompt.companion.reader

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuedEditTest {
    private val sid = "queued-edit-proof"
    private val created = 1_000.0

    private fun row(id: String, state: MessageProtocol.State, text: String = "hello") =
        MessageProtocol.Row(id, "claude", sid, text, 1, "attempt", state, null, null, 1, created)

    private fun drawn(vararg rows: MessageProtocol.Row, now: Double = created + 5) =
        ChatHistory(sid = sid, inode = 1, offset = 100).managedMessages(
            MessageReplica(1, 1, rows.associateBy { it.id }),
            emptyList(), now, false, emptyMap(),
        )

    @Test fun aWaitingMessageShowsEdit() {
        assertTrue(QueuedEdit.shows(drawn(row("queuedmessage0000001", MessageProtocol.State.QUEUED)).single()))
        assertTrue(QueuedEdit.shows(drawn(row("sentmessage000000001", MessageProtocol.State.SENT)).single()))
    }

    @Test fun aMessageTheBotAlreadyReadShowsNoEdit() {
        for (state in listOf(MessageProtocol.State.ACCEPTED, MessageProtocol.State.STARTED, MessageProtocol.State.CANCELLED, MessageProtocol.State.FAILED)) {
            assertTrue(state.name, drawn(row("readmessage00000001", state)).none { QueuedEdit.shows(it) })
        }
    }

    @Test fun aWaitingMessageThatTurnedNotSentShowsNoEdit() {
        assertTrue(drawn(row("sentmessage000000002", MessageProtocol.State.SENT), now = created + 600).none { QueuedEdit.shows(it) })
    }

    @Test fun theBotsAnswerNeverShowsEdit() {
        val answer = ChatHistoryMessage("a", "assistant", "hi", "", 0, 1, requestId = "queuedmessage0000001", cancellable = true)
        assertFalse(QueuedEdit.shows(answer))
    }

    @Test fun anEditCancelsTheWaitingMessageThenSendsTheNewTextReplacingIt() = runBlocking {
        val calls = mutableListOf<String>()
        val waiting = row("queuedmessage0000002", MessageProtocol.State.QUEUED)
        val outcome = QueuedEdit.run(waiting, "new text",
            dismiss = { calls += "dismiss ${it.id}"; MessageActionResult.Applied },
            submit = { text, replaces -> calls += "submit $text $replaces"; PromptAdmission.Recorded("fresh0000000000001") })
        assertEquals(listOf("dismiss queuedmessage0000002", "submit new text queuedmessage0000002"), calls)
        assertEquals(QueuedEdit.Outcome.Sent(PromptAdmission.Recorded("fresh0000000000001")), outcome)
    }

    @Test fun anEditThatArrivesTooLateKeepsTheOldMessageAndSendsNothing() = runBlocking {
        var submitted = false
        val waiting = row("sentmessage000000003", MessageProtocol.State.SENT)
        for (refusal in listOf(MessageActionResult.Rejected("message already handed off"), MessageActionResult.Unknown, MessageActionResult.BlockedRecovery)) {
            val outcome = QueuedEdit.run(waiting, "new text", dismiss = { refusal }, submit = { _, _ -> submitted = true; PromptAdmission.Recorded("x") })
            assertEquals(QueuedEdit.Outcome.TooLate, outcome)
        }
        assertFalse(submitted)
    }

    @Test fun aMessageTheBotAlreadyReadIsNotEvenCancelled() = runBlocking {
        var dismissed = false
        for (state in listOf(MessageProtocol.State.ACCEPTED, MessageProtocol.State.STARTED, MessageProtocol.State.FAILED, MessageProtocol.State.CANCELLED)) {
            val outcome = QueuedEdit.run(row("readmessage00000002", state), "new text",
                dismiss = { dismissed = true; MessageActionResult.Applied }, submit = { _, _ -> PromptAdmission.Recorded("x") })
            assertEquals(state.name, QueuedEdit.Outcome.TooLate, outcome)
        }
        assertFalse(dismissed)
    }

    @Test fun aVanishedMessageIsTooLate() = runBlocking {
        assertEquals(QueuedEdit.Outcome.TooLate, QueuedEdit.run(null, "new text", dismiss = { MessageActionResult.Applied }, submit = { _, _ -> PromptAdmission.Recorded("x") }))
    }

    @Test fun aRefusedReplacementAfterTheCancelIsReportedSoTheTextIsNotLost() = runBlocking {
        val outcome = QueuedEdit.run(row("queuedmessage0000003", MessageProtocol.State.QUEUED), "new text",
            dismiss = { MessageActionResult.Applied }, submit = { _, _ -> PromptAdmission.Rejected("Chat is unavailable") })
        assertEquals(QueuedEdit.Outcome.Lost("Chat is unavailable"), outcome)
    }

    @Test fun noRowMeansNoDismissOrSubmit() = runBlocking {
        var touched = false
        QueuedEdit.run(null, "x", dismiss = { touched = true; MessageActionResult.Applied }, submit = { _, _ -> touched = true; PromptAdmission.Recorded("x") })
        assertFalse(touched)
    }
}

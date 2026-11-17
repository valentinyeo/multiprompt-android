package dev.multiprompt.companion.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingPromptsTest {
    @Test fun droppedAndRestoredLinkFlushesEachWaitingMessageOnceInOrder() {
        val queue = PendingPrompts()
        val first = queue.enqueue("/clear")
        val second = queue.enqueue("hello")
        val cached = ChatHistoryState(ChatHistory(sid = "poteto"), loaded = true)
        assertNull(queue.next(cached.ready))
        val live = cached.copy(connected = true)
        assertEquals(first.id, queue.next(live.ready)?.id)
        assertNull(queue.next(live.ready))
        queue.complete(first.id, PromptAdmission.Rejected("Chat is disconnected"))
        assertNull(queue.next(live.copy(connected = false, retrying = true).ready))
        assertEquals(listOf(first.id, second.id), queue.state.value.map { it.id })
        val sent = mutableListOf<String>()
        while (true) {
            val entry = queue.next(live.ready) ?: break
            sent.add(entry.text)
            queue.complete(entry.id, PromptAdmission.Recorded(entry.id))
        }
        assertEquals(listOf("/clear", "hello"), sent)
        assertTrue(queue.state.value.isEmpty())
        assertNull(queue.next(live.ready))
        assertEquals(setOf(first.id, second.id), queue.accepted.value)
    }

    @Test fun permanentRejectionKeepsTheMessageForExplicitRetryOrRemoval() {
        val queue = PendingPrompts()
        val entry = queue.enqueue("keep this")
        assertEquals(entry.id, queue.next(true)?.id)
        assertFalse(queue.remove(entry.id))
        queue.complete(entry.id, PromptAdmission.Rejected("Reset or navigation is pending"))
        assertEquals("keep this", queue.state.value.single().text)
        assertEquals(ChatHistory.NOT_SENT, ChatHistory().projectMessages(waiting = queue.state.value).single().delivery)
        assertNull(queue.next(true))
        assertTrue(queue.retry(entry.id))
        assertEquals(entry.id, queue.next(true)?.id)
        queue.complete(entry.id, PromptAdmission.Rejected("Chat is disconnected"))
        assertTrue(queue.remove(entry.id))
        assertTrue(queue.state.value.isEmpty())
        assertTrue(queue.accepted.value.isEmpty())
    }

    @Test fun admissionCannotReplaceTheWaitingMessageIdentity() {
        val queue = PendingPrompts()
        val entry = queue.enqueue("keep this identity")
        queue.next(true)
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            queue.complete(entry.id, PromptAdmission.Recorded("different-message-id"))
        }
        assertEquals(entry.id, queue.state.value.single().id)
        assertTrue(queue.accepted.value.isEmpty())
    }

    @Test fun onlyLinkReadinessFailuresRetryAutomatically() {
        listOf("Chat is disconnected", "Chat is not ready", "Chat is catching up").forEach {
            assertTrue(PendingPrompts.retryable(it))
        }
        listOf("Chat changed", "Chat is no longer selected", "Could not save message").forEach {
            assertFalse(PendingPrompts.retryable(it))
        }
    }
}

package dev.multiprompt.companion.data

import android.app.Application
import dev.multiprompt.companion.model.TmuxSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SessionReplyDotTest {
    private val now = System.currentTimeMillis() / 1000
    private val frozen = now - 3 * 3600

    @Test
    fun replyAfterLastReadShowsTheDotWhileSessionActivityStaysFrozen() {
        val store = SessionReadStore(RuntimeEnvironment.getApplication())
        val read = TmuxSession("host", "cl-1", 1, 0, frozen, outputActivityEpochSeconds = frozen, paneCommand = "claude")
        store.markRead(read)
        assertFalse(store.isUnread(read))

        val replied = read.copy(outputActivityEpochSeconds = now + 120)

        assertTrue(store.isUnread(replied))
        store.markRead(replied)
        assertFalse(store.isUnread(replied))
        assertTrue(store.isUnread(replied.copy(outputActivityEpochSeconds = now + 240)))
    }

    @Test
    fun finishedLiveChatReplyShowsTheDotAndInFlightTurnDoesNot() {
        val store = SessionReadStore(RuntimeEnvironment.getApplication())
        val live = TmuxSession("host", "live-abc", 1, 0, frozen, paneCommand = "sleep", replyAtEpochSeconds = frozen, replyWaiting = true)
        store.markRead(live)

        val finished = live.copy(replyAtEpochSeconds = now + 120)
        val working = finished.copy(replyWaiting = false)

        assertTrue(store.isUnread(finished))
        assertTrue(SessionReadStore.waitingForReply(finished))
        assertFalse(SessionReadStore.waitingForReply(working))
    }

    @Test
    fun replyTimePrefersLiveReplyThenWindowOutputThenSessionActivity() {
        val base = TmuxSession("host", "cl-1", 1, 0, 100)

        assertEquals(100, base.replyActivityEpochSeconds)
        assertEquals(300, base.copy(outputActivityEpochSeconds = 300).replyActivityEpochSeconds)
        assertEquals(500, base.copy(outputActivityEpochSeconds = 300, replyAtEpochSeconds = 500).replyActivityEpochSeconds)
    }
}

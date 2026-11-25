package dev.multiprompt.companion.data

import android.app.Application
import dev.multiprompt.companion.model.TmuxSession
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UnreadCountStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private val session = TmuxSession("host", "claude", 1, 0, 20, preview = "❯")
    private val counted = UnreadCount.State(3, 20, 20, false)

    @Test
    fun unreadMessageBoundarySurvivesRecreationAndClearsWithRead() {
        val store = SessionReadStore(context)
        store.markRead(session)
        store.markUnreadFrom(session, "transcript-message")
        val reopened = SessionReadStore(context)
        assertTrue(reopened.isUnread(session))
        assertEquals("transcript-message", reopened.unreadMessageKey(session))
        assertEquals(1, reopened.unreadCountState(session).count)
        reopened.markRead(session)
        assertNull(SessionReadStore(context).unreadMessageKey(session))
        store.markUnreadFrom(session, "transcript-message")
        store.removeHost(session.hostId)
        assertNull(SessionReadStore(context).unreadMessageKey(session))
    }

    @Test
    fun stateSurvivesStoreRecreationAndReadClearsIt() {
        val store = SessionReadStore(context)
        store.saveUnreadCountState(session, counted)
        val reopened = SessionReadStore(context)
        assertEquals(counted, reopened.unreadCountState(session))
        reopened.markRead(session)
        assertEquals(0, SessionReadStore(context).unreadCountState(session).count)
        reopened.markUnread(session)
        assertEquals(1, SessionReadStore(context).unreadCountState(session).count)
        reopened.saveUnreadCountState(session, counted)
        reopened.markUnread(session)
        assertEquals(3, reopened.unreadCountState(session).count)
    }

    @Test
    fun archiveAndTimedArchiveClearCount() {
        val store = SessionReadStore(context)
        store.saveUnreadCountState(session, counted)
        store.archive(session)
        assertEquals(0, store.unreadCountState(session).count)
        store.saveUnreadCountState(session, counted)
        store.archiveUntil(session, Long.MAX_VALUE)
        assertEquals(0, store.unreadCountState(session).count)
    }

    @Test
    fun removeHostClearsOnlyItsCountState() {
        val store = SessionReadStore(context)
        val other = session.copy(hostId = "other")
        store.saveUnreadCountState(session, counted)
        store.saveUnreadCountState(other, counted)
        store.removeHost(session.hostId)
        assertEquals(UnreadCount.State(), store.unreadCountState(session))
        assertEquals(counted, store.unreadCountState(other))
    }
}

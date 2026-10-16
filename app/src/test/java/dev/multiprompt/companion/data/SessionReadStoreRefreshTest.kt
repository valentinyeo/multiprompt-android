package dev.multiprompt.companion.data

import android.app.Application
import dev.multiprompt.companion.model.TmuxSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SessionReadStoreRefreshTest {
    @Test
    fun intermediateArchiveCheckDoesNotPersistReleaseButFinalCheckDoes() {
        val store = SessionReadStore(RuntimeEnvironment.getApplication())
        val session = TmuxSession("host", "session", 1, 0, 1)
        store.archive(session)

        assertFalse(store.isArchived(session, needsAttention = true, restoreReleased = false))
        assertTrue(store.isArchived(session, needsAttention = false, restoreReleased = false))
        assertFalse(store.isArchived(session, needsAttention = true))
        assertFalse(store.isArchived(session, needsAttention = false))
    }
}

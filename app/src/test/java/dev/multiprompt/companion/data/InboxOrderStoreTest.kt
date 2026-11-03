package dev.multiprompt.companion.data

import android.app.Application
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class InboxOrderStoreTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun aFreshInstallPutsNewestSessionsAtTheBottom() {
        assertTrue(SessionReadStore(context).newestSessionsAtBottom())
    }

    @Test fun aStoredNewestOnTopFromAnOlderBuildIsFlippedBackOnce() {
        context.getSharedPreferences("session_read_state", Context.MODE_PRIVATE)
            .edit().putBoolean("newest_sessions_at_bottom", false).apply()
        assertTrue(SessionReadStore(context).newestSessionsAtBottom())
        assertTrue(SessionReadStore(context).newestSessionsAtBottom())
    }

    @Test fun aChoiceMadeAfterTheUpgradeIsKept() {
        val store = SessionReadStore(context)
        store.newestSessionsAtBottom()
        store.setNewestSessionsAtBottom(false)
        assertFalse(SessionReadStore(context).newestSessionsAtBottom())
        store.setNewestSessionsAtBottom(true)
        assertTrue(SessionReadStore(context).newestSessionsAtBottom())
    }
}

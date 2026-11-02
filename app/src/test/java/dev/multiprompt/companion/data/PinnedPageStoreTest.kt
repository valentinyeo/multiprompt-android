package dev.multiprompt.companion.data

import android.app.Application
import dev.multiprompt.companion.model.TmuxSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PinnedPageStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private val chat = TmuxSession("host", "claude", 1, 0, 20)
    private val other = TmuxSession("host", "codex", 1, 0, 20)

    @Test fun eachChatKeepsItsOwnPageAcrossRecreation() {
        SessionReadStore(context).setPinnedPage(chat, "https://hypertask.app/explorations/fdpzvrp")
        assertEquals("https://hypertask.app/explorations/fdpzvrp", SessionReadStore(context).pinnedPage(chat))
        assertNull(SessionReadStore(context).pinnedPage(other))
    }

    @Test fun removingAPinLeavesTheOtherChatUntouched() {
        val store = SessionReadStore(context)
        store.setPinnedPage(chat, "https://example.com/a")
        store.setPinnedPage(other, "https://example.com/b")
        store.setPinnedPage(chat, null)
        val reopened = SessionReadStore(context)
        assertNull(reopened.pinnedPage(chat))
        assertEquals("https://example.com/b", reopened.pinnedPage(other))
    }
}

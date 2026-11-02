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
class BotLookStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private val session = TmuxSession("host", "claude", 1, 0, 20)
    private val other = TmuxSession("host", "codex", 1, 0, 20)

    @Test fun lookSurvivesRecreationAndStaysWithItsOwnBot() {
        val store = SessionReadStore(context)
        store.setBotFace(session, 7)
        store.setBotColor(session, 2)
        store.setBotPicture(session, "/tmp/look.png")
        val reopened = SessionReadStore(context)
        assertEquals(7, reopened.botFace(session))
        assertEquals(2, reopened.botColor(session))
        assertEquals("/tmp/look.png", reopened.botPicture(session))
        assertNull(reopened.botFace(other))
        assertNull(reopened.botColor(other))
        assertNull(reopened.botPicture(other))
    }

    @Test fun autoClearsEveryPickAndOutOfRangeValuesAreIgnored() {
        val store = SessionReadStore(context)
        store.setBotFace(session, 3)
        store.setBotColor(session, 5)
        store.setBotPicture(session, "/tmp/look.png")
        store.setBotFace(session, null)
        store.setBotColor(session, null)
        store.setBotPicture(session, null)
        val reopened = SessionReadStore(context)
        assertNull(reopened.botFace(session))
        assertNull(reopened.botColor(session))
        assertNull(reopened.botPicture(session))
        store.setBotFace(session, 31)
        assertNull("an out of range face is ignored", SessionReadStore(context).botFace(session))
        store.setBotColor(session, 12)
        assertNull("an out of range colour is ignored", SessionReadStore(context).botColor(session))
    }
}

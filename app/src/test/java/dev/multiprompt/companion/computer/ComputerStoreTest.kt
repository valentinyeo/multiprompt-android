package dev.multiprompt.companion.computer

import android.app.Application
import android.graphics.Bitmap
import dev.multiprompt.companion.model.TmuxSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ComputerStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private val store = ComputerStore(context)
    private val session = TmuxSession("host", "claude", 1, 0, 20)
    private val other = session.copy(name = "another")

    @Test fun eachServerKeepsItsOwnViewerAddress() {
        assertEquals(ComputerTarget.Default, store.target("host"))
        store.setTarget("host", ComputerTarget("viewer.local", 6081))
        assertEquals(ComputerTarget("viewer.local", 6081), store.target("host"))
        assertEquals(ComputerTarget.Default, store.target("other-host"))
    }

    @Test fun aChatRemembersWhetherItWasLeftOnTheComputerView() {
        assertFalse(store.showsComputer(session))
        store.setShowsComputer(session, true)
        assertTrue(store.showsComputer(session))
        assertFalse(store.showsComputer(other))
        store.setShowsComputer(session, false)
        assertFalse(store.showsComputer(session))
    }

    @Test fun theLastPictureSurvivesUntilItIsCleared() {
        assertNull(store.lastPicture(session))
        store.saveLastPicture(session, Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))
        assertNotNull(store.lastPicture(session))
        assertTrue(store.lastPicture(session)!!.length() > 0)
        assertNull(store.lastPicture(other))
        store.clearLastPicture(session)
        assertNull(store.lastPicture(session))
    }
}

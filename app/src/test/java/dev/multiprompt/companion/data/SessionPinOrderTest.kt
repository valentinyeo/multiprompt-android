package dev.multiprompt.companion.data

import android.app.Application
import dev.multiprompt.companion.model.TmuxSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SessionPinOrderTest {
    private val context = RuntimeEnvironment.getApplication()
    private val a = TmuxSession("host", "a", 1, 0, 50)
    private val b = TmuxSession("host", "b", 1, 0, 40)
    private val c = TmuxSession("host", "c", 1, 0, 30)
    private val newestFirst = listOf(a, b, c)
    private val pinC = setOf(SessionReadStore.key("host", "c"))

    private fun names(list: List<TmuxSession>) = list.map { it.name }

    @Test fun pinnedChatMovesAheadOfNewerChats() {
        assertEquals(listOf("c", "a", "b"), names(SessionPins.arrange(newestFirst, pinC, pinnedLast = false)))
    }

    @Test fun pinnedChatLandsLastWhenTheListIsDrawnBottomUp() {
        assertEquals(listOf("a", "b", "c"), names(SessionPins.arrange(listOf(c, a, b), pinC, pinnedLast = true)))
    }

    @Test fun aNewReplyElsewhereDoesNotPushThePinDown() {
        val busier = b.copy(lastActivityEpochSeconds = 99)
        assertEquals(listOf("c", "b", "a"), names(SessionPins.arrange(listOf(busier, a, c), pinC, pinnedLast = false)))
    }

    @Test fun unpinnedListKeepsItsOrder() {
        assertEquals(listOf("a", "b", "c"), names(SessionPins.arrange(newestFirst, emptySet(), pinnedLast = false)))
    }

    @Test fun pinnedChatsKeepTheirOwnOrder() {
        val both = pinC + SessionReadStore.key("host", "b")
        assertEquals(listOf("b", "c", "a"), names(SessionPins.arrange(newestFirst, both, pinnedLast = false)))
    }

    @Test fun pinSurvivesRecreationAndUnpinClearsIt() {
        SessionReadStore(context).setPinnedToTop(c, true)
        assertEquals(pinC, SessionReadStore(context).pinnedSessionKeys())
        SessionReadStore(context).setPinnedToTop(c, false)
        assertTrue(SessionReadStore(context).pinnedSessionKeys().isEmpty())
    }

    @Test fun removingAHostDropsItsPins() {
        val store = SessionReadStore(context)
        store.setPinnedToTop(c, true)
        store.setPinnedToTop(TmuxSession("other", "x", 1, 0, 1), true)
        store.removeHost("host")
        assertEquals(setOf(SessionReadStore.key("other", "x")), SessionReadStore(context).pinnedSessionKeys())
    }
}

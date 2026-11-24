package dev.multiprompt.companion.ui

import android.app.Application
import android.os.Looper
import dev.multiprompt.companion.data.PinnedChats
import dev.multiprompt.companion.data.SessionReadStore
import dev.multiprompt.companion.model.TmuxSession
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReaderSwipeOrderTest {
    private lateinit var f: SessionActivityFixture
    private val firstPin = session("first-pin", 50)
    private val secondPin = session("second-pin", 40)
    private val newest = session("newest", 30)
    private val middle = session("middle", 20)
    private val oldest = session("oldest", 10)

    private fun session(name: String, epoch: Long) = TmuxSession(
        hostId = "v",
        name = name,
        windows = 1,
        attachedClients = 0,
        lastActivityEpochSeconds = epoch,
    )

    private fun advance(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun setUpInbox(newestAtBottom: Boolean) {
        f = SessionActivityFixture()
        advance()
        val sessions = listOf(newest, middle, oldest, firstPin, secondPin)
        f.state.value = f.state.value.copy(
            accountLayout = null,
            sessions = sessions,
            sessionInteractionEpochSeconds = sessions.associate {
                SessionReadStore.key(it.hostId, it.name) to it.lastActivityEpochSeconds
            },
            pinnedSessionKeys = listOf(PinnedChats.key(firstPin), PinnedChats.key(secondPin)),
            newestSessionsAtBottom = newestAtBottom,
        )
    }

    private fun names() = f.model.state.value.readerSwipeOrder.map { it.substringAfterLast("::") }

    @After fun cleanup() {
        if (this::f.isInitialized) f.close()
    }

    @Test
    fun swipingForwardFromTheTopPinnedRowOpensTheRowBelowIt() {
        setUpInbox(newestAtBottom = true)
        f.model.openReader(firstPin)

        assertEquals(listOf("first-pin", "second-pin", "oldest", "middle", "newest"), names())
        f.model.openAdjacentReaderSession(1)
        assertEquals("second-pin", f.model.state.value.readerSession?.name)
    }

    @Test
    fun swipingForwardFromAnUnpinnedRowOpensTheRowBelowIt() {
        setUpInbox(newestAtBottom = true)
        f.model.openReader(middle)

        assertEquals(listOf("first-pin", "second-pin", "oldest", "middle", "newest"), names())
        f.model.openAdjacentReaderSession(1)
        assertEquals("newest", f.model.state.value.readerSession?.name)
    }

    @Test
    fun swipingBackwardFromTheBottomRowOfNewestAtTopOpensTheRowAboveIt() {
        setUpInbox(newestAtBottom = false)
        f.model.openReader(oldest)

        assertEquals(listOf("first-pin", "second-pin", "newest", "middle", "oldest"), names())
        f.model.openAdjacentReaderSession(-1)
        assertEquals("middle", f.model.state.value.readerSession?.name)
    }

    @Test
    fun archivingALowerPinnedRowOpensTheUnpinnedRowBelowIt() {
        setUpInbox(newestAtBottom = true)
        f.model.openReader(secondPin)

        f.model.archiveReaderUntilAndOpenNext(null)
        assertEquals("oldest", f.model.state.value.readerSession?.name)
    }

    @Test
    fun archivingALowerPinnedRowOpensTheRowBelowItWhenNewestSitsAtTop() {
        setUpInbox(newestAtBottom = false)
        f.model.openReader(secondPin)

        f.model.archiveReaderUntilAndOpenNext(null)
        assertEquals("newest", f.model.state.value.readerSession?.name)
    }
}

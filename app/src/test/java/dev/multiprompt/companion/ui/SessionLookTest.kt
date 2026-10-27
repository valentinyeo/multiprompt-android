package dev.multiprompt.companion.ui

import android.app.Application
import android.os.Looper
import dev.multiprompt.companion.data.SessionReadStore
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SessionLookTest {
    private lateinit var f: SessionActivityFixture
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun visits() = f.model.state.value.accountLayout!!.tabs.first().visits.orEmpty()

    @Before fun setup() { f = SessionActivityFixture(); advance(0) }
    @After fun cleanup() { f.close() }

    @Test fun explicitUserReaderOpenRecordsOneLookAfterTenFocusedSecondsUsingStartTime() {
        f.foreground.value = true
        val beforeOpen = System.currentTimeMillis()
        f.model.openReader(f.session, userInitiated = true)
        advance(0)
        val afterOpen = System.currentTimeMillis()
        advance(9_000)
        assertTrue(visits().isEmpty())
        advance(1_000)
        assertEquals(1, visits().size)
        assertTrue("look value must be the front start, not dwell completion", visits().single() in beforeOpen..afterOpen)
        advance(20_000)
        assertEquals(1, visits().size)
        assertNull(f.model.state.value.accountLayout!!.tabs.first().input)
        assertNull(f.model.state.value.accountLayout!!.tabs.first().used)
    }

    @Test fun explicitUserTerminalOpenQualifiesButSwitchingAloneDoesNotStampInputOrUsed() {
        f.foreground.value = true
        f.model.openTerminal(f.session, userInitiated = true)
        advance(0)
        assertNull(f.model.state.value.accountLayout!!.tabs.first().input)
        assertNull(f.model.state.value.accountLayout!!.tabs.first().used)
        advance(10_000)
        assertEquals(1, visits().size)
    }

    @Test fun automaticReaderAndTerminalOpensNeverCountEvenAfterTenForegroundSeconds() {
        f.foreground.value = true
        f.model.openReader(f.session)
        advance(11_000)
        assertTrue(visits().isEmpty())
        f.model.openTerminal(f.session)
        advance(11_000)
        assertTrue(visits().isEmpty())
        assertNull(f.model.state.value.accountLayout!!.tabs.first().used)
    }

    @Test fun readerAndTerminalStepJumpsNeverQualifyTheirNewFrontSession() {
        f.model.selectWorkspace(null)
        for (terminal in listOf(false, true)) {
            f.foreground.value = true
            if (terminal) f.model.openTerminal(f.session, userInitiated = true)
            else f.model.openReader(f.session, userInitiated = true)
            advance(9_000)
            if (terminal) f.model.openAdjacentSession(1) else f.model.openAdjacentReaderSession(1)
            advance(11_000)
            assertTrue(f.model.state.value.accountLayout!!.tabs.all { it.visits.isNullOrEmpty() && it.input == null && it.used == null })
        }
    }

    @Test fun unfocusedDwellNeverCountsAndPauseCancelsRatherThanAccumulatesTime() {
        f.model.openReader(f.session, userInitiated = true)
        advance(20_000)
        assertTrue(visits().isEmpty())
        f.foreground.value = true
        advance(9_000)
        f.model.onPause()
        advance(20_000)
        assertTrue(visits().isEmpty())
        f.foreground.value = true
        advance(9_000)
        assertTrue(visits().isEmpty())
        advance(1_000)
        assertEquals(1, visits().size)
    }

    @Test fun unfocusedAutomaticOpenDoesNotBecomeALookWhenAppRegainsFocus() {
        f.model.openReader(f.session)
        advance(20_000)
        assertTrue(visits().isEmpty())
        f.foreground.value = true
        advance(20_000)
        assertTrue(visits().isEmpty())
    }

    @Test fun restoredSessionStateWithoutExplicitUserSelectionNeverCounts() {
        f.foreground.value = true
        f.state.value = f.state.value.copy(readerSession = f.session)
        advance(20_000)
        assertTrue(visits().isEmpty())
        assertNotNull(SessionReadStore.key(f.session.hostId, f.session.name))
    }
}

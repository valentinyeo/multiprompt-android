package dev.multiprompt.companion.computer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerViewerTest {
    private class FakeTunnel(override val localPort: Int) : ComputerTunnel {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    private val published = mutableListOf<ComputerState>()

    @Test fun sleepIsWhereItStartsSoNothingHoldsAVpsConnection() {
        val viewer = ComputerViewer(opener = { null }, publish = { published += it })
        assertEquals(ComputerStatus.Sleeping, viewer.current.status)
        assertTrue(published.isEmpty())
    }

    @Test fun wakeRetriesOnceThenGoesLiveAndSleepClosesTheTunnel() = runBlocking {
        val tunnel = FakeTunnel(45123)
        var calls = 0
        val viewer = ComputerViewer(
            opener = { calls++; if (calls == 1) null else tunnel },
            publish = { published += it },
            retries = 1,
        )
        viewer.wake(ComputerTarget("127.0.0.1", 6080))
        assertEquals(
            listOf(ComputerStatus.Connecting, ComputerStatus.Reconnecting, ComputerStatus.Live),
            published.map { it.status },
        )
        assertEquals(45123, viewer.current.localPort)
        assertTrue(published[0].localPort == null)
        viewer.sleep()
        assertTrue(tunnel.closed)
        assertEquals(ComputerStatus.Sleeping, viewer.current.status)
        assertNull(viewer.current.localPort)
    }

    @Test fun aViewerThatNeverAnswersEndsLostWithAReason() = runBlocking {
        val viewer = ComputerViewer(opener = { null }, publish = { published += it }, retries = 1)
        viewer.wake(ComputerTarget.Default)
        assertEquals(
            listOf(ComputerStatus.Connecting, ComputerStatus.Reconnecting, ComputerStatus.Lost),
            published.map { it.status },
        )
        assertEquals("Cannot reach the VPS", viewer.current.failure)
    }

    @Test fun aChatThatDropsItsConnectionNeverLeavesADeadPictureOnScreen() = runBlocking {
        val lost = FakeTunnel(42000)
        var reopen = false
        val viewer = ComputerViewer(
            opener = { if (reopen) FakeTunnel(42001) else lost },
            publish = { published += it },
            retries = 0,
        )
        viewer.wake(ComputerTarget.Default)
        assertEquals(ComputerStatus.Live, viewer.current.status)
        viewer.invalidate()
        assertTrue(lost.closed)
        assertEquals(ComputerStatus.Reconnecting, viewer.current.status)
        assertEquals(42000, viewer.current.localPort)
        reopen = true
        viewer.wake(ComputerTarget.Default)
        assertEquals(ComputerStatus.Live, viewer.current.status)
        assertEquals(42001, viewer.current.localPort)
    }

    @Test fun wakingASecondTimeAfterALossReconnectsInsteadOfConnecting() = runBlocking {
        var fail = true
        val viewer = ComputerViewer(
            opener = { if (fail) null else FakeTunnel(41000) },
            publish = { published += it },
            retries = 0,
        )
        viewer.wake(ComputerTarget.Default)
        assertEquals(ComputerStatus.Lost, viewer.current.status)
        fail = false
        viewer.wake(ComputerTarget.Default)
        assertEquals(ComputerStatus.Live, viewer.current.status)
        assertEquals(41000, viewer.current.localPort)
    }
}

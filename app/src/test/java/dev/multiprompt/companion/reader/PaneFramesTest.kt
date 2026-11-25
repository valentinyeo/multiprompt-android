package dev.multiprompt.companion.reader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PaneFramesTest {
    private class Host {
        var connects = 0
        var releases = 0
        var channels = 0
        var frames = 0
    }

    private fun TestScope.reader(host: Host, screen: ChatPollGate, pane: ChatPollGate) {
        val pump = ReaderStreamPump<Int>(
            wanted = screen.active,
            connect = {
                host.connects++
                host.connects
            },
            stream = { _, heard ->
                paneFrames(pane.active) {
                    host.channels++
                    while (true) {
                        heard()
                        host.frames++
                        delay(1_000L)
                    }
                }
            },
            release = { host.releases++ },
            onClient = {},
            onConnecting = {},
            onFailure = {},
            reconnectDelayMillis = 3_000L,
            reuseFirstBytesMillis = 2_000L,
        )
        backgroundScope.launch { pump.pump() }
    }

    @Test
    fun theChatViewKeepsTheConnectionAndOpensNoPaneChannel() = runTest {
        val chat = ChatPollGate()
        val pane = ChatPollGate()
        val host = Host()
        val lease = chat.acquire()
        reader(host, chat, pane)
        runCurrent()
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(1, host.connects)
        assertEquals(0, host.channels)
        assertEquals(0, host.frames)
        assertEquals(0, host.releases)
        lease.close()
        runCurrent()
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(0, host.frames)
        assertEquals(0, host.releases)
    }

    @Test
    fun thePaneReaderOpensItsChannelAtOnce() = runTest {
        val chat = ChatPollGate()
        val pane = ChatPollGate()
        val host = Host()
        chat.acquire()
        pane.acquire()
        reader(host, chat, pane)
        runCurrent()
        assertEquals(1, host.connects)
        assertEquals(1, host.channels)
        assertEquals(1, host.frames)
        advanceTimeBy(5_000L)
        runCurrent()
        assertTrue(host.frames >= 5)
        assertEquals(1, host.channels)
    }

    @Test
    fun closingThePaneLeaseStopsFramesAndKeepsTheConnection() = runTest {
        val chat = ChatPollGate()
        val pane = ChatPollGate()
        val host = Host()
        chat.acquire()
        val lease = pane.acquire()
        reader(host, chat, pane)
        runCurrent()
        advanceTimeBy(2_500L)
        runCurrent()
        assertTrue(host.frames >= 3)
        lease.close()
        runCurrent()
        val stopped = host.frames
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(stopped, host.frames)
        assertEquals(1, host.channels)
        assertEquals(0, host.releases)
        pane.acquire()
        runCurrent()
        assertEquals(stopped + 1, host.frames)
        assertEquals(2, host.channels)
    }

    @Test
    fun paneFramesServesNothingWithoutAPaneLease() = runTest {
        val pane = ChatPollGate()
        var frames = 0
        backgroundScope.launch {
            paneFrames(pane.active) {
                while (true) {
                    frames++
                    delay(1_000L)
                }
            }
        }
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(0, frames)
        pane.acquire()
        runCurrent()
        assertEquals(1, frames)
    }
}

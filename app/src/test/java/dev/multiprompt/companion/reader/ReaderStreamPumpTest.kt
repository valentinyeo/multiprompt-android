package dev.multiprompt.companion.reader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderStreamPumpTest {
    private class FakeHost {
        var connects = 0
        var releases = 0
        var snapshots = 0
        var openedAt = -1L
        var failNextStream: Throwable? = null
        val clients = mutableListOf<Int>()
        val streamedBy = mutableListOf<Int>()
    }

    private fun TestScope.pump(selected: MutableStateFlow<Boolean>, host: FakeHost, failures: MutableList<Throwable> = mutableListOf()) {
        val pump = ReaderStreamPump(
            selected = selected,
            connect = {
                host.connects++
                host.connects
            },
            stream = { client ->
                host.streamedBy += client
                host.failNextStream?.let {
                    host.failNextStream = null
                    throw it
                }
                while (true) {
                    host.snapshots++
                    delay(1_000L)
                }
            },
            release = { host.releases++ },
            onClient = { client -> if (client != null) host.clients += client },
            onConnecting = {},
            onFailure = { failures += it },
            reconnectDelayMillis = 3_000L,
        )
        backgroundScope.launch { pump.run() }
    }

    @Test
    fun hiddenChatPullsZeroScreenUpdatesInTenSeconds() = runTest {
        val host = FakeHost()
        pump(MutableStateFlow(false), host)
        runCurrent()
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(0, host.snapshots)
        assertEquals(0, host.connects)
    }

    @Test
    fun openingAHiddenChatPullsFreshContentWithinOneSecond() = runTest {
        val host = FakeHost()
        val selected = MutableStateFlow(false)
        pump(selected, host)
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(0, host.snapshots)
        val openedAt = currentTime
        selected.value = true
        runCurrent()
        assertTrue("no snapshot after open", host.snapshots >= 1)
        assertTrue("first snapshot took ${currentTime - openedAt} ms", currentTime - openedAt <= 1_000L)
    }

    @Test
    fun hidingStopsTheStreamAndKeepsTheConnectionWarmForTheNextOpen() = runTest {
        val host = FakeHost()
        val selected = MutableStateFlow(true)
        pump(selected, host)
        advanceTimeBy(3_500L)
        runCurrent()
        assertEquals(1, host.connects)
        selected.value = false
        runCurrent()
        val settled = host.snapshots
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals("hidden chat kept pulling", settled, host.snapshots)
        assertEquals("hiding dropped the connection", 0, host.releases)
        selected.value = true
        runCurrent()
        assertEquals("opening reconnected instead of reusing", 1, host.connects)
        assertEquals(listOf(1, 1), host.streamedBy)
        assertEquals(settled + 1, host.snapshots)
    }

    @Test
    fun deadWarmConnectionReconnectsAtOnceAndWithoutShowingAFailure() = runTest {
        val host = FakeHost()
        val failures = mutableListOf<Throwable>()
        val selected = MutableStateFlow(true)
        pump(selected, host, failures)
        runCurrent()
        selected.value = false
        runCurrent()
        host.failNextStream = IllegalStateException("channel refused")
        val reopenedAt = currentTime
        val before = host.snapshots
        selected.value = true
        runCurrent()
        assertEquals(1, host.releases)
        assertEquals(2, host.connects)
        assertEquals(listOf(1, 1, 2), host.streamedBy)
        assertEquals(before + 1, host.snapshots)
        assertEquals(reopenedAt, currentTime)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun failureOnAFreshConnectionReportsAndWaitsBeforeReconnecting() = runTest {
        val host = FakeHost()
        val failures = mutableListOf<Throwable>()
        host.failNextStream = IllegalStateException("tmux session is no longer available")
        pump(MutableStateFlow(true), host, failures)
        runCurrent()
        assertEquals(1, failures.size)
        assertEquals(1, host.connects)
        advanceTimeBy(2_999L)
        runCurrent()
        assertEquals(1, host.connects)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(2, host.connects)
        assertTrue(host.snapshots >= 1)
    }

    @Test
    fun connectTimeoutWhileVisibleRetriesInsteadOfEndingTheStream() = runTest {
        var attempts = 0
        var streamed = 0
        val failures = mutableListOf<Throwable>()
        val pump = ReaderStreamPump(
            selected = MutableStateFlow(true),
            connect = {
                attempts++
                if (attempts == 1) withTimeout(1L) { delay(10L) }
                attempts
            },
            stream = {
                streamed++
                delay(Long.MAX_VALUE / 4)
            },
            release = {},
            onClient = {},
            onConnecting = {},
            onFailure = { failures += it },
            reconnectDelayMillis = 3_000L,
        )
        backgroundScope.launch { pump.run() }
        advanceTimeBy(2L)
        runCurrent()
        assertEquals(1, failures.size)
        assertEquals(0, streamed)
        advanceTimeBy(3_000L)
        runCurrent()
        assertEquals(2, attempts)
        assertEquals(1, streamed)
    }
}

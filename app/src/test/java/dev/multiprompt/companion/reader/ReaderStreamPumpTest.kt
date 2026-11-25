package dev.multiprompt.companion.reader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderStreamPumpTest {
    private class FakeHost {
        var connects = 0
        var releases = 0
        var snapshots = 0
        var failNextStream: Throwable? = null
        var silentNextReuse = false
        var bytesThenQuietMillis = 0L
        var dropAfterBytes: Throwable? = null
        val clients = mutableListOf<Int>()
        val streamedBy = mutableListOf<Int>()
    }

    private fun TestScope.pump(selected: StateFlow<Boolean>, host: FakeHost, failures: MutableList<Throwable> = mutableListOf()) {
        val pump = ReaderStreamPump<Int>(
            wanted = selected,
            connect = {
                host.connects++
                host.connects
            },
            stream = { client, heard ->
                host.streamedBy += client
                val reuse = host.streamedBy.count { it == client } > 1
                if (host.silentNextReuse && reuse) {
                    host.silentNextReuse = false
                    delay(Long.MAX_VALUE / 4)
                }
                host.failNextStream?.let {
                    host.failNextStream = null
                    throw it
                }
                if (host.bytesThenQuietMillis > 0L && reuse) {
                    val quiet = host.bytesThenQuietMillis
                    host.bytesThenQuietMillis = 0L
                    heard()
                    delay(quiet)
                }
                host.dropAfterBytes?.let {
                    host.dropAfterBytes = null
                    heard()
                    host.snapshots++
                    throw it
                }
                while (true) {
                    heard()
                    host.snapshots++
                    delay(1_000L)
                }
            },
            release = { host.releases++ },
            onClient = { client -> if (client != null) host.clients += client },
            onConnecting = {},
            onFailure = { failures += it },
            reconnectDelayMillis = 3_000L,
            reuseFirstBytesMillis = 2_000L,
        )
        backgroundScope.launch { pump.pump() }
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
        val pump = ReaderStreamPump<Int>(
            wanted = MutableStateFlow(true),
            connect = {
                attempts++
                if (attempts == 1) withTimeout(1L) { delay(10L) }
                attempts
            },
            stream = { _, _ ->
                streamed++
                delay(Long.MAX_VALUE / 4)
            },
            release = {},
            onClient = {},
            onConnecting = {},
            onFailure = { failures += it },
            reconnectDelayMillis = 3_000L,
            reuseFirstBytesMillis = 2_000L,
        )
        backgroundScope.launch { pump.pump() }
        advanceTimeBy(2L)
        runCurrent()
        assertEquals(1, failures.size)
        assertEquals(0, streamed)
        advanceTimeBy(3_000L)
        runCurrent()
        assertEquals(2, attempts)
        assertEquals(1, streamed)
    }

    @Test
    fun historyRepliesKeepAResumedChatWarmWithoutPaneFrames() = runTest {
        val host = FakeHost()
        val gate = ChatPollGate()
        var historyReply: (() -> Unit)? = null
        val pump = ReaderStreamPump<Int>(
            wanted = gate.active,
            connect = { ++host.connects },
            stream = { _, heard -> historyReply = heard; delay(Long.MAX_VALUE) },
            release = { host.releases++ },
            onClient = {},
            onConnecting = {},
            onFailure = { throw it },
            reconnectDelayMillis = 3_000L,
            reuseFirstBytesMillis = 2_000L,
        )
        backgroundScope.launch { pump.pump() }
        val lease = gate.acquire()
        runCurrent()
        requireNotNull(historyReply).invoke()
        lease.close()
        runCurrent()
        gate.acquire()
        runCurrent()
        advanceTimeBy(500L)
        requireNotNull(historyReply).invoke()
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(1, host.connects)
        assertEquals(0, host.releases)
        assertEquals(0, host.snapshots)
        assertEquals(2, pump.streamStarts)
    }

    @Test
    fun silentWarmConnectionIsReplacedWithinTwoSecondsOfOpening() = runTest {
        val host = FakeHost()
        val failures = mutableListOf<Throwable>()
        val selected = MutableStateFlow(true)
        pump(selected, host, failures)
        runCurrent()
        selected.value = false
        runCurrent()
        host.silentNextReuse = true
        val reopenedAt = currentTime
        selected.value = true
        runCurrent()
        assertEquals(0, host.releases)
        advanceTimeBy(1_999L)
        runCurrent()
        assertEquals(0, host.releases)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, host.releases)
        assertEquals(2, host.connects)
        assertTrue("reconnected after ${currentTime - reopenedAt} ms", currentTime - reopenedAt <= 2_000L)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun firstFramePastTwoSecondsKeepsTheWarmConnectionWhenBytesArrivedEarly() = runTest {
        val host = FakeHost()
        val failures = mutableListOf<Throwable>()
        val selected = MutableStateFlow(true)
        pump(selected, host, failures)
        runCurrent()
        selected.value = false
        runCurrent()
        host.bytesThenQuietMillis = 6_000L
        val before = host.snapshots
        selected.value = true
        advanceTimeBy(5_900L)
        runCurrent()
        assertEquals(0, host.releases)
        assertEquals(1, host.connects)
        assertEquals(before, host.snapshots)
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(before + 1, host.snapshots)
        assertEquals(0, host.releases)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun copyModeWithNoSnapshotKeepsTheWarmConnection() = runTest {
        val host = FakeHost()
        val failures = mutableListOf<Throwable>()
        val selected = MutableStateFlow(true)
        pump(selected, host, failures)
        runCurrent()
        selected.value = false
        runCurrent()
        host.bytesThenQuietMillis = Long.MAX_VALUE / 4
        selected.value = true
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(0, host.releases)
        assertEquals(1, host.connects)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun reusedStreamThatDropsAfterItsFirstFrameRestartsOnTheSameConnectionAtOnce() = runTest {
        val host = FakeHost()
        val failures = mutableListOf<Throwable>()
        val selected = MutableStateFlow(true)
        pump(selected, host, failures)
        runCurrent()
        selected.value = false
        runCurrent()
        host.dropAfterBytes = IllegalStateException("tmux session ended")
        selected.value = true
        runCurrent()
        assertEquals(1, failures.size)
        assertEquals(0, host.releases)
        advanceTimeBy(2_999L)
        runCurrent()
        assertEquals("a warm connection that already answered is not replaced", 1, host.connects)
        assertEquals(1, host.connects)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, host.connects)
        assertEquals(listOf(1, 1, 1), host.streamedBy)
        assertTrue(host.snapshots > 0)
    }

    @Test
    fun hidingDuringConnectDoesNotMarkFailure() = runTest {
        var attempts = 0
        var snapshots = 0
        val failures = mutableListOf<Throwable>()
        val selected = MutableStateFlow(true)
        val pump = ReaderStreamPump<Int>(
            wanted = selected,
            connect = {
                attempts++
                if (attempts == 1) delay(500L)
                attempts
            },
            stream = { _, heard ->
                heard()
                snapshots++
                delay(Long.MAX_VALUE / 4)
            },
            release = {},
            onClient = {},
            onConnecting = {},
            onFailure = { failures += it },
            reconnectDelayMillis = 3_000L,
            reuseFirstBytesMillis = 2_000L,
        )
        backgroundScope.launch { pump.pump() }
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(0, snapshots)
        selected.value = false
        runCurrent()
        assertTrue(failures.isEmpty())
        selected.value = true
        runCurrent()
        assertTrue(failures.isEmpty())
        assertEquals(2, attempts)
        assertEquals(1, snapshots)
    }

    @Test
    fun twoVisiblePumpsBothStreamConcurrently() = runTest {
        val hostA = FakeHost()
        val hostB = FakeHost()
        val gateA = ChatPollGate()
        val gateB = ChatPollGate()
        gateA.acquire()
        gateB.acquire()
        backgroundScope.launch {
            ReaderStreamPump(
                wanted = gateA.active,
                connect = { hostA.connects++; hostA.connects },
                stream = { _, heard ->
                    heard()
                    hostA.snapshots++
                    delay(Long.MAX_VALUE / 4)
                },
                release = {},
                onClient = {},
                onConnecting = {},
                onFailure = {},
                reconnectDelayMillis = 3_000L,
                reuseFirstBytesMillis = 2_000L,
            ).pump()
        }
        backgroundScope.launch {
            ReaderStreamPump(
                wanted = gateB.active,
                connect = { hostB.connects++; hostB.connects + 100 },
                stream = { _, heard ->
                    heard()
                    hostB.snapshots++
                    delay(Long.MAX_VALUE / 4)
                },
                release = {},
                onClient = {},
                onConnecting = {},
                onFailure = {},
                reconnectDelayMillis = 3_000L,
                reuseFirstBytesMillis = 2_000L,
            ).pump()
        }
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(1, hostA.connects)
        assertEquals(1, hostB.connects)
        assertEquals(1, hostA.snapshots)
        assertEquals(1, hostB.snapshots)
    }

    @Test
    fun anObservedModelCommandResultCannotExpireOrPressEnterLater() = runTest {
        val confirmation = ModelSwitchConfirmation({ currentTime }, 25_000L)
        confirmation.begin()
        confirmation.complete()
        advanceTimeBy(25_001L)
        assertFalse(confirmation.pending.value)
        assertFalse(confirmation.expire())
        assertFalse(confirmation.shouldPress(true))
        confirmation.begin()
        assertTrue(confirmation.shouldPress(true))
    }

    @Test
    fun pendingConfirmationCannotKeepAHiddenChatPolling() = runTest {
        val host = FakeHost()
        val confirmation = ModelSwitchConfirmation({ currentTime }, 25_000L)
        val gate = ChatPollGate()
        val first = gate.acquire()
        val second = gate.acquire()
        pump(gate.active, host)
        runCurrent()
        confirmation.begin()
        second.close()
        advanceTimeBy(3_000L)
        runCurrent()
        assertTrue(host.snapshots > 1)
        first.close()
        runCurrent()
        val settled = host.snapshots
        advanceTimeBy(25_001L)
        runCurrent()
        assertEquals("HIDDEN_READER_IO: route reads through ChatPollGate", settled, host.snapshots)
        assertTrue(confirmation.expire())
        gate.acquire()
        runCurrent()
        assertEquals(settled + 1, host.snapshots)
        assertEquals(1, host.connects)
    }

    @Test
    fun oneOpenChatKeepsOneReaderAndOneLoginWhenTheGateDropsAndRetakesInOneTurn() = runTest {
        val host = FakeHost()
        val gate = ChatPollGate()
        val pump = ReaderStreamPump<Int>(
            wanted = gate.active,
            connect = {
                host.connects++
                host.connects
            },
            stream = { client, heard ->
                host.streamedBy += client
                while (true) {
                    heard()
                    host.snapshots++
                    delay(1_000L)
                }
            },
            release = { host.releases++ },
            onClient = { client -> if (client != null) host.clients += client },
            onConnecting = {},
            onFailure = {},
            reconnectDelayMillis = 3_000L,
            reuseFirstBytesMillis = 2_000L,
        )
        backgroundScope.launch { pump.pump() }
        var lease = gate.acquire()
        runCurrent()
        repeat(120) {
            lease.close()
            lease = gate.acquire()
            advanceTimeBy(500L)
        }
        runCurrent()
        assertEquals("reader starts per minute", 1, host.streamedBy.size)
        assertEquals("logins per minute", 1, host.connects)
        assertEquals(1, pump.streamStarts)
        assertEquals(1, pump.connects)
        assertEquals(0, host.releases)
        assertTrue("reader stopped sending", host.snapshots >= 60)
        lease.close()
    }

    @Test
    fun oneOpenChatKeepsOneLoginAcrossARealMinuteOfHidesAndReopens() = runTest {
        val host = FakeHost()
        val gate = ChatPollGate()
        val pump = ReaderStreamPump<Int>(
            wanted = gate.active,
            connect = {
                host.connects++
                host.connects
            },
            stream = { client, heard ->
                host.streamedBy += client
                while (true) {
                    heard()
                    host.snapshots++
                    delay(1_000L)
                }
            },
            release = { host.releases++ },
            onClient = { client -> if (client != null) host.clients += client },
            onConnecting = {},
            onFailure = {},
            reconnectDelayMillis = 3_000L,
            reuseFirstBytesMillis = 2_000L,
        )
        backgroundScope.launch { pump.pump() }
        var lease = gate.acquire()
        runCurrent()
        repeat(6) {
            advanceTimeBy(5_000L)
            lease.close()
            runCurrent()
            advanceTimeBy(5_000L)
            lease = gate.acquire()
            runCurrent()
        }
        runCurrent()
        assertEquals("reader starts per minute of six hides", 7, host.streamedBy.size)
        assertEquals("logins per minute of six hides", 1, host.connects)
        assertEquals(0, host.releases)
        lease.close()
    }

    @Test
    fun oneOpenChatKeepsOneReaderAndOneLoginForAMinuteAfterARealDrop() = runTest {
        val host = FakeHost()
        val failures = mutableListOf<Throwable>()
        host.dropAfterBytes = IllegalStateException("tmux session ended")
        val pump = ReaderStreamPump<Int>(
            wanted = MutableStateFlow(true),
            connect = {
                host.connects++
                host.connects
            },
            stream = { client, heard ->
                host.streamedBy += client
                host.dropAfterBytes?.let {
                    host.dropAfterBytes = null
                    heard()
                    host.snapshots++
                    throw it
                }
                while (true) {
                    heard()
                    host.snapshots++
                    delay(1_000L)
                }
            },
            release = { host.releases++ },
            onClient = { client -> if (client != null) host.clients += client },
            onConnecting = {},
            onFailure = { failures += it },
            reconnectDelayMillis = 3_000L,
            reuseFirstBytesMillis = 2_000L,
        )
        backgroundScope.launch { pump.pump() }
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals("logins per minute", 1, host.connects)
        assertEquals(1, failures.size)
        assertTrue("reader stopped after the drop", host.snapshots >= 55)
        assertTrue("reader restarted after the drop", pump.streamStarts >= 2)
    }
}

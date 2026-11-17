package dev.multiprompt.companion.reader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPollGateTest {
    @Test
    fun closingAnotherObserverDoesNotHideTheVisibleReader() {
        val gate = ChatPollGate()
        assertFalse(gate.active.value)
        val first = gate.acquire()
        val second = gate.acquire()
        second.close()
        second.close()
        assertTrue(gate.active.value)
        first.close()
        assertFalse(gate.active.value)
        gate.acquire().use { assertTrue(gate.active.value) }
        assertFalse(gate.active.value)
    }

    @Test
    fun selectionGenerationsAreNotLostWhenAScreenClosesAndReopensAtOnce() {
        val gate = ChatPollGate()
        val changes = mutableListOf<Boolean>()
        gate.onChange { changes += it }.use {
            gate.acquire().close()
            gate.acquire()
            gate.close()
        }
        assertEquals(listOf(false, true, false, true, false), changes)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancelledReadsObserveTheReleasedSelectionBeforeCompletingAnAction() = runTest(UnconfinedTestDispatcher()) {
        val gate = ChatPollGate()
        var selected = false
        gate.onChange { selected = it }
        var selectedAtCancellation: Boolean? = null
        val reader = gate.launchIn(backgroundScope) {
            try {
                awaitCancellation()
            } finally {
                selectedAtCancellation = selected
            }
        }
        val lease = gate.acquire()
        runCurrent()
        lease.close()
        runCurrent()
        assertEquals(false, selectedAtCancellation)
        reader.cancel()
    }

    @Test
    fun closingTheOwnerInvalidatesAllLeases() {
        val gate = ChatPollGate()
        val lease = gate.acquire()
        gate.close()
        lease.close()
        assertFalse(gate.active.value)
        gate.acquire().use { assertFalse(gate.active.value) }
        assertFalse(gate.active.value)
    }
}

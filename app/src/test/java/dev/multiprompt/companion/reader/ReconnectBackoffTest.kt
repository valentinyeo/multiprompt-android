package dev.multiprompt.companion.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectBackoffTest {
    @Test
    fun waitDoublesToThirtySecondsAndStaysThere() {
        var delay = ReconnectBackoff.INITIAL_MS
        val seen = mutableListOf(delay)
        repeat(6) {
            delay = ReconnectBackoff.afterWait(delay, nudged = false)
            seen += delay
        }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), seen)
    }

    @Test
    fun nudgeAfterFiveFailedTriesResetsTheWaitToTheStart() {
        var delay = ReconnectBackoff.INITIAL_MS
        repeat(5) { delay = ReconnectBackoff.afterWait(delay, nudged = false) }
        assertEquals(ReconnectBackoff.MAX_MS, delay)
        assertEquals(ReconnectBackoff.INITIAL_MS, ReconnectBackoff.afterWait(delay, nudged = true))
    }

    @Test
    fun healthyConnectionIsNeverNudged() {
        assertFalse(ReconnectBackoff.shouldNudge(retrying = false))
        assertTrue(ReconnectBackoff.shouldNudge(retrying = true))
    }

    @Test
    fun severalCallbacksInsideTwoSecondsGiveOneNudge() {
        val gate = NetworkNudgeGate()
        assertTrue(gate.onNetwork(1L, validated = true, nowMs = 10_000L))
        assertFalse(gate.onNetwork(2L, validated = true, nowMs = 10_400L))
        assertFalse(gate.onNetwork(3L, validated = true, nowMs = 11_999L))
        assertTrue(gate.onNetwork(3L, validated = true, nowMs = 12_000L))
    }

    @Test
    fun capabilityChurnOnTheSameNetworkDoesNotNudgeAgain() {
        val gate = NetworkNudgeGate()
        assertTrue(gate.onNetwork(1L, validated = true, nowMs = 0L))
        assertFalse(gate.onNetwork(1L, validated = true, nowMs = 5_000L))
        assertFalse(gate.onNetwork(1L, validated = true, nowMs = 60_000L))
    }

    @Test
    fun anUnvalidatedNetworkNeverNudgesButValidationLaterDoes() {
        val gate = NetworkNudgeGate()
        assertFalse(gate.onNetwork(1L, validated = false, nowMs = 0L))
        assertTrue(gate.onNetwork(1L, validated = true, nowMs = 300L))
    }

    @Test
    fun losingTheNetworkAllowsTheNextReturnToNudge() {
        val gate = NetworkNudgeGate()
        assertTrue(gate.onNetwork(1L, validated = true, nowMs = 0L))
        assertFalse(gate.onNetwork(1L, validated = false, nowMs = 100L))
        assertTrue(gate.onNetwork(1L, validated = true, nowMs = 5_000L))
    }

    @Test
    fun aCallbackBlockedByTheWindowIsNotRememberedAsHandled() {
        val gate = NetworkNudgeGate()
        assertTrue(gate.onNetwork(1L, validated = true, nowMs = 0L))
        assertFalse(gate.onNetwork(2L, validated = true, nowMs = 1_000L))
        assertTrue(gate.onNetwork(2L, validated = true, nowMs = 2_500L))
    }

    @Test
    fun returningToTheAppSharesTheWindowWithNetworkCallbacks() {
        val gate = NetworkNudgeGate()
        assertTrue(gate.onResume(nowMs = 5_000L))
        assertFalse(gate.onNetwork(1L, validated = true, nowMs = 5_300L))
        assertFalse(gate.onResume(nowMs = 6_000L))
        assertTrue(gate.onResume(nowMs = 7_000L))
    }

    @Test
    fun resetForgetsTheValidatedNetwork() {
        val gate = NetworkNudgeGate()
        assertTrue(gate.onNetwork(1L, validated = true, nowMs = 0L))
        gate.reset()
        assertTrue(gate.onNetwork(1L, validated = true, nowMs = 3_000L))
    }
}

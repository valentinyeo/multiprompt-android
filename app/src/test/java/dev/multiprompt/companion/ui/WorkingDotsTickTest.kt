package dev.multiprompt.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkingDotsTickTest {
    @Test
    fun oneDotLightsAtATimeAndMovesAlong() {
        listOf(0 to 0, 1 to 1, 2 to 2, 3 to 0, 4 to 1).forEach { (phase, lit) ->
            val alphas = (0..2).map { workingDotAlpha(it, phase) }
            assertEquals(1, alphas.count { it == 0.85f })
            assertEquals(0.85f, alphas[lit])
        }
    }

    @Test
    fun theDotsStepFastEnoughToLookAlive() {
        assertTrue(WORKING_DOT_STEP_MS in 200L..400L)
    }
}

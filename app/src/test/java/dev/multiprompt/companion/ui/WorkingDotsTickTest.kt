package dev.multiprompt.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkingDotsTickTest {
    @Test
    fun oneDotLightsAtATimeAndMovesAlong() {
        listOf(0f to 0, 0.5f to 0, 1.2f to 1, 2.9f to 2, 3f to 2).forEach { (phase, lit) ->
            val alphas = (0..2).map { workingDotAlpha(it, phase) }
            assertEquals(1, alphas.count { it == 0.85f })
            assertEquals(0.85f, alphas[lit])
        }
    }
}

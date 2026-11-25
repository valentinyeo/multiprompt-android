package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.ssh.SshRepository
import dev.multiprompt.companion.ssh.TmuxText
import dev.multiprompt.companion.ui.isWorking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneFrameStateTest {
    private fun frame(pane: String): SshRepository.StreamSnapshot = SshRepository.StreamSnapshot(
        output = pane,
        screenOutput = pane,
        details = TmuxText.runtimeDetails(pane),
        pickerOptions = TmuxText.modelPickerOptions(pane),
        waitingForInput = TmuxText.isWaitingForInput(pane, AGENT),
        alternateOn = false,
        confirmationVisible = false,
        eta = null,
        hookState = null,
    )

    private fun wholePaneWorking(pane: String): Boolean =
        TmuxText.readerBlocks(pane, AGENT).any {
            it.kind == TmuxText.ReaderBlockKind.PROGRESS && isTransientProgress(it.text)
        }

    @Test
    fun sixtyIdenticalFramesEmitOneState() {
        val pane = quietPane()
        val snapshot = frame(pane)
        val state = MutableStateFlow(ReaderState())
        var emissions = 0
        repeat(60) { index ->
            var emitted: ReaderState? = null
            state.update { current ->
                nextPaneState(current, snapshot, AGENT, null, CLOCK + index).also {
                    if (it != null) {
                        emissions++
                        emitted = it
                    }
                } ?: current
            }
            if (index > 0) assertNull("identical frame ${index + 1} must not emit", emitted)
        }

        assertEquals(1, emissions)
        assertEquals(pane, state.value.screenOutput)
        val before = state.value
        assertNull(nextPaneState(before, snapshot, AGENT, null, CLOCK + 100))
        assertSame(before, state.value)
    }

    @Test
    fun aChangedLastLineEmitsExactlyOnce() {
        val pane = quietPane()
        val state = MutableStateFlow(ReaderState())
        state.value = requireNotNull(nextPaneState(state.value, frame(pane), AGENT, null, CLOCK))
        val changed = pane.replaceLastLine("  Reading file app/src/main/reader/PaneWorking.kt")

        val first = nextPaneState(state.value, frame(changed), AGENT, null, CLOCK + 1)
        assertNotNull("a changed last line must emit", first)
        state.value = requireNotNull(first)
        assertNull("the same changed pane must not emit twice", nextPaneState(state.value, frame(changed), AGENT, null, CLOCK + 2))
        assertTrue(state.value.working)
    }

    @Test
    fun everyChangedFrameEmitsOnceAndCostsOneState() {
        val state = MutableStateFlow(ReaderState())
        var emissions = 0
        repeat(30) { index ->
            val pane = quietPane(index)
            var emitted: ReaderState? = null
            state.update { current ->
                nextPaneState(current, frame(pane), AGENT, null, CLOCK + index).also {
                    if (it != null) emissions++
                    emitted = it
                } ?: current
            }
            assertNotNull("changed frame ${index + 1} must emit", emitted)
        }

        assertEquals(30, emissions)
    }

    @Test
    fun theWorkingBooleanMatchesTheWholePaneDetection() {
        val panes = listOf(workingPane(), quietPane(), spinnerOnlyPane(), piWorkingPane())
        panes.forEach { pane ->
            assertEquals(
                "working must match the old whole-pane read for ${pane.lineSequence().last()}",
                wholePaneWorking(pane),
                paneWorking(pane, AGENT),
            )
        }
        assertTrue(paneWorking(workingPane(), AGENT))
        assertFalse(paneWorking(quietPane(), AGENT))
        assertFalse(paneWorking(spinnerOnlyPane(), AGENT))
        assertTrue(paneWorking(piWorkingPane(), AgentKind.PI))
    }

    @Test
    fun theWorkingReadOnlyParsesThePaneTail() {
        val pane = "  Reading file app/src/main/Foo.kt\n\n" + quietRows(WORKING_TAIL_LINES + 50)
        assertTrue(wholePaneWorking(pane))
        assertFalse(paneWorking(pane, AGENT))
        assertEquals(WORKING_TAIL_LINES, tailLines(pane, WORKING_TAIL_LINES).lineSequence().count())
        assertEquals(pane, tailLines(pane, WORKING_TAIL_LINES + 500))
    }

    @Test
    fun theFrameCarriesTheWorkingBoolean() {
        val working = frame(workingPane())
        val state = requireNotNull(nextPaneState(ReaderState(), working, AGENT, null, CLOCK))
        assertTrue(state.working)
        assertTrue(state.isWorking())

        val done = requireNotNull(nextPaneState(state, frame(quietPane()), AGENT, null, CLOCK + 1))
        assertFalse(done.working)
        assertFalse(done.isWorking())
    }

    @Test
    fun theUiReadsTheBooleanAndTheHookStateOnly() {
        assertTrue(ReaderState(working = true).isWorking())
        assertTrue(ReaderState(sending = true, working = false).isWorking())
        assertFalse(ReaderState(working = true, hookState = TmuxText.HookState(false, 0)).isWorking())
        assertTrue(ReaderState(hookState = TmuxText.HookState(true, 0), working = false).isWorking())
        assertFalse(ReaderState(screenOutput = "Working…", output = "Working…").isWorking())
    }

    @Test
    fun anUnchangedFrameStillEmitsWhenTheServerSignalMoves() {
        val pane = quietPane()
        val state = ReaderState(status = ReaderStatus.Live, screenOutput = pane, output = pane)
        val hooked = frame(pane).copy(hookState = TmuxText.HookState(active = true, atEpochSeconds = 10))
        val next = requireNotNull(nextPaneState(state, hooked, AGENT, null, CLOCK))
        assertTrue(next.working)
        assertTrue(next.isWorking())
        assertEquals(CLOCK, next.lastUpdatedAtMillis)
    }

    @Test
    fun anUnchangedFrameDoesNotEmitWhileAFailureIsShowing() {
        val pane = quietPane()
        val state = ReaderState(status = ReaderStatus.Live, screenOutput = pane, output = pane, lastFailure = "gone")
        val next = requireNotNull(nextPaneState(state, frame(pane), AGENT, null, CLOCK))
        assertNull(next.lastFailure)
        assertEquals(ReaderStatus.Live, next.status)
    }

    private fun quietPane(marker: Int = 0): String = buildString {
        appendLine("● Done and already proven: the auto-park check is live in the 15-minute cron.")
        appendLine("  ✓ TmuxText.kt:12 parsed, 12 ms")
        appendLine("  ⎿ read 3000 lines of the captured pane")
        appendLine()
        repeat(200) { appendLine("  wrapped prose row $it with ordinary words in it") }
        appendLine("› repeat the last answer $marker")
        append("  a plain final line with ordinary words")
    }

    private fun workingPane(): String = buildString {
        appendLine("● Started on the reader padding.")
        appendLine()
        repeat(120) { appendLine("  wrapped prose row $it with ordinary words in it") }
        append("  Reading file app/src/main/java/dev/multiprompt/companion/ui/WorkingDots.kt")
    }

    private fun spinnerOnlyPane(): String = buildString {
        appendLine("● Started on the reader padding.")
        appendLine()
        repeat(80) { appendLine("  wrapped prose row $it with ordinary words in it") }
        append("✳ Cooked for 1m 39s")
    }

    private fun piWorkingPane(): String = buildString {
        appendLine("● Working on it.")
        appendLine()
        repeat(40) { appendLine("  wrapped prose row $it with ordinary words in it") }
        append("Thinking about the next step")
    }

    private fun quietRows(count: Int): String =
        (0 until count).joinToString("\n") { "  wrapped prose row $it with ordinary words in it" }

    private fun String.replaceLastLine(replacement: String): String =
        substringBeforeLast('\n') + "\n" + replacement

    private companion object {
        const val CLOCK = 1_700_000_000_000L
        val AGENT = AgentKind.CLAUDE
    }
}

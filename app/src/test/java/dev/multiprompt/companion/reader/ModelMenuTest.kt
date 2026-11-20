package dev.multiprompt.companion.reader

import dev.multiprompt.companion.ssh.TmuxText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelMenuTest {
  @Test
  fun theModelRowShowsSonnetWhenTheChatRunsSonnetEvenIfTheSavedPickIsOpus() {
    val settings = HostSettings("opus", "high", "acceptEdits", true)
    val runtime = TmuxText.RuntimeDetails("Sonnet 4.5", "low")
    assertEquals(HostChange.SONNET, ModelMenu.currentModelChange(settings, runtime))
    assertEquals("Sonnet", ModelMenu.modelRowLabel(settings, runtime, null))
    assertTrue(HostChange.SONNET.active(settings, runtime.model, runtime.effort))
    assertFalse(HostChange.OPUS.active(settings, runtime.model, runtime.effort))
  }

  @Test
  fun aDesktopModelChangeShowsThePickedModelBeforeTheTranscriptCatchesUp() {
    val settings = HostSettings("opus", "high", "acceptEdits", true)
    val runtime = TmuxText.RuntimeDetails("Opus 5.5", "high")
    assertEquals(HostChange.SONNET, ModelMenu.currentModelChange(settings, runtime, pendingFamily = "sonnet"))
    assertEquals("Sonnet", ModelMenu.modelRowLabel(settings, runtime, "sonnet"))
  }

  @Test
  fun aFailedModelSwitchShowsAPlainFailureLine() {
    val line = ModelMenu.switchFailureLine("Could not change chat settings")
    assertEquals("Could not change chat settings", line)
    assertNull(ModelMenu.switchFailureLine(null))
  }
}
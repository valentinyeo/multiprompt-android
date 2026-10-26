package dev.multiprompt.companion.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dev.multiprompt.companion.reader.ChatHistoryMessage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SessionNoteChipTest {
    @get:Rule
    val compose = createComposeRule()

    private val longNote = "[from Hax Runner 10] IGSH-975 native journey is now fully green. " +
        "Details follow in a very long machine message that used to fill the whole screen. ".repeat(6) +
        "Run: https://github.com/valentinyeo/multiprompt-android/actions/runs/1"

    private val messages = listOf(
        ChatHistoryMessage("a1", "assistant", "Here is the plan for the release.", "", 0, 1),
        ChatHistoryMessage("n1", "user", longNote, "", 2, 3),
        ChatHistoryMessage("n2", "user", "[from Chief Of Staff] ping about IGSH-1001", "", 4, 5),
        ChatHistoryMessage("n3", "user", "[from Poteto] second short note", "", 6, 7),
        ChatHistoryMessage("u1", "user", "My own typed question", "", 8, 9),
    )

    private fun show() {
        compose.setContent {
            MaterialTheme {
                ChatHistoryList(messages, sunlight = true, sessionName = "Mini Boss CEO", modifier = Modifier.background(Color.White))
            }
        }
    }

    @Test
    fun otherSessionMessagesAreChipsUntilTapped() {
        show()
        compose.onNodeWithTag("session-note-chip-n1").assertIsDisplayed()
        compose.onNodeWithTag("chat-message-u1").assertIsDisplayed()
        compose.onNodeWithTag("chat-message-a1").assertIsDisplayed()
        compose.onNodeWithTag("session-note-body").assertDoesNotExist()
        assertEquals(0, compose.onAllNodesWithTag("session-note-body").fetchSemanticsNodes().size)
        compose.onNode(hasText("Hax Runner 10 to Mini Boss CEO · IGSH-975")).assertIsDisplayed()
        compose.onNode(hasText("Chief Of Staff to Mini Boss CEO · IGSH-1001")).assertIsDisplayed()
        val oneChip = compose.onNodeWithTag("session-note-chip-n1").getUnclippedBoundsInRoot()
        assertTrue("chip height ${oneChip.height}", oneChip.height.value <= 40f)
        val three = listOf("n1", "n2", "n3").sumOf { compose.onNodeWithTag("session-note-chip-$it").getUnclippedBoundsInRoot().height.value.toDouble() }
        assertTrue("three chips ${three}dp", three <= 120.0)
        screenshot("session-note-collapsed.png")
        compose.onNodeWithTag("session-note-toggle-n1").performClick()
        compose.onNodeWithTag("session-note-body").assertIsDisplayed()
        compose.onNode(hasText("Run: https://github.com/valentinyeo/multiprompt-android/actions/runs/1", substring = true)).assertIsDisplayed()
        screenshot("session-note-expanded.png")
        compose.onNodeWithTag("session-note-toggle-n1").performClick()
        compose.onNodeWithTag("session-note-body").assertDoesNotExist()
        screenshot("session-note-folded.png")
    }

    private fun screenshot(name: String) {
        output(name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun output(name: String): File {
        val directory = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")))
        check(directory.mkdirs() || directory.isDirectory)
        return File(directory, name)
    }
}

package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.multiprompt.companion.NewSessionMode
import dev.multiprompt.companion.NewSessionPicker
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.RemoteDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
class NewSessionScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var closed = 0
    private var mode: NewSessionMode? = null

    private fun show(sunlight: Boolean = false) {
        compose.setContent {
            MultipromptTheme(sunlight = sunlight, readerStyle = true) {
                NewSessionFolderScreen(
                    sunlight = sunlight,
                    picker = NewSessionPicker(
                        hostId = "host",
                        path = "/home/user/projects",
                        directories = listOf(
                            RemoteDirectory("multiprompt", isRepo = true),
                            RemoteDirectory("notes", isRepo = false),
                        ),
                        usage = mapOf("multiprompt" to 3),
                    ),
                    hosts = listOf(HostProfile("host", "VPS", "example.com", username = "user", keySecretId = "key")),
                    onBrowse = {},
                    onChoose = {},
                    onModeChange = { mode = it },
                    onClose = { closed++ },
                )
            }
        }
    }

    @Test
    fun backButtonReplacesCancel() {
        show()
        compose.onAllNodesWithText("Cancel").assertCountEquals(0)
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun searchAndModeArePills44dpTall() {
        show()
        compose.onNodeWithTag("picker-back").assertHeightIsEqualTo(44.dp)
        compose.onNodeWithTag("picker-search").assertHeightIsEqualTo(44.dp)
        compose.onNodeWithTag("picker-mode").assertHeightIsEqualTo(44.dp)
        compose.onNodeWithText("Find a repo", useUnmergedTree = true).assertExists()
    }

    @Test
    fun modeSegmentSwitchesToExisting() {
        show(sunlight = true)
        compose.onNodeWithText("Existing").performClick()
        assertEquals(NewSessionMode.EXISTING, mode)
    }

    @Test
    fun rowsDropTheFullPath() {
        show()
        compose.onNodeWithText("git · used 3×").assertExists()
        compose.onAllNodesWithText("/home/user/projects/multiprompt", substring = true).assertCountEquals(0)
        assertTrue(pickerEntrySubtitle(isRepo = false, uses = 0).isEmpty())
        assertEquals("git", pickerEntrySubtitle(isRepo = true, uses = 0))
        assertEquals("used 2×", pickerEntrySubtitle(isRepo = false, uses = 2))
    }
}

package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.multiprompt.companion.NewSessionPicker
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.RecentFolder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
class NewSessionRecentScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var chosen: RecentFolder? = null
    private var browsed: String? = null

    private val funeral = RecentFolder("hz", "Hetzner", "/home/u/projects/funeralprints")
    private val notes = RecentFolder("vps", "VPS", "/home/u/projects/notes")

    private fun show() {
        compose.setContent {
            MultipromptTheme(sunlight = false, readerStyle = true) {
                NewSessionRecentScreen(
                    picker = NewSessionPicker(recentFolders = listOf(funeral, notes)),
                    hosts = listOf(
                        HostProfile("vps", "VPS", "a.example.com", username = "u", keySecretId = "k1"),
                        HostProfile("hz", "Hetzner", "b.example.com", username = "u", keySecretId = "k2"),
                    ),
                    onChooseFolder = { chosen = it },
                    onBrowseHost = { browsed = it },
                    onClose = {},
                )
            }
        }
    }

    @Test
    fun rowsShowFolderNameAndServer() {
        show()
        compose.onNodeWithText("funeralprints").assertExists()
        compose.onNodeWithText("notes").assertExists()
        compose.onAllNodesWithText("Hetzner").assertCountEquals(2)
        compose.onNodeWithText("Find a project folder", useUnmergedTree = true).assertExists()
    }

    @Test
    fun pickingARowChoosesItsFolderAndServer() {
        show()
        compose.onNodeWithText("funeralprints").performClick()
        assertEquals(funeral, chosen)
    }

    @Test
    fun typingFiltersTheListAndHidesServerBrowsing() {
        show()
        compose.onNode(hasSetTextAction()).performTextInput("note")
        compose.onAllNodesWithText("funeralprints").assertCountEquals(0)
        compose.onNodeWithText("notes").assertExists()
        compose.onAllNodesWithText("Browse by server").assertCountEquals(0)
    }

    @Test
    fun browsingByServerStaysAvailable() {
        show()
        compose.onNodeWithText("Browse by server").assertExists()
        compose.onAllNodesWithText("Hetzner")[0].performClick()
        assertEquals("hz", browsed)
    }
}

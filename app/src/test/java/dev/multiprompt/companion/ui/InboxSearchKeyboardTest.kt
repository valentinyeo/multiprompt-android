package dev.multiprompt.companion.ui

import android.app.Application
import android.view.View
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.multiprompt.companion.AppUiState
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
class InboxSearchKeyboardTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var view: View
    private var imeBottom = 0
    private var keyboardHeight = 0
    private var density = 1f
    private val newest = "match newest"
    private val state = AppUiState(
        hosts = listOf(HostProfile("host", "VPS", "example.com", username = "user", keySecretId = "key")),
        sessions = listOf(
            TmuxSession("host", newest, 1, 0, 20),
            TmuxSession("host", "match older", 1, 0, 10),
        ),
        newestSessionsAtBottom = true,
    )

    private fun openSearchWithKeyboard() {
        compose.setContent {
            view = LocalView.current
            val currentDensity = LocalDensity.current
            val bottom = WindowInsets.ime.getBottom(currentDensity)
            SideEffect {
                imeBottom = bottom
                density = currentDensity.density
            }
            MaterialTheme {
                SessionsScreen(
                    state = state,
                    onOpen = {},
                    onOpenTerminal = {},
                    onArchive = {},
                    onArchiveUntil = { _, _ -> },
                    onRestore = {},
                    onDissolve = {},
                    onEnd = {},
                    onRestoreDissolved = {},
                    onForgetDissolved = {},
                    onMarkUnread = {},
                    onToggleArchived = {},
                    onSelectSessionBucket = {},
                    onSelectWorkspace = {},
                    onSwitchWorkspace = {},
                    onMoveWorkspaceSplit = { _, _ -> },
                    onResetWorkspaceSplitOrder = {},
                    onCreateWorkspace = { null },
                    onMoveSession = { _, _ -> },
                    onRenameSession = { _, _ -> null },
                    onSetFavorite = { _, _ -> },
                    onRefresh = {},
                    onSelectSection = {},
                    onForgetSession = {},
                    onStartNewSession = {},
                    onSetNewestSessionsAtBottom = {},
                    onSetAllSplitOnRight = {},
                    appTheme = AppTheme.SYSTEM,
                    onSetAppTheme = {},
                    onClearSessionActionError = {},
                    readerDefaultFontScale = 1f,
                    onSetReaderDefaultFontScale = {},
                    crashReport = null,
                    onClearCrashReport = {},
                    onMapAccountHost = { _, _ -> },
                    signedIn = true,
                    onSignIn = {},
                    onSignOut = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Search sessions").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("match")
        compose.runOnIdle {
            keyboardHeight = (320 * density).toInt()
            ViewCompat.dispatchApplyWindowInsets(
                view,
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, (24 * density).toInt()))
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, keyboardHeight))
                    .setVisible(WindowInsetsCompat.Type.ime(), true)
                    .build(),
            )
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("Fixture must deliver a real nonzero Compose IME inset", imeBottom > 0)
            assertEquals(keyboardHeight, imeBottom)
        }
        compose.onNodeWithText("2 results across all splits").assertIsDisplayed()
    }

    private fun list() = compose.onNode(hasScrollAction() and hasAnyDescendant(hasText(newest)))

    @Test
    fun searchResultsStartDirectlyBelowSearchBoxWithKeyboard() {
        openSearchWithKeyboard()
        val summary = compose.onNodeWithText("2 results across all splits").getUnclippedBoundsInRoot()
        val first = compose.onNodeWithText(newest).getUnclippedBoundsInRoot()
        assertEquals("Search results must not be bottom anchored", summary.bottom.value + 4f, first.top.value, 1f)
        val older = compose.onNodeWithText("match older").getUnclippedBoundsInRoot()
        assertTrue("Newest result must be first", first.bottom <= older.top)
    }

    @Test
    fun searchResultAndListAreFullyAboveKeyboardWithoutDoubleNavigationPadding() {
        openSearchWithKeyboard()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val keyboardTop = root.bottom.value - keyboardHeight / density
        val firstNode = compose.onNodeWithText(newest).assertIsDisplayed()
        val first = firstNode.getUnclippedBoundsInRoot()
        val viewport = list().getUnclippedBoundsInRoot()
        assertTrue("First result must be entirely above keyboard", first.bottom.value <= keyboardTop)
        assertTrue("First result must be fully inside the list", first.top >= viewport.top && first.bottom <= viewport.bottom)
        assertTrue("List must end above keyboard", viewport.bottom.value <= keyboardTop)
        val split = compose.onNodeWithText("All").getUnclippedBoundsInRoot()
        assertEquals("Floating split bar keeps its 8dp keyboard gap", keyboardTop, split.bottom.value + 8f, 1f)
    }

    @Test
    fun closingSearchRestoresBottomAnchoredNewestSession() {
        openSearchWithKeyboard()
        compose.onNodeWithContentDescription("Close session search").performClick()
        val first = compose.onNodeWithText(newest).getUnclippedBoundsInRoot()
        val viewport = list().getUnclippedBoundsInRoot()
        val split = compose.onNodeWithText("All").getUnclippedBoundsInRoot()
        assertEquals("Newest session stays just above floating filters", split.top.value - 8f, first.bottom.value + 4f, 1f)
        assertTrue("List continues behind floating filters", viewport.bottom > split.bottom)
        val older = compose.onNodeWithText("match older").getUnclippedBoundsInRoot()
        assertTrue("Non-search order must stay reversed", older.bottom <= first.top)
    }
}

package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.multiprompt.companion.update.ReleaseNote
import dev.multiprompt.companion.update.UpdateRelease
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-xxhdpi")
class UpdateCardNotesTest {
    @get:Rule
    val compose = createComposeRule()

    private fun offered(notes: String, history: List<ReleaseNote> = emptyList()) = UpdateRelease(
        versionCode = 2,
        versionName = "0.2.0-m3",
        apkUrl = "https://example.com/app.apk",
        sha256 = "0".repeat(64),
        sizeBytes = 20L * 1024 * 1024,
        notes = notes,
        history = history,
    )

    private fun show(release: UpdateRelease, installs: MutableList<UpdateRelease> = mutableListOf()) {
        compose.setContent {
            MultipromptTheme {
                Surface(Modifier.fillMaxWidth().wrapContentHeight()) {
                    UpdateAvailableCard(release) { installs += it }
                }
            }
        }
    }

    private val fiveChanges = listOf(
        "- MUBO-1 First change",
        "- MUBO-2 Second change",
        "- MUBO-3 Third change",
        "- MUBO-4 Fourth change",
        "- MUBO-5 Fifth change",
    ).joinToString("\n")

    @Test
    fun theCardShowsThreePlainChangesAndSeeAllRevealsTheRest() {
        show(offered(fiveChanges))

        compose.onNodeWithText("v0.2.0-m3 available").assertIsDisplayed()
        compose.onNodeWithText("What's new").assertIsDisplayed()
        compose.onNodeWithText("MUBO-1 First change").assertIsDisplayed()
        compose.onNodeWithText("MUBO-3 Third change").assertIsDisplayed()
        compose.onNodeWithText("MUBO-4 Fourth change").assertDoesNotExist()
        compose.onNodeWithText("MUBO-5 Fifth change").assertDoesNotExist()
        compose.onNodeWithText("See all").assertIsDisplayed()

        compose.onNodeWithText("See all").performClick()

        compose.onNodeWithText("MUBO-4 Fourth change").assertIsDisplayed()
        compose.onNodeWithText("MUBO-5 Fifth change").assertIsDisplayed()
        compose.onNodeWithText("Show less").assertIsDisplayed()
    }

    @Test
    fun cardKeepsItsVersionSizeAndInstallActionWhileNotesAreShown() {
        val installs = mutableListOf<UpdateRelease>()
        show(offered(fiveChanges), installs)

        compose.onNodeWithText("20 MB").assertIsDisplayed()
        compose.onNodeWithText("Download and update").assertIsDisplayed().performClick()
        compose.onNodeWithText("MUBO-1 First change").assertIsDisplayed()
        compose.runOnIdle { assertEquals("0.2.0-m3", installs.single().versionName) }
    }

    @Test
    fun shortNotesShowEveryChangeAndSeeAllStillOpensThem() {
        show(offered("- MUBO-1 Only change\n- MUBO-2 Second change"))

        compose.onNodeWithText("MUBO-1 Only change").assertIsDisplayed()
        compose.onNodeWithText("MUBO-2 Second change").assertIsDisplayed()
        compose.onNodeWithText("See all").performClick()
        compose.onNodeWithText("MUBO-1 Only change").assertIsDisplayed()
        compose.onNodeWithText("MUBO-2 Second change").assertIsDisplayed()
        compose.onNodeWithText("Show less").assertIsDisplayed()
    }

    @Test
    fun theCardNeverShowsAnotherVersionsNotes() {
        show(
            offered(
                notes = "- MUBO-1 The offered change",
                history = listOf(ReleaseNote("0.1.0-m3", "2026-01-01", "- MUBO-9 An older release change")),
            ),
        )

        compose.onNodeWithText("MUBO-1 The offered change").assertIsDisplayed()
        compose.onNodeWithText("MUBO-9 An older release change").assertDoesNotExist()
    }

    @Test
    fun notesUnavailableKeepTheCardWithoutEmptyChangeRows() {
        show(offered(""))

        compose.onNodeWithText("v0.2.0-m3 available").assertIsDisplayed()
        compose.onNodeWithText("What's new").assertDoesNotExist()
        compose.onNodeWithText("See all").assertDoesNotExist()
        compose.onNodeWithText("Download and update").assertIsDisplayed()
    }

    @Test
    fun markdownHeadersAndTicketOnlyLinesNeverReachTheCard() {
        show(
            offered(
                """
                ## What's new
                - **MUBO-1** Chat view needs no screen text
                MUBO-2
                Release notes
                - MUBO-3 Second real change
                """.trimIndent(),
            ),
        )

        compose.onNodeWithText("MUBO-1 Chat view needs no screen text").assertIsDisplayed()
        compose.onNodeWithText("MUBO-3 Second real change").assertIsDisplayed()
        compose.onNodeWithText("MUBO-2").assertDoesNotExist()
        compose.onNodeWithText("What's new").assertIsDisplayed()
    }
}

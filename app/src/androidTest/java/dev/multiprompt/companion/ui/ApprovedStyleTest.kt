package dev.multiprompt.companion.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.multiprompt.companion.MainActivity
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ApprovedStyleTest {
    @get:Rule(order = 0) val notifications = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun approvedInboxAndChatUseSharedSurfaces() {
        val output = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "approved-style").apply { mkdirs() }
        val problems = mutableListOf<String>()
        val samples = mutableListOf<String>()
        for ((name, screen) in listOf("inbox" to FadeScreen.INBOX_LIST, "chat" to FadeScreen.SESSION)) {
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent { FadeTestScreen(screen, material3 = false, sunlight = false) }
            }
            if (name == "inbox") compose.onNodeWithText("Inbox").assertIsDisplayed()
            else compose.onNode(hasSetTextAction()).assertIsDisplayed()
            compose.waitForIdle()
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val control = compose.onNodeWithContentDescription(if (name == "inbox") "Search sessions" else "Back").captureToImage().asAndroidBitmap()
            val controlPixels = IntArray(control.width * control.height)
            control.getPixels(controlPixels, 0, control.width, 0, 0, control.width, control.height)
            File(output, "$name-control.png").outputStream().use { control.compress(Bitmap.CompressFormat.PNG, 100, it) }
            for ((label, color) in listOf("page" to 0x101010, "controls" to 0x1F1F1F, "outline" to 0x2E2E2E)) {
                val count = (if (label == "page") pixels else controlPixels).count { it and 0xFFFFFF == color }
                samples += "$name $label #${color.toString(16)} pixels=$count"
                if (count < 100) problems += "$name $label missing: $count pixels"
            }
            if (name == "inbox") {
                if (compose.onAllNodesWithText("✳").fetchSemanticsNodes().isNotEmpty()) problems += "inbox still shows agent icons"
                compose.onNodeWithContentDescription("Search sessions").performClick()
                compose.onNodeWithText("Search all sessions").performTextInput("12")
                compose.onNodeWithText("Session 12", useUnmergedTree = true).assertIsDisplayed()
                compose.onNodeWithText("Session 0", useUnmergedTree = true).assertDoesNotExist()
                compose.onNodeWithContentDescription("Close session search").performClick()
                compose.onNodeWithText("Session 0").performTouchInput { longClick() }
                compose.onNodeWithText("Mark unread").assertIsDisplayed()
            }
        }
        File(output, "samples.txt").writeText((samples + problems).joinToString("\n"))
        assertTrue("APPROVED_STYLE: ${problems.joinToString("; ")}", problems.isEmpty())
    }
}

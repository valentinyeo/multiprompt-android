package dev.multiprompt.companion.ui

import android.content.pm.ApplicationInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import dev.multiprompt.companion.MainActivity
import dev.multiprompt.companion.reader.PendingQuestion
import java.io.File
import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class StructuredQuestionsReleaseTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun publishedPackageRendersOnlyStructuredChoices() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("dev.multiprompt.companion", context.packageName)
        assertFalse(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val question = PendingQuestion("release-proof", 0, "Which parts should we keep?", "Exact header",
            listOf(PendingQuestion.Option("History", "Keep earlier messages."), PendingQuestion.Option("Settings", "Keep preferences.")),
            true, true, "claude")
        var sent: Pair<Set<Int>, String>? = null
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MultipromptTheme(readerStyle = true) {
                    Surface {
                        Column {
                            MarkdownText("1. Ordinary numbered list\n2. Another ordinary item\n- Ordinary bullet", ReaderTextSize, ReaderForeground, ReaderChoiceBlue)
                            QuestionChoices(question, enabled = true) { choices, other -> sent = choices to other; true }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("1.").assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithText("Ordinary numbered list").assertHasNoClickAction()
        compose.onNodeWithTag("reader-choice-1").assertDoesNotExist()
        listOf("Exact header", "Which parts should we keep?", "Keep earlier messages.", "Keep preferences.").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        val output = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")))
        output.mkdirs()
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(output, "released-structured-questions.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Other").performTextInput("Exact Other answer")
        compose.onNodeWithText("Submit answer").performClick()
        compose.runOnIdle { assertEquals(setOf(0, 1) to "Exact Other answer", sent) }
        assertTrue(context.applicationInfo.sourceDir.endsWith(".apk"))
        File(output, "released-questions-result.txt").writeText("RELEASED_STRUCTURED_QUESTIONS_OK\n${context.packageName}\n${context.applicationInfo.sourceDir}\n")
    }
}

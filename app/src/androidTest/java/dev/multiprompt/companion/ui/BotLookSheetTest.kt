package dev.multiprompt.companion.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import dev.multiprompt.companion.MainActivity
import dev.multiprompt.companion.model.TmuxSession
import java.io.File
import org.junit.Rule
import org.junit.Test

class BotLookSheetTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val session = TmuxSession("host", "bot-0", 1, 0, 0, accountName = "HT Product Lead")

    @Test fun botLookSheetShowsFacesAndColours() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MultipromptTheme(sunlight = false, material3 = false, readerStyle = true) {
                    Surface {
                        BotLookSheet(
                            session = sessions[0],
                            face = null,
                            color = null,
                            onPickFace = {},
                            onPickColor = {},
                            onDismiss = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Bot look").assertIsDisplayed()
        compose.onNodeWithText("Face").assertIsDisplayed()
        compose.onNodeWithText("Colour").assertIsDisplayed()
        compose.onNodeWithText("Auto").assertIsDisplayed()
        deviceScreenshot("bot-look-sheet")
    }

    private fun deviceScreenshot(name: String) {
        val directory = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "robot-faces").apply { mkdirs() }
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

}

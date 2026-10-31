package dev.multiprompt.companion.ui

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.multiprompt.companion.MainActivity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class KitMenuInteractionTest {
    @get:Rule(order = 0) val notifications = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun scrollPageResetBackAndReopen() {
        var expanded by mutableStateOf(false)
        var page by mutableStateOf("A")
        var calls = 0
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MultipromptTheme(material3 = true) {
                    TextButton(onClick = { expanded = true }) { Text("Open menu") }
                    KitMenu(expanded, onDismissRequest = { expanded = false }, contentResetKey = page) {
                        repeat(40) { index ->
                            KitMenuRow("$page row $index", onClick = { calls++ })
                        }
                        KitMenuRow("Change page", onClick = { page = "B" })
                    }
                }
            }
        }
        compose.onNodeWithText("Open menu").performClick()
        compose.onNodeWithText("A row 39").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, calls) }
        compose.onNodeWithText("Change page").performScrollTo().performClick()
        compose.onNodeWithText("B row 0").assertIsDisplayed()
        compose.onNodeWithText("B row 39").assertIsNotDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "approved-style").apply { mkdirs() }
        instrumentation.uiAutomation.waitForIdle(100, 5_000)
        File(output, "menu-page.png").outputStream().use {
            assertTrue(instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        compose.onNodeWithText("B row 39").performScrollTo().assertIsDisplayed()
        assertTrue(instrumentation.uiAutomation.injectInputEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK), true))
        assertTrue(instrumentation.uiAutomation.injectInputEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK), true))
        compose.waitForIdle()
        compose.onNodeWithText("B row 39").assertDoesNotExist()
        compose.onNodeWithText("Open menu").performClick()
        compose.onNodeWithText("B row 0").assertIsDisplayed()
        compose.onNodeWithText("B row 39").assertIsNotDisplayed()
        compose.runOnIdle { assertEquals(1, calls) }
    }
}

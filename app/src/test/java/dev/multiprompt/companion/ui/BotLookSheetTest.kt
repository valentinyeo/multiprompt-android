package dev.multiprompt.companion.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.dp
import dev.multiprompt.companion.model.TmuxSession
import java.io.File
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BotLookSheetTest {
    private val scheduler = TestCoroutineScheduler()
    @get:Rule val compose = createComposeRule(effectContext = UnconfinedTestDispatcher(scheduler))
    private val session = TmuxSession("host", "bot-0", 1, 0, 0, accountName = "HT Product Lead")

    @Test fun botLookSheetShowsFacesColoursAndAuto() {
        compose.setContent {
            MultipromptTheme(sunlight = false, material3 = false) {
                Surface {
                    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("bot-look")) {
                        BotLookContent(
                            session = session,
                            pickedFace = 4,
                            pickedColor = 2,
                            onPickFace = {},
                            onPickColor = {},
                        )
                        Text("captured", Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
        compose.onNodeWithText("Bot look").assertIsDisplayed()
        compose.onNodeWithText("Face").assertIsDisplayed()
        compose.onNodeWithText("Colour").assertIsDisplayed()
        compose.onNodeWithText("Auto").assertIsDisplayed()
        val bitmap = capture("bot-look")
        assertTrue(bitmap.width > 200 && bitmap.height > 200)
    }

    @Test fun pickingAFaceOrColourReportsIt() {
        val faces = mutableListOf<Int>()
        val colours = mutableListOf<Int?>()
        compose.setContent {
            MultipromptTheme(sunlight = false, material3 = false) {
                Surface {
                    Column(Modifier.padding(16.dp)) {
                        BotLookContent(
                            session = session,
                            pickedFace = null,
                            pickedColor = null,
                            onPickFace = { faces.add(it) },
                            onPickColor = { colours.add(it) },
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("Bot look").assertIsDisplayed()
        compose.onNodeWithText("Auto").performClick()
        compose.waitForIdle()
        assertEquals(listOf<Int?>(null), colours)
        compose.onNodeWithText("Face").assertIsDisplayed()
        assertTrue(faces.isEmpty())
    }

    private fun capture(tag: String): Bitmap {
        val root = compose.onNodeWithTag(tag)
        val view = (checkNotNull(root.fetchSemanticsNode().root) as ViewRootForTest).view.rootView
        val attachInfo = ReflectionHelpers.getField<Any>(view, "mAttachInfo")
        val accelerated = view.isHardwareAccelerated
        ReflectionHelpers.setField(attachInfo, "mHardwareAccelerated", false)
        val redraw = CoroutineScope(UnconfinedTestDispatcher(scheduler)).launch {
            while (isActive) {
                delay(16)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                view.draw(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
                view.viewTreeObserver.dispatchOnDraw()
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
        val bitmap = try {
            root.captureToImage().asAndroidBitmap()
        } finally {
            redraw.cancel()
            ReflectionHelpers.setField(attachInfo, "mHardwareAccelerated", accelerated)
        }
        val output = File("build/reports/igsh-1173/$tag.png")
        checkNotNull(output.parentFile).mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return bitmap
    }
}

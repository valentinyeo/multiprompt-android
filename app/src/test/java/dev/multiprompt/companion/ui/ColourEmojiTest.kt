package dev.multiprompt.companion.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import java.io.File
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
class ColourEmojiTest {
    private val scheduler = TestCoroutineScheduler()
    @get:Rule val compose = createComposeRule(effectContext = UnconfinedTestDispatcher(scheduler))

    @Test fun chatTextDrawsColourEmojiNotGreyGlyphs() {
        compose.setContent {
            MultipromptTheme(sunlight = false, material3 = false) {
                Surface {
                    Column(Modifier.padding(8.dp)) {
                        Text("Ship it 👍🚀", Modifier.testTag("emoji"))
                    }
                }
            }
        }
        val bitmap = capture("emoji")
        val chroma = chromaticPixels(bitmap)
        assertTrue("emoji drew as grey glyphs", chroma > 20)
    }

    @Test fun reactionBadgeAndBotNameDrawColourEmoji() {
        compose.setContent {
            MultipromptTheme(sunlight = false, material3 = false) {
                Surface {
                    Column(Modifier.padding(8.dp)) {
                        Text("Rocket 🤖 Chief", Modifier.testTag("name"))
                        Text("❤️ 🔥", Modifier.testTag("reaction"))
                    }
                }
            }
        }
        assertTrue("bot name emoji drew as grey glyphs", chromaticPixels(capture("name")) > 20)
        assertTrue("reaction emoji drew as grey glyphs", chromaticPixels(capture("reaction")) > 20)
    }

    @Test fun composerKeepsEmojiColourfulWhileTyping() {
        compose.setContent {
            MultipromptTheme(sunlight = false, material3 = false) {
                Surface {
                    Column(Modifier.padding(8.dp)) {
                        OutlinedTextField(
                            value = "Ship it 👍",
                            onValueChange = {},
                            modifier = Modifier.testTag("composer"),
                        )
                    }
                }
            }
        }
        assertTrue("composer emoji drew as grey glyphs", chromaticPixels(capture("composer")) > 20)
    }

    private fun chromaticPixels(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { pixel ->
            val r = (pixel shr 16) and 255
            val g = (pixel shr 8) and 255
            val b = pixel and 255
            val high = maxOf(r, g, b)
            val low = minOf(r, g, b)
            high > 60 && high - low > 60
        }
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
        val output = File("build/reports/igsh-1370/$tag.png")
        checkNotNull(output.parentFile).mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return bitmap
    }
}

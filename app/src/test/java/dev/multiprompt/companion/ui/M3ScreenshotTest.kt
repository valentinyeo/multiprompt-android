package dev.multiprompt.companion.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
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
import org.junit.Assume.assumeNotNull
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
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class M3ScreenshotTest {
    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val compose = createComposeRule(effectContext = UnconfinedTestDispatcher(scheduler))

    @Test
    fun captureBothLooksAndBrightnessModes() {
        val directory = System.getProperty("m3.shots")
        assumeNotNull(directory)
        val output = File(checkNotNull(directory)).apply { mkdirs() }
        val fixture = mutableStateOf(Triple(M3Screen.INBOX_LIST, false, false))
        compose.setContent {
            key(fixture.value) {
                val (screen, material3, sunlight) = fixture.value
                M3TestScreen(screen, material3, sunlight)
            }
        }
        listOf(M3Screen.INBOX_LIST, M3Screen.INBOX_SEARCH, M3Screen.SESSION, M3Screen.INBOX_MENU).forEach { screen ->
            listOf(false, true).forEach { sunlight ->
                listOf(false, true).forEach { material3 ->
                    compose.runOnIdle { fixture.value = Triple(screen, material3, sunlight) }
                    compose.openM3Screen(screen)
                    val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
                    val nodes = roots.fetchSemanticsNodes()
                    val background = (checkNotNull(nodes.first().root) as ViewRootForTest).view.rootView
                    val bitmap = Bitmap.createBitmap(background.width, background.height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    val origin = nodes.first().positionOnScreen
                    nodes.indices.forEach { index ->
                        val target = if (index == 0) roots[index] else compose.onNode(isPopup(), useUnmergedTree = true)
                        val node = target.fetchSemanticsNode()
                        val root = (checkNotNull(node.root) as ViewRootForTest).view.rootView
                        val attachInfo = ReflectionHelpers.getField<Any>(root, "mAttachInfo")
                        val accelerated = root.isHardwareAccelerated
                        ReflectionHelpers.setField(attachInfo, "mHardwareAccelerated", false)
                        val redrawCanvas = Canvas(Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888))
                        val redraw = CoroutineScope(UnconfinedTestDispatcher(scheduler)).launch {
                            while (isActive) {
                                delay(16)
                                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                                root.draw(redrawCanvas)
                                root.viewTreeObserver.dispatchOnDraw()
                                shadowOf(Looper.getMainLooper()).idle()
                            }
                        }
                        val image = try {
                            target.captureToImage().asAndroidBitmap()
                        } finally {
                            redraw.cancel()
                            ReflectionHelpers.setField(attachInfo, "mHardwareAccelerated", accelerated)
                        }
                        val position = node.positionOnScreen - origin
                        canvas.drawBitmap(image, position.x, position.y, null)
                    }
                    val name = "${screen.name.lowercase()}-${if (material3) "m3" else "classic"}-${if (sunlight) "light" else "dark"}.png"
                    File(output, name).outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                }
            }
        }
    }
}

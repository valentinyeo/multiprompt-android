package dev.multiprompt.companion.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import org.junit.Assert.assertFalse
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
class DesignKitTest {
    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val compose = createComposeRule(effectContext = UnconfinedTestDispatcher(scheduler))

    @Composable
    private fun Page(content: @Composable () -> Unit) {
        Column(Modifier.fillMaxWidth().padding(Kit.ScreenEdge), verticalArrangement = Arrangement.spacedBy(Kit.ControlGap)) {
            content()
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
        val output = File("build/reports/igsh-1015/$tag.png")
        checkNotNull(output.parentFile).mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return bitmap
    }

    private fun assertPixel(bitmap: Bitmap, x: Int, y: Int, expected: Color, what: String) {
        assertEquals("$what at $x,$y", Integer.toHexString(expected.toArgb()), Integer.toHexString(bitmap.getPixel(x, y)))
    }

    @Test
    fun primaryButtonIsWhiteAndDestructiveIsRed() {
        compose.setContent {
            Page {
                KitPrimaryButton("Done", {}, Modifier.testTag("primary"))
                KitPrimaryButton("Remove", {}, Modifier.testTag("primary-danger"), destructive = true)
            }
        }
        val white = capture("primary")
        assertPixel(white, 14, white.height / 2, Kit.Text, "primary fill")
        val red = capture("primary-danger")
        assertPixel(red, 14, red.height / 2, Kit.Danger, "destructive fill")
        assertEquals(Kit.ControlHeight.value.toInt(), white.height)
    }

    @Test
    fun secondaryButtonAndRoundButtonShareTheSurfaceStyle() {
        compose.setContent {
            Page {
                KitSecondaryButton("Automatic order", {}, Modifier.testTag("secondary"))
                KitRoundButton({}, Icons.Default.MoreVert, "More", Modifier.testTag("round"))
                KitTitlePill("Session name", Modifier.testTag("pill"))
            }
        }
        listOf("secondary", "pill").forEach { tag ->
            val bitmap = capture(tag)
            assertPixel(bitmap, 14, bitmap.height / 2, Kit.Surface, "$tag fill")
            assertPixel(bitmap, bitmap.width / 2, 0, Kit.Outline, "$tag outline")
        }
        val round = capture("round")
        assertEquals(Kit.RoundButton.value.toInt(), round.width)
        assertPixel(round, 6, round.height / 2, Kit.Surface, "round fill")
    }

    @Test
    fun toggleFlipsAndShowsTheSelectedFill() {
        var on by mutableStateOf(false)
        compose.setContent {
            Page { KitToggle(on, { on = it }, Modifier.testTag("toggle")) }
        }
        val off = capture("toggle")
        assertPixel(off, off.width - 8, off.height / 2, Kit.Surface, "off track")
        compose.onNodeWithTag("toggle").performClick()
        compose.runOnIdle { assertTrue(on) }
        val onBitmap = capture("toggle")
        assertPixel(onBitmap, 10, onBitmap.height / 2, Kit.SurfaceRaised, "on track")
    }

    @Test
    fun segmentedSelectsAndPaintsTheRaisedFill() {
        var picked by mutableStateOf("Dark")
        compose.setContent {
            Page {
                Column(Modifier.testTag("segmented")) {
                    KitSegmented(listOf("System" to "System", "Dark" to "Dark", "Sunlight" to "Sunlight"), picked, { picked = it })
                }
            }
        }
        val before = capture("segmented")
        assertPixel(before, before.width / 2, before.height / 2 - 14, Kit.SurfaceRaised, "selected segment")
        compose.onNodeWithText("Sunlight").performClick()
        compose.runOnIdle { assertEquals("Sunlight", picked) }
    }

    @Test
    fun listGroupSitsOnTheSurfaceWithDividersAndRowsClick() {
        var archived = 0
        compose.setContent {
            Page {
                Column(Modifier.testTag("group")) {
                    KitListGroup {
                        KitListRow("Waiting", icon = Icons.Default.Archive, onClick = {})
                        KitDivider()
                        KitListRow("Archive", icon = Icons.Default.Archive, onClick = { archived++ })
                    }
                }
                KitSectionLabel("Section")
            }
        }
        val group = capture("group")
        assertPixel(group, group.width - 4, group.height / 2 - 10, Kit.Surface, "group fill")
        compose.onNodeWithText("Archive").performClick()
        compose.runOnIdle { assertEquals(1, archived) }
        compose.onNodeWithText("SECTION").assertExists()
    }

    @Test
    fun dangerRowUsesRedText() {
        compose.setContent {
            Page { KitListRow("Remove", destructive = true, modifier = Modifier.testTag("danger-row")) }
        }
        val bitmap = capture("danger-row")
        var red = 0
        for (x in 0 until bitmap.width) for (y in 0 until bitmap.height) if (bitmap.getPixel(x, y) != 0 && Color(bitmap.getPixel(x, y)).red > 0.8f && Color(bitmap.getPixel(x, y)).green < 0.7f) red++
        assertTrue("expected red label pixels, found $red", red > 20)
    }

    @Test
    fun sheetPaintsTheSheetFill() {
        compose.setContent {
            Column(Modifier.testTag("sheet")) {
                KitSheetSurface {
                    KitListGroup {
                        KitListRow("Waiting")
                        KitListRow("Remind me")
                    }
                }
            }
        }
        val bitmap = capture("sheet")
        assertPixel(bitmap, 4, bitmap.height - 4, Kit.Sheet, "sheet fill")
        assertPixel(bitmap, bitmap.width / 4, 14, Kit.Sheet, "sheet beside handle")
    }

    @Test
    fun unreadBadgeIsTheOnlyBlue() {
        compose.setContent {
            Page { KitUnreadBadge(11, Modifier.testTag("badge")) }
        }
        val bitmap = capture("badge")
        assertPixel(bitmap, 3, bitmap.height / 2, Kit.AccentUnread, "badge fill")
        compose.onNodeWithText("11").assertExists()
    }

    @Test
    fun bubblesKeepTheirOutlines() {
        compose.setContent {
            Page {
                KitAssistantBubble("Claude reply", Modifier.testTag("assistant"))
                KitUserBubble("Your message", Modifier.testTag("user"))
            }
        }
        val assistant = capture("assistant")
        assertPixel(assistant, assistant.width / 2, 0, Kit.Outline, "assistant outline")
        val user = capture("user")
        assertPixel(user, user.width / 2, 0, ReaderBubbleUserOutline, "user outline")
    }

    @Test
    fun answerOptionStatesPickAndDim() {
        var clicks = 0
        compose.setContent {
            Page {
                KitAnswerOption("A", "Option title", "One line of detail", Modifier.testTag("idle"), onClick = { clicks++ })
                KitAnswerOption("B", "Picked title", "One line of detail", Modifier.testTag("picked"), state = KitOptionState.Picked)
                KitAnswerOption("C", "Dimmed title", "One line of detail", Modifier.testTag("dimmed"), state = KitOptionState.Dimmed)
            }
        }
        val idle = capture("idle")
        assertPixel(idle, idle.width - 6, idle.height / 2, Kit.Surface, "idle option")
        val picked = capture("picked")
        assertPixel(picked, picked.width - 6, picked.height / 2, Kit.SurfaceRaised, "picked option")
        val dimmed = capture("dimmed")
        assertFalse(Integer.toHexString(dimmed.getPixel(dimmed.width - 6, dimmed.height / 2)) == Integer.toHexString(Kit.Surface.toArgb()))
        compose.onNodeWithTag("idle").performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun kitFileUsesOnlyReaderTokens() {
        val source = File("src/main/java/dev/multiprompt/companion/ui/DesignKit.kt").readText()
        listOf("MaterialTheme", "colorScheme", "DropdownMenu", "AlertDialog", "Color(0x").forEach {
            assertFalse("DesignKit.kt must not use $it", source.contains(it))
        }
    }
}

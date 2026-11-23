package dev.multiprompt.companion.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Mic
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
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

    private fun capture(tag: String, root: androidx.compose.ui.test.SemanticsNodeInteraction = compose.onNodeWithTag(tag)): Bitmap {
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
    fun deviceApprovalScreenUsesKitButtonPixelsAndActions() {
        var approvals = 0
        var denials = 0
        compose.setContent {
            MultipromptTheme {
                DeviceApprovalPrompt(PendingDevice("device", "Laptop", "SHA256:compare-this"), false, null, { approvals++ }, { denials++ })
            }
        }
        val approve = capture("device-approval-primary", compose.onNodeWithText("Approve"))
        assertPixel(approve, approve.width / 2, approve.height - 4, Kit.Text, "screen primary fill")
        val deny = capture("device-approval-secondary", compose.onNodeWithText("No"))
        assertPixel(deny, deny.width / 2, deny.height - 4, Kit.Surface, "screen secondary fill")
        compose.onNodeWithText("Approve").performClick()
        compose.runOnIdle { assertEquals(1, approvals); assertEquals(0, denials) }
        compose.onNodeWithText("No").performClick()
        compose.runOnIdle { assertEquals(1, approvals); assertEquals(1, denials) }
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
        assertEquals(Kit.ControlHeight.value.toInt(), round.width)
        assertEquals(Kit.ControlHeight.value.toInt(), round.height)
        assertPixel(round, 6, round.height / 2, Kit.Surface, "round fill")
    }

    @Test
    fun roundButtonsAndPillsShareControlHeight() {
        compose.setContent {
            Page {
                KitRoundButton({}, Icons.Default.MoreVert, "More", Modifier.testTag("round"))
                KitTitlePill("Session name", Modifier.testTag("pill"))
                KitSecondaryButton("All", {}, Modifier.testTag("split-pill"))
                KitSearchBox("", {}, Modifier.testTag("search"))
            }
        }
        val height = Kit.ControlHeight.value.toInt()
        listOf("round", "pill", "split-pill", "search").forEach { tag ->
            val bitmap = capture(tag)
            assertEquals("$tag uses Kit.ControlHeight", height, bitmap.height)
        }
        val source = File("src/main/java/dev/multiprompt/companion/ui/DesignKit.kt").readText()
        assertFalse("Kit.RoundButton must be removed", source.contains("val RoundButton"))
    }

    @Test
    fun composerPillAndItsCirclesShareTheKitSizes() {
        compose.setContent {
            Page {
                KitComposerPill(Modifier.testTag("composer-pill")) {
                    KitInnerButton({}, Icons.Default.Add, "Attach", Modifier.testTag("attach"))
                    Box(Modifier.weight(1f))
                    KitInnerButton({}, Icons.Default.Mic, "Start dictation", Modifier.testTag("mic"))
                    KitInnerControl(modifier = Modifier.testTag("send"), fill = Kit.Text, outlined = false, onClick = {}) {
                        Icon(Icons.Default.MoreVert, null, Modifier.size(Kit.ControlIconSize), tint = Kit.Page)
                    }
                }
            }
        }
        val outer = Kit.ControlHeight.value.toInt()
        val inner = Kit.ControlInnerSize.value.toInt()
        val pill = capture("composer-pill")
        assertEquals("the message box pill uses the outer control height", outer, pill.height)
        assertPixel(pill, 2, pill.height / 2, Kit.Surface, "composer pill fill")
        listOf("attach", "mic", "send").forEach { tag ->
            val circle = capture(tag)
            assertEquals("$tag uses the shared inner button size", inner, circle.width)
            assertEquals("$tag uses the shared inner button size", inner, circle.height)
        }
        assertEquals("the inner circles leave an even inset", Kit.ControlHeight - Kit.ControlInnerSize, Kit.InnerInset * 2)
        assertPixel(capture("attach"), 3, inner / 2, Kit.SurfaceRaised, "attach soft fill")
        assertPixel(capture("send"), 3, inner / 2, Kit.Text, "send strong fill")
    }

    @Test
    fun multiLineComposerUsesFixedCornersAndDoesNotClipButtons() {
        val grown = (Kit.ControlHeight * 2).value.toInt()
        compose.setContent {
            Page {
                KitComposerPill(Modifier.testTag("composer-pill").height(grown.dp)) {
                    KitInnerButton({}, Icons.Default.Add, "Attach", Modifier.testTag("attach"))
                    Box(Modifier.weight(1f))
                    KitInnerButton({}, Icons.Default.Mic, "Start dictation", Modifier.testTag("mic"))
                    KitInnerControl(modifier = Modifier.testTag("send"), fill = Kit.Text, outlined = false, onClick = {}) {
                        Icon(Icons.Default.MoreVert, null, Modifier.size(Kit.ControlIconSize), tint = Kit.Page)
                    }
                }
            }
        }
        val block = File("src/main/java/dev/multiprompt/companion/ui/DesignKit.kt").readText()
            .substringAfter("fun KitComposerPill")
            .substringBefore("fun KitExpandButton")
        assertTrue("composer uses fixed corners", block.contains("Kit.ComposerShape"))
        assertFalse("composer must not use percent pill corners", block.contains("Kit.PillShape"))
        val pill = capture("composer-pill")
        val corner = (Kit.ControlHeight / 2).value.toInt() + 4
        assertPixel(pill, corner, corner, Kit.Surface, "upper-left keeps a fixed corner radius")
        val inner = Kit.ControlInnerSize.value.toInt()
        listOf("attach", "mic", "send").forEach { tag ->
            val circle = capture(tag)
            assertEquals("$tag stays full size when the composer grows", inner, circle.width)
            assertEquals("$tag stays full size when the composer grows", inner, circle.height)
            val fill = if (tag == "send") Kit.Text else Kit.SurfaceRaised
            assertPixel(circle, inner / 2, inner - 3, fill, "$tag bottom edge stays painted")
        }
    }

    @Test
    fun expandButtonIsASmallRoundSurfaceControl() {
        var clicks = 0
        compose.setContent {
            Page { KitExpandButton(onClick = { clicks++ }, modifier = Modifier.testTag("expand")) }
        }
        val bitmap = capture("expand")
        assertEquals(32, bitmap.width)
        assertEquals(32, bitmap.height)
        assertPixel(bitmap, 4, bitmap.height / 2, Kit.Surface, "expand fill")
        compose.onNodeWithTag("expand").performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
        compose.onNodeWithContentDescription("Expand table").assertExists()
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
    fun sheetScrollColumnReachesSecondPlanBlockInShortSheet() {
        compose.setContent {
            BoxWithConstraints(Modifier.height(220.dp).fillMaxWidth()) {
                KitSheetSurface(maxContentHeight = maxHeight) {
                    Text("AI usage", style = Kit.Heading, color = Kit.Text)
                    KitSheetScrollColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        KitPlanBlock(
                            name = "Claude",
                            percent = "37.5%",
                            fraction = 0.375f,
                            paceLine = "BEHIND",
                            detailLine = "WEEK 37.5% | 5H 12.5% | EMPTY IN 42H",
                        )
                        KitPlanBlock(
                            name = "Codex",
                            percent = "62.5%",
                            fraction = 0.625f,
                            paceLine = "AHEAD",
                            detailLine = "WEEK 62.5% | 5H 25% | EMPTY IN 18H",
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("Claude").assertIsDisplayed()
        compose.onNodeWithText("Codex").performScrollTo()
        compose.onNodeWithText("Codex").assertIsDisplayed()
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
    fun skillPillUsesTheSurfaceAndOutline() {
        compose.setContent {
            Page {
                KitSkillPill("/ship", Modifier.testTag("skill"))
                KitSkillPill("/other", Modifier.testTag("skill-other"), marked = true)
                KitSkillPill("/now", Modifier.testTag("skill-current"), current = true)
            }
        }
        val bitmap = capture("skill")
        assertPixel(bitmap, bitmap.width / 2, 0, Kit.Outline, "skill outline")
        assertPixel(bitmap, 4, bitmap.height / 2, Kit.Surface, "skill fill")
        compose.onNodeWithText("/ship").assertExists()
        val other = capture("skill-other")
        assertPixel(other, 4, other.height / 2, Kit.Outline, "other skill match")
        val current = capture("skill-current")
        assertPixel(current, 4, current.height / 2, Kit.Text, "current skill match")
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
    fun searchBoxCountAndMatchMarkerUseKitColours() {
        compose.setContent {
            Page {
                KitSearchBox("", {}, Modifier.fillMaxWidth().testTag("search-box"), placeholder = "Search")
                KitMatchCount("2 of 4", Modifier.testTag("match-count"))
                KitMatchMarker("match", current = false, Modifier.testTag("mark-other"))
                KitMatchMarker("match", current = true, Modifier.testTag("mark-current"))
            }
        }
        val box = capture("search-box")
        assertPixel(box, 8, box.height / 2, Kit.Surface, "search fill")
        assertPixel(box, box.width / 2, 0, Kit.Outline, "search outline")
        compose.onNodeWithText("2 of 4").assertExists()
        val other = capture("mark-other")
        assertPixel(other, 2, other.height / 2, Kit.Outline, "other match")
        val current = capture("mark-current")
        assertPixel(current, 2, current.height / 2, Kit.Text, "current match")
    }

    @Test
    fun usageBarFillsWithTextAndPlanBlockSitsOnTheSurface() {
        compose.setContent {
            Page {
                KitUsageBar(0.5f, Modifier.testTag("usage"))
                KitUsageBar(null, Modifier.testTag("usage-empty"))
                KitPlanBlock(
                    name = "Claude",
                    percent = "35%",
                    fraction = 0.35f,
                    paceLine = "BEHIND | RESETS THU 21:13, IN 1D 23H",
                    detailLine = "WEEK 35% | 5H 4% | EMPTY IN 40H",
                    modifier = Modifier.testTag("plan"),
                )
            }
        }
        val bar = capture("usage")
        assertPixel(bar, bar.width / 4, bar.height / 2, Kit.Text, "usage fill")
        assertPixel(bar, bar.width * 3 / 4, bar.height / 2, Kit.Outline, "usage track")
        val empty = capture("usage-empty")
        assertPixel(empty, empty.width / 4, empty.height / 2, Kit.Outline, "empty usage track")
        val plan = capture("plan")
        assertPixel(plan, 20, plan.height - 6, Kit.Surface, "plan fill")
        compose.onNodeWithText("Claude").assertExists()
        compose.onNodeWithText("BEHIND | RESETS THU 21:13, IN 1D 23H").assertExists()
        compose.onNodeWithText("AHEAD | RESETS THU 21:13, IN 1D 23H").assertDoesNotExist()
    }

    @Test
    fun savedNoteIsASurfacePillWithAnOutline() {
        compose.setContent {
            Page { KitSavedNote(Modifier.testTag("saved"), fade = false) }
        }
        val bitmap = capture("saved")
        assertPixel(bitmap, 14, bitmap.height / 2, Kit.Surface, "saved fill")
        assertPixel(bitmap, bitmap.width / 2, 0, Kit.Outline, "saved outline")
        compose.onNodeWithText("Saved").assertExists()
    }

    private fun assertRenameSessionSheetColours(sunlight: Boolean, tag: String) {
        val palette = kitPalette(sunlight)
        compose.setContent {
            MultipromptTheme(sunlight = sunlight, material3 = false) {
                KitSheetSurface(Modifier.testTag("rename-session-sheet")) {
                    RenameSessionSheetBody(
                        draft = "Runner",
                        onDraftChange = {},
                        error = null,
                        onDismiss = {},
                        onRename = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Rename session").assertIsDisplayed()
        val sheet = capture("$tag-sheet", compose.onNodeWithTag("rename-session-sheet"))
        assertPixel(sheet, 4, sheet.height - 4, palette.sheet, "$tag sheet fill")
        val field = capture("$tag-field", compose.onNodeWithTag("rename-name-field"))
        assertPixel(field, field.width / 2, field.height - 8, palette.surface, "$tag field fill")
        assertPixel(field, field.width / 2, 0, palette.outline, "$tag field outline")
        val heading = capture("$tag-heading", compose.onNodeWithText("Rename session"))
        assertPixel(heading, 4, heading.height / 2, palette.text, "$tag heading text")
    }

    @Test
    fun renameSessionSheetFollowsKitColoursAtNight() {
        assertRenameSessionSheetColours(sunlight = false, tag = "rename-night")
    }

    @Test
    fun renameSessionSheetFollowsKitColoursInDayMode() {
        assertRenameSessionSheetColours(sunlight = true, tag = "rename-day")
    }

    @Test
    fun kitFileUsesOnlyReaderTokens() {
        val source = File("src/main/java/dev/multiprompt/companion/ui/DesignKit.kt").readText()
        listOf("MaterialTheme", "colorScheme", "DropdownMenu", "AlertDialog", "Color(0x").forEach {
            assertFalse("DesignKit.kt must not use $it", source.contains(it))
        }
    }
}

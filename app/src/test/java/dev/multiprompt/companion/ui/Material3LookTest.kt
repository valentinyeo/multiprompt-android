package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.Config

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
class Material3LookTest(private val screen: M3Screen, private val sunlight: Boolean) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun wireframeLayoutAndEveryClickableTouchTarget() {
        compose.setContent { M3TestScreen(screen, material3 = true, sunlight = sunlight) }
        compose.openM3Screen(screen)
        val clickable = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(clickable.isNotEmpty())
        clickable.forEach { node ->
            val target = compose.onNode(SemanticsMatcher("node ${node.id}") { it.id == node.id }, useUnmergedTree = true)
            if (generateSequence(node.parent) { it.parent }.any { SemanticsProperties.IsPopup in it.config }) {
                target.performScrollTo()
            }
            val bounds = target.fetchSemanticsNode().touchBoundsInRoot
            assertTrue("screen=$screen ${node.config} touch=$bounds", bounds.width >= 48f && bounds.height >= 48f)
        }
        if (screen == M3Screen.SESSION || screen == M3Screen.SESSION_MENU) {
            compose.onNodeWithText("Conversation").assertDoesNotExist()
            listOf("Scroll agent history up", "Open agent history", "Back to live").forEach {
                compose.onNodeWithContentDescription(it).assertDoesNotExist()
            }
            compose.onNodeWithText("claude-reader", useUnmergedTree = true).assertDoesNotExist()
            val back = compose.onNodeWithContentDescription("Back").fetchSemanticsNode().boundsInRoot
            val menu = compose.onNodeWithContentDescription("Session actions").fetchSemanticsNode().boundsInRoot
            assertEquals(48f, back.width, 0.01f)
            assertEquals(48f, menu.width, 0.01f)
            assertEquals(back.top, menu.top, 0.01f)
            val send = compose.onNodeWithContentDescription("Send prompt").fetchSemanticsNode().boundsInRoot
            assertEquals(40f, send.width, 0.01f)
            assertEquals(40f, send.height, 0.01f)
            val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
            assertTrue("composer=$field", field.height >= 56f)
            listOf("Archive", "Remind", "Wait").forEach { label ->
                compose.onNodeWithText(label).assertDoesNotExist()
            }
            val mic = compose.onNodeWithContentDescription("Start dictation").fetchSemanticsNode().boundsInRoot
            val attach = compose.onNodeWithContentDescription("Attach").fetchSemanticsNode().boundsInRoot
            assertTrue("microphone=$mic composer=$field", mic.left >= field.left && mic.right <= field.right && mic.top >= field.top && mic.bottom <= field.bottom)
            assertTrue("attach=$attach composer=$field", attach.right < field.left)
        } else {
            val tab = compose.onNodeWithText("All").fetchSemanticsNode()
            val row = compose.onNodeWithText("claude-reader").fetchSemanticsNode()
            assertTrue("tab=${tab.boundsInRoot} row=${row.boundsInRoot}", tab.boundsInRoot.bottom <= row.boundsInRoot.top)
            assertTrue("row=${row.boundsInRoot}", row.boundsInRoot.height in 48f..56f)
            val title = compose.onNodeWithText("claude-reader", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val badgeNode = compose.onNodeWithContentDescription("1 unread messages", useUnmergedTree = true).fetchSemanticsNode()
            val badge = badgeNode.boundsInRoot
            val time = compose.onAllNodesWithTag("Session activity", useUnmergedTree = true).fetchSemanticsNodes()
                .single { kotlin.math.abs(it.boundsInRoot.center.y - badge.center.y) < 1f }.boundsInRoot
            assertTrue("title=$title badge=$badge", badge.left >= title.right)
            assertEquals("time=$time badge=$badge", time.center.y, badge.center.y, 0.01f)
            assertTrue("time=$time badge=$badge", badge.left > time.right)
            assertTrue("badge=$badge", badge.width >= 16f)
            assertTrue("badge=$badge", badge.height >= 16f)
            val badgeLayouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("1", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(badgeLayouts) }
            assertEquals(1, badgeLayouts.single().lineCount)
            assertEquals(11f, badgeLayouts.single().layoutInput.style.fontSize.value)
            assertEquals(16f, badgeLayouts.single().layoutInput.style.lineHeight.value)
            val texts = compose.onAllNodes(SemanticsMatcher("all") { true }, useUnmergedTree = true).fetchSemanticsNodes()
                .flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }.map { it.text }
            assertTrue("Avatar initials must be decorative: $texts", "C" !in texts && "CR" !in texts && "CT" !in texts)
        }
    }

    @Test
    fun deterministicOrangeSchemeAndDefaultTypeScaleOnAndroid34() {
        var primary = Color.Unspecified
        var actualScheme: androidx.compose.material3.ColorScheme? = null
        var family: androidx.compose.ui.text.font.FontFamily? = null
        compose.setContent {
            MultipromptTheme(sunlight = sunlight, material3 = true) {
                val scheme = MaterialTheme.colorScheme
                val typography = MaterialTheme.typography
                SideEffect {
                    actualScheme = scheme
                    primary = scheme.primary
                    family = typography.bodyLarge.fontFamily
                }
                androidx.compose.material3.Text("Title", style = typography.titleLarge)
            }
        }
        compose.waitForIdle()
        assertEquals(if (sunlight) Material3LightColors else Material3DarkColors, actualScheme)
        assertEquals(if (sunlight) Color(0xFF8F4C27) else Color(0xFFFFB68E), primary)
        assertEquals(if (sunlight) Color(0xFFFFF8F5) else Color(0xFF1A120D), actualScheme?.surface)
        assertEquals(androidx.compose.ui.text.font.FontFamily.SansSerif, family)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Title").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(22f, layouts.single().layoutInput.style.fontSize.value)
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}-sunlight={1}")
        fun parameters(): List<Array<Any>> = M3Screen.entries.flatMap { screen ->
            listOf(false, true).map { sunlight -> arrayOf(screen, sunlight) }
        }
    }
}

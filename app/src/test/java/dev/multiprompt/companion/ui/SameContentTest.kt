package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

internal fun ComposeContentTestRule.openM3Screen(screen: M3Screen) {
    when (screen) {
        M3Screen.INBOX_SEARCH -> {
            onNodeWithContentDescription("Search sessions").performClick()
            onNode(hasSetTextAction()).performTextInput("claude")
        }
        M3Screen.INBOX_MENU -> onNodeWithContentDescription("Session list actions").performClick()
        M3Screen.SESSION_MENU -> onNodeWithContentDescription("Session actions").performClick()
        else -> Unit
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
class SameContentTest(private val screen: M3Screen, private val sunlight: Boolean) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sharedContentMultisetAndRegionOrderAreIdentical() {
        val material3 = mutableStateOf(false)
        compose.setContent {
            key(material3.value) { M3TestScreen(screen, material3.value, sunlight) }
        }
        compose.openM3Screen(screen)
        val classic = regions()
        assertTrue("Fixture must expose text and descriptions", classic.getValue("all").any { it.startsWith("text:") } && classic.getValue("all").any { it.startsWith("description:") })
        compose.runOnIdle { material3.value = true }
        compose.openM3Screen(screen)
        val m3 = regions()
        if (screen == M3Screen.SESSION || screen == M3Screen.SESSION_MENU) {
            assertTrue(classic.getValue("chat messages").isNotEmpty())
        } else {
            assertEquals(4, classic.getValue("splits").size)
            assertTrue(classic.getValue("list row titles").isNotEmpty())
        }
        if (screen == M3Screen.INBOX_MENU || screen == M3Screen.SESSION_MENU) {
            assertTrue(classic.getValue("menu items").isNotEmpty())
        }
        assertEquals("content multiset screen=$screen sunlight=$sunlight", classic.getValue("all").groupingBy { it }.eachCount(), m3.getValue("all").groupingBy { it }.eachCount())
        classic.keys.filterNot { it == "all" }.forEach { region ->
            assertEquals("region=$region screen=$screen sunlight=$sunlight", classic.getValue(region), m3.getValue(region))
        }
    }

    private fun regions(): Map<String, List<String>> {
        val nodes = compose.onAllNodes(SemanticsMatcher("all") { true }, useUnmergedTree = true).fetchSemanticsNodes()
        val activities = nodes.filter { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "Session activity" }
        val badges = nodes.filter { it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } == listOf("1 unread messages") }
        val inbox = screen != M3Screen.SESSION && screen != M3Screen.SESSION_MENU
        val rowCount = if (screen == M3Screen.INBOX_SEARCH || screen == M3Screen.INBOX_SPLITS) 1 else 2
        assertEquals(if (inbox) rowCount else 0, activities.size)
        assertEquals(if (inbox) 1 else 0, badges.size)
        fun ancestors(node: SemanticsNode) = generateSequence(node) { it.parent }
        fun subtree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap { subtree(it) }
        val badgeText = badges.flatMap { subtree(it) }
            .flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }.map { it.text }
        assertEquals(if (inbox) listOf("1") else emptyList<String>(), badgeText)
        fun tokens(node: SemanticsNode): List<String> = buildList {
            if (node.config.getOrElse(SemanticsProperties.TestTag) { "" } == "Session activity" ||
                ancestors(node).any { it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } == listOf("1 unread messages") }) return@buildList
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.forEach { add("text:${it.text}") }
            node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.forEach { add("description:$it") }
            node.config.getOrElseNullable(SemanticsProperties.EditableText) { null }?.let { add("editable:${it.text}") }
        }
        val splitNames = setOf("text:All", "text:Favorites", "text:Frequent", "text:Android")
        fun isSplit(node: SemanticsNode): Boolean {
            val clickable = ancestors(node).firstOrNull { SemanticsActions.OnClick in it.config } ?: return false
            val text = subtree(clickable).flatMap { tokens(it) }
            return text.size == 1 && text.single() in splitNames
        }
        fun isMenu(node: SemanticsNode) = ancestors(node).any {
            SemanticsProperties.IsDialog in it.config || SemanticsProperties.IsPopup in it.config
        }
        fun isMessage(node: SemanticsNode) = ancestors(node).any { ReplySelectable in it.config }
        val splits = nodes.filter { isSplit(it) }.flatMap { tokens(it) }
        val remainder = nodes.filterNot { isSplit(it) }.flatMap { tokens(it) }
        val titles = nodes.flatMap { tokens(it) }.filter { it in setOf("text:claude-reader", "text:codex-tests") }
        return mapOf(
            "all" to nodes.flatMap { tokens(it) },
            "splits" to splits,
            "list row titles" to titles,
            "chat messages" to nodes.filter { isMessage(it) }.flatMap { tokens(it) },
            "menu items" to nodes.filter { isMenu(it) }.flatMap { tokens(it) },
            "composer/actions and other unchanged content" to remainder,
        )
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}-sunlight={1}")
        fun parameters(): List<Array<Any>> = M3Screen.entries.flatMap { screen ->
            listOf(false, true).map { sunlight -> arrayOf(screen, sunlight) }
        }
    }
}

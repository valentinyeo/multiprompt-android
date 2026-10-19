package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
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
    fun orderedUnmergedContentIsIdentical() {
        val material3 = mutableStateOf(false)
        compose.setContent {
            key(material3.value) { M3TestScreen(screen, material3.value, sunlight) }
        }
        compose.openM3Screen(screen)
        val classic = content()
        assertTrue("Fixture must expose text and descriptions", classic.any { it.startsWith("text:") } && classic.any { it.startsWith("description:") })
        compose.runOnIdle { material3.value = true }
        compose.openM3Screen(screen)
        assertEquals("screen=$screen sunlight=$sunlight", classic, content())
    }

    private fun content(): List<String> = compose.onAllNodes(SemanticsMatcher("all") { true }, useUnmergedTree = true)
        .fetchSemanticsNodes().flatMap { node ->
            buildList {
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.forEach { add("text:${it.text}") }
                node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.forEach { add("description:$it") }
                node.config.getOrElseNullable(SemanticsProperties.EditableText) { null }?.let { add("editable:${it.text}") }
            }
        }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}-sunlight={1}")
        fun parameters(): List<Array<Any>> = M3Screen.entries.flatMap { screen ->
            listOf(false, true).map { sunlight -> arrayOf(screen, sunlight) }
        }
    }
}

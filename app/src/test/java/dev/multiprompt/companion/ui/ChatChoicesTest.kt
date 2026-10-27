package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.multiprompt.companion.reader.PendingQuestion
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatChoicesTest {
    @get:Rule val compose = createComposeRule()
    private val question = PendingQuestion("call", 0, "Which route?", "Account",
        listOf(PendingQuestion.Option("Create", "Keep work separate."), PendingQuestion.Option("Keep", "Current setup.")),
        multiSelect = false, other = true, provider = "claude")

    @Test fun exactQuestionAndDescriptionsStaySeparateFromLabels() {
        var answer: Pair<Set<Int>, String>? = null
        compose.setContent {
            MaterialTheme { QuestionChoices(question, enabled = true) { selected, other -> answer = selected to other; true } }
        }
        listOf("Account", "Which route?", "Create", "Keep work separate.", "Keep", "Current setup.", "Other").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithText("Keep").performClick()
        compose.onNodeWithText("Submit answer").performClick().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(setOf(1) to "", answer) }
    }

    @Test fun multipleSelectionsAndOtherArePreservedTogether() {
        var answer: Pair<Set<Int>, String>? = null
        compose.setContent {
            MaterialTheme { QuestionChoices(question.copy(multiSelect = true), enabled = true) { selected, other -> answer = selected to other; true } }
        }
        compose.onNodeWithText("Create").performClick()
        compose.onNodeWithText("Keep").performClick()
        compose.onNodeWithText("Other").performTextInput("A third route")
        compose.onNodeWithText("Submit answer").performClick()
        compose.runOnIdle { assertEquals(setOf(0, 1) to "A third route", answer) }
    }

    @Test fun singleChoiceAndOtherAreExclusive() {
        var answer: Pair<Set<Int>, String>? = null
        compose.setContent {
            MaterialTheme { QuestionChoices(question, enabled = true) { selected, other -> answer = selected to other; true } }
        }
        compose.onNodeWithText("Create").performClick()
        compose.onNodeWithText("Other").performTextInput("Custom answer")
        compose.onNodeWithText("Submit answer").performClick()
        compose.runOnIdle { assertEquals(emptySet<Int>() to "Custom answer", answer) }
    }

    @Test fun failedQuestionTransportAllowsRetry() {
        val error = mutableStateOf<String?>(null)
        var attempts = 0
        compose.setContent {
            MaterialTheme { QuestionChoices(question, enabled = true, error = error.value) { _, _ -> attempts++; true } }
        }
        compose.onNodeWithText("Keep").performClick()
        compose.onNodeWithText("Submit answer").performClick().assertIsNotEnabled()
        compose.runOnIdle { error.value = "Connection failed" }
        compose.onNodeWithText("Submit answer").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, attempts) }
    }
}

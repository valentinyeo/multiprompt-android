package dev.multiprompt.companion.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import dev.multiprompt.companion.reader.ChatHistoryMessage
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReplyReferenceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun replyHasOneLineAndJumpsToHighlightedOriginal() {
        val source = "Original source " + "long reply reference words ".repeat(35)
        val wire = ReplyQuote.compose(source, "My short answer")
        val messages = listOf(ChatHistoryMessage("source", "assistant", source, "", 0, 1)) +
            (1..35).map { ChatHistoryMessage("filler-$it", "assistant", "Earlier message $it", "", it.toLong(), it + 1L) } +
            ChatHistoryMessage("reply", "user", wire, "", 40, 41)
        var expectedColor = Color.Unspecified
        compose.setContent {
            MaterialTheme {
                expectedColor = chatHintColor(true)
                ChatHistoryList(messages, sunlight = true, modifier = Modifier.background(Color.White))
            }
        }
        screenshot("reply-line.png")
        compose.onNodeWithContentDescription("Jump to original message").assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("reply-reference-text", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(1, layout.lineCount)
        assertTrue(layout.isLineEllipsized(0))
        assertEquals(expectedColor, layout.layoutInput.style.color)
        compose.onNodeWithTag("chat-message-source").assertDoesNotExist()
        compose.onNodeWithContentDescription("Jump to original message").performClick()
        compose.onNodeWithTag("chat-message-source").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Original message"))
        assertTrue(compose.onNodeWithTag("chat-message-source").getUnclippedBoundsInRoot().top.value >= 0f)
        screenshot("reply-source-highlight.png")
        output("reply-layout.json").writeText(JSONObject()
            .put("lines", layout.lineCount)
            .put("ellipsized", layout.isLineEllipsized(0))
            .put("sourceVisible", true)
            .put("highlighted", true)
            .put("wire", wire)
            .toString())
    }

    @Test
    fun tappingALoadedReplyFetchesItsOlderSourceBeforeJumping() {
        val source = "An original message from an older history page"
        val wire = ReplyQuote.compose(source, "Reply after scrolling back")
        val pagesLoaded = AtomicInteger()
        compose.setContent {
            MaterialTheme {
                var messages by remember { mutableStateOf(
                    (1..35).map { ChatHistoryMessage("m$it", "assistant", "Recent message $it", "", it.toLong(), it + 1L) } +
                        ChatHistoryMessage("pending-reply", "user", wire, "", 40, 41, pending = true),
                ) }
                var hasOlder by remember { mutableStateOf(true) }
                ChatHistoryList(
                    messages,
                    sunlight = true,
                    hasOlder = hasOlder,
                    modifier = Modifier.background(Color.White),
                    onNearTop = { loading ->
                        if (loading && hasOlder) {
                            pagesLoaded.incrementAndGet()
                            messages = listOf(ChatHistoryMessage("source", "assistant", source, "", 0, 1)) +
                                messages.map { if (it.key == "pending-reply") it.copy(key = "accepted-reply", pending = false) else it }
                            hasOlder = false
                        }
                    },
                )
            }
        }
        compose.onNodeWithTag("chat-message-source").assertDoesNotExist()
        compose.onNodeWithContentDescription("Jump to original message").performClick()
        compose.onNodeWithTag("chat-message-source").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Original message"))
        assertEquals(1, pagesLoaded.get())
        screenshot("reply-loaded-source.png")
        output("reply-page-layout.json").writeText(JSONObject()
            .put("pagesLoaded", pagesLoaded.get())
            .put("sourceVisible", true)
            .put("highlighted", true)
            .toString())
    }

    private fun screenshot(name: String) {
        output(name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun output(name: String): File {
        val directory = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")))
        check(directory.mkdirs() || directory.isDirectory)
        return File(directory, name)
    }
}

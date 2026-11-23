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
import dev.multiprompt.companion.reader.ChatReactions
import dev.multiprompt.companion.reader.ChatHistoryMessage
import dev.multiprompt.companion.reader.PendingQuestion
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
    fun foldedReactionsDoNotShiftReplyJumpTarget() {
        val source = ChatHistoryMessage("reaction-source", "assistant", "Original reply with a stable provider identity", "", 31, 32, "msg_source")
        val reaction = ChatReactions.format(ChatReactions.Emoji.LIKE, source.requestId, source.text)!!
        val messages = (1..30).map { ChatHistoryMessage("older-$it", "assistant", "Older message $it", "", it.toLong(), it + 1L) } +
            source +
            (33..67).map { ChatHistoryMessage("later-$it", "assistant", "Later message $it", "", it.toLong(), it + 1L) } +
            (1..20).map { ChatHistoryMessage("reaction-$it", "user", reaction, "", 67L + it, 68L + it) } +
            ChatHistoryMessage("reply", "user", ReplyQuote.compose(source.text, "Answer to the original"), "", 90, 91)
        compose.setContent {
            MaterialTheme { ChatHistoryList(messages, sunlight = true, modifier = Modifier.background(Color.White)) }
        }
        compose.onNodeWithTag("chat-message-reaction-source").assertDoesNotExist()
        compose.onNodeWithContentDescription("Jump to original message").performClick()
        compose.onNodeWithTag("chat-message-reaction-source").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Original message"))
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

    @Test
    fun jumpingToAnOversizedSourceShowsItsBeginning() {
        val source = "Beginning of the tall original " + "long original message words ".repeat(160)
        val messages = (1..20).map { ChatHistoryMessage("older-$it", "assistant", "Older message $it", "", it.toLong(), it + 1L) } +
            ChatHistoryMessage("tall-source", "assistant", source, "", 21, 22) +
            (23..55).map { ChatHistoryMessage("later-$it", "assistant", "Later message $it", "", it.toLong(), it + 1L) } +
            ChatHistoryMessage("reply", "user", ReplyQuote.compose(source, "Answer to tall original"), "", 56, 57)
        val question = PendingQuestion("call", 0, "Choose an option", "", listOf(PendingQuestion.Option("A", ""), PendingQuestion.Option("B", "")), false, true, "claude")
        compose.setContent {
            MaterialTheme { ChatHistoryList(messages, sunlight = true, questions = listOf(question), modifier = Modifier.background(Color.White)) }
        }
        compose.onNodeWithContentDescription("Jump to original message").performClick()
        val original = compose.onNodeWithTag("chat-message-tall-source")
        original.assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Original message"))
        val bounds = original.getUnclippedBoundsInRoot()
        val viewport = compose.onRoot().getUnclippedBoundsInRoot()
        val sourceHeight = bounds.bottom - bounds.top
        val viewportHeight = viewport.bottom - viewport.top
        assertTrue(sourceHeight > viewportHeight)
        assertTrue(bounds.top >= viewport.top)
        screenshot("reply-tall-source.png")
        output("reply-tall-layout.json").writeText(JSONObject()
            .put("sourceHeight", sourceHeight.value)
            .put("viewportHeight", viewportHeight.value)
            .put("sourceTop", bounds.top.value)
            .put("viewportTop", viewport.top.value)
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

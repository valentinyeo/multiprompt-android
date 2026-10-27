package dev.multiprompt.companion.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.multiprompt.companion.MainActivity
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatBubbleWidthTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun bothRolesUseNinetyPercentAndShortMessagesShrink() {
        val reply = "A wider message leaves more room for each line. " +
            "Long replies should be easier to read without changing the text size. " +
            "Both sides of the conversation share the same maximum width."
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MultipromptTheme(readerStyle = true) {
                    Column(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("Chat width proof", color = ReaderForeground)
                        Column(Modifier.fillMaxWidth().testTag("chat-width"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            for ((tag, user, text) in listOf(
                                Triple("agent-long", false, reply), Triple("user-long", true, reply),
                                Triple("agent-short", false, "Ready"), Triple("user-short", true, "Thanks"),
                            )) {
                                ChatBubble(isUser = user, sunlight = false, contentModifier = Modifier.testTag(tag)) {
                                    Text(text)
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val output = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "bubble-width").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(output, "chat.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val chat = compose.onNodeWithTag("chat-width").fetchSemanticsNode().boundsInRoot
        val measurements = JSONObject().put("screenWidth", bitmap.width).put("chatWidth", chat.width)
        val problems = mutableListOf<String>()
        for (role in listOf("agent", "user")) {
            val long = compose.onNodeWithTag("$role-long").fetchSemanticsNode().boundsInRoot
            val short = compose.onNodeWithTag("$role-short").fetchSemanticsNode().boundsInRoot
            measurements.put(role, JSONObject().put("longWidth", long.width).put("shortWidth", short.width)
                .put("left", long.left).put("right", long.right).put("top", long.top).put("bottom", long.bottom))
            if (kotlin.math.abs(long.width / chat.width - 0.9f) > 0.005f) problems += "$role maximum is ${long.width / chat.width}, expected 0.9"
            assertTrue("$role short message must shrink", short.width < long.width / 2)
            assertEquals("$role keeps its aligned edge", if (role == "agent") long.left else long.right,
                if (role == "agent") short.left else short.right, 1f)
        }
        measurements.put("problems", problems.joinToString("; "))
        File(output, "measurements.json").writeText(measurements.toString(2))
        assertTrue("CHAT_BUBBLE_WIDTH: ${problems.joinToString("; ")}", problems.isEmpty())
    }
}

package dev.multiprompt.companion.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import coil3.EventListener
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import dev.multiprompt.companion.reader.ChatHistoryMessage
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Rule
import org.junit.Test

class ChatImagePreviewTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun screencastPreviewAndBrokenLinkInRealChat() {
        val image = "https://screencast2.com/VkDx2.png"
        val broken = "https://screencast2.com/igsh-876-missing.png"
        val failed = AtomicBoolean(false)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).eventListener(object : EventListener() {
            override fun onError(request: ImageRequest, result: ErrorResult) {
                if (request.data == broken) failed.set(true)
            }
        }).build())
        compose.setContent {
            MaterialTheme {
                ChatHistoryList(
                    messages = listOf(
                        ChatHistoryMessage("image", "user", "Show this image\n$image", "", 0, 1),
                        ChatHistoryMessage("broken", "assistant", "Broken images stay links\n$broken", "", 1, 2),
                    ),
                    modifier = Modifier.background(Color.White).padding(12.dp),
                    sunlight = true,
                )
            }
        }
        compose.waitUntil(60_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Image preview: $image"))
                .fetchSemanticsNodes().isNotEmpty() && failed.get()
        }
        compose.onNodeWithContentDescription("Image preview: $image").assertIsDisplayed()
        compose.onNodeWithText(image, substring = true).assertIsDisplayed()
        compose.onNodeWithText(broken, substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Image preview: $broken").assertDoesNotExist()
        val output = File(context.getExternalFilesDir(null), "image-unfurl.png")
        output.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithContentDescription("Image preview: $image").performClick()
        compose.onNodeWithContentDescription("Full screen image").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close image").performClick()
        compose.onNodeWithContentDescription("Full screen image").assertDoesNotExist()
    }
}

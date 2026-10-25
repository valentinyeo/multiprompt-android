package dev.multiprompt.companion.ui

import android.graphics.Bitmap
import android.util.Log
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
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import coil3.EventListener
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.decode.DataSource
import dev.multiprompt.companion.reader.ChatHistoryMessage
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        val successes = AtomicInteger(0)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).eventListener(object : EventListener() {
            override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                if (request.data == image) successes.incrementAndGet()
            }
            override fun onError(request: ImageRequest, result: ErrorResult) {
                Log.e("ImagePreviewTest", "Image request failed: ${request.data}", result.throwable)
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
        val output = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")) {
            "Gradle must provide the instrumentation output directory"
        }, "image-unfurl.png")
        check(output.parentFile?.mkdirs() == true || output.parentFile?.isDirectory == true)
        output.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithContentDescription("Image preview: $image").performClick()
        compose.onNodeWithContentDescription("Full screen image").assertIsDisplayed()
        compose.waitUntil(30_000) { successes.get() >= 2 }
        val before = compose.onNodeWithContentDescription("Full screen image").captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
        compose.onNodeWithContentDescription("Full screen image").performTouchInput {
            pinch(Offset(center.x - 30f, center.y), Offset(center.x - 180f, center.y),
                Offset(center.x + 30f, center.y), Offset(center.x + 180f, center.y))
        }
        val after = compose.onNodeWithContentDescription("Full screen image").captureToImage().asAndroidBitmap()
        assertFalse("Pinch must change the displayed image", before.sameAs(after))
        compose.onNodeWithContentDescription("Close image").performClick()
        compose.onNodeWithContentDescription("Full screen image").assertDoesNotExist()
    }


    @Test
    fun assistantImageLoadsOnScrollAndUsesCache() {
        val image = "https://screencast2.com/VkDx2.png?inline-preview"
        val requested = AtomicBoolean(false)
        val networkLoads = AtomicInteger(0)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).eventListener(object : EventListener() {
            override fun onError(request: ImageRequest, result: ErrorResult) {
                Log.e("ImagePreviewTest", "Image request failed: ${request.data}", result.throwable)
            }
            override fun onStart(request: ImageRequest) {
                Log.i("ImagePreviewTest", "Image request started: ${request.data}")
                if (request.data == image) requested.set(true)
            }
            override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                if (request.data == image && result.dataSource == DataSource.NETWORK) networkLoads.incrementAndGet()
            }
        }).build())
        val messages = listOf(ChatHistoryMessage("old", "assistant", "[Screenshot]($image)", "", 0, 1)) +
            (1..40).map { ChatHistoryMessage("m$it", "user", "Message $it", "", it.toLong(), it + 1L) }
        compose.setContent {
            MaterialTheme {
                ChatHistoryList(messages = messages, modifier = Modifier.background(Color.White), sunlight = true)
            }
        }
        compose.runOnIdle { assertFalse("Offscreen images must not load", requested.get()) }
        compose.onNodeWithTag("chat-history").performScrollToIndex(40)
        compose.waitUntil(60_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Image preview: $image"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Image preview: $image").assertIsDisplayed()
        compose.onNodeWithText("Screenshot").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Image preview: $image").assertIsDisplayed()
        assertTrue(requested.get())
        compose.onNodeWithTag("chat-history").performScrollToIndex(0)
        compose.onNodeWithContentDescription("Image preview: $image").assertDoesNotExist()
        compose.onNodeWithTag("chat-history").performScrollToIndex(40)
        compose.waitUntil(30_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Image preview: $image"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Image preview: $image").assertIsDisplayed()
        assertEquals("Scrolling back must reuse the image without another network load", 1, networkLoads.get())
    }
}

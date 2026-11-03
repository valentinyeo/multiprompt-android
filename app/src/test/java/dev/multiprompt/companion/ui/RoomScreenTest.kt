package dev.multiprompt.companion.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import java.io.File
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import dev.multiprompt.companion.room.RoomEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class RoomScreenTest {
    private val scheduler = TestCoroutineScheduler()
    @get:Rule val compose = createComposeRule(effectContext = UnconfinedTestDispatcher(scheduler))

    private val room = RoomSummary(
        id = "room-1",
        name = "Launch room",
        members = listOf(RoomMember("m1", "HT Product Lead", 1), RoomMember("m2", "SF Design Lead", 4)),
    )

    @Test fun roomShowsNameMembersAndTheWaitingLine() {
        var backs = 0
        compose.setContent {
            MultipromptTheme(sunlight = false, material3 = false) {
                Surface {
                    RoomScreen(room, emptyList(), loading = false, exhausted = false, error = null, onLoadOlder = {}, onBack = { backs++ })
                }
            }
        }
        compose.onNodeWithText("Launch room").assertIsDisplayed()
        compose.onNodeWithText("HT Product Lead, SF Design Lead").assertIsDisplayed()
        compose.onNodeWithText("@HT Product Lead").assertIsDisplayed()
        compose.onNodeWithText("@SF Design Lead").assertIsDisplayed()
        compose.onNodeWithText("No messages yet").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        assertTrue(backs == 1)
    }

    @Test fun anAgentReplyCarriesItsNameAndMyMessageDoesNot() {
        val events = listOf(
            RoomEvent(1, "human", "Ship it today", null),
            RoomEvent(2, "agent_reply", "On it", "m2"),
        )
        val lines = roomLines(events, room.members)
        assertEquals("SF Design Lead", lines[1].author?.name)
        assertNull(lines[0].author)
        compose.setContent {
            MultipromptTheme(sunlight = false, material3 = false) {
                Surface {
                    RoomScreen(room, lines, loading = false, exhausted = false, error = null, onLoadOlder = {}, onBack = {})
                }
            }
        }
        compose.onNodeWithText("Ship it today").assertIsDisplayed()
        compose.onNodeWithText("On it").assertIsDisplayed()
        compose.onNodeWithText("SF Design Lead").assertIsDisplayed()
        compose.onNodeWithText("HT Product Lead").assertDoesNotExist()
        capture("room-conversation.png")
    }

    @Test fun aRoomWithNothingLoadedOffersLoadOlder() {
        var older = 0
        compose.setContent {
            MultipromptTheme(sunlight = false, material3 = false) {
                Surface {
                    RoomScreen(
                        room,
                        listOf(RoomLine(3, room.members.first(), "Hello")),
                        loading = false,
                        exhausted = false,
                        error = null,
                        onLoadOlder = { older++ },
                        onBack = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Load older").performClick()
        compose.waitForIdle()
        assertTrue(older == 1)
    }

    @Test fun roomRowShowsTheRoomAndItsMembers() {
        compose.setContent {
            MultipromptTheme(sunlight = false, material3 = false) {
                Surface { RoomRow(room) { } }
            }
        }
        compose.onNodeWithText("Launch room").assertIsDisplayed()
        compose.onNodeWithText("HT Product Lead, SF Design Lead").assertIsDisplayed()
        capture()
    }

    private fun capture(name: String = "room-row.png") {
        val root = compose.onRoot()
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
        val output = File("build/reports/igsh-1317/$name")
        checkNotNull(output.parentFile).mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}

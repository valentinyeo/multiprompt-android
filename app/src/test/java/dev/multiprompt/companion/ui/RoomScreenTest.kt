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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
        members = listOf(RoomMember("m1", "ht-product-lead", "HT Product Lead", 1), RoomMember("m2", "sf-design-lead", "SF Design Lead", 4)),
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

    @Test fun eachAnswerSitsBeneathItsOwnQuestionOnce() {
        val events = listOf(
            RoomEvent(1, "human", "First question", null, id = "q1"),
            RoomEvent(2, "human", "Second question", null, id = "q2"),
            RoomEvent(3, "agent_reply", "Second answer", "m2", id = "r2", parentEventId = "q2"),
            RoomEvent(4, "agent_reply", "First answer", "m1", id = "r1", parentEventId = "q1"),
            RoomEvent(4, "agent_reply", "First answer", "m1", id = "r1", parentEventId = "q1"),
            RoomEvent(5, "agent_reply", "Older answer", "m1", id = "r0", parentEventId = "q0"),
        )
        val lines = roomLines(events, room.members)
        assertEquals(listOf("First question", "First answer", "Second question", "Second answer", "Older answer"), lines.map { it.text })
        assertEquals("HT Product Lead", lines[1].author?.name)
        assertEquals("SF Design Lead", lines[3].author?.name)
    }

    @Test fun offlineAgentsSayTheMessageWasNotSent() {
        assertEquals("Agents are offline on this computer. Your message was not sent.", roomSendError("agents_offline"))
        assertEquals("Agents are offline on this computer. Your message was not sent.", roomSendError("unsupported_remote_capability"))
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

    @Test fun typingAtOpensTheAgentListAndSendsTheDraft() {
        var sent = ""
        compose.setContent {
            MultipromptTheme(sunlight = false, material3 = false) {
                Surface {
                    RoomScreen(room, emptyList(), loading = false, exhausted = false, error = null, onLoadOlder = {}, onSend = { sent = it }, onBack = {})
                }
            }
        }
        compose.onNodeWithText("Message Launch room").performTextInput("hello @")
        compose.onNodeWithText("@sf-design-lead").assertIsDisplayed().performClick()
        compose.onNodeWithText("Send").performClick()
        compose.waitForIdle()
        assertTrue(sent == "hello @sf-design-lead")
    }

    @Test fun aFailedSendKeepsTheDraftAndASentOneClearsIt() {
        val sent = androidx.compose.runtime.mutableIntStateOf(0)
        compose.setContent {
            MultipromptTheme(sunlight = false, material3 = false) {
                Surface {
                    RoomScreen(room, emptyList(), loading = false, exhausted = false, error = "Agents are offline on this computer. Your message was not sent.", onLoadOlder = {}, onSend = {}, sent = sent.intValue, onBack = {})
                }
            }
        }
        compose.onNodeWithText("Message Launch room").performTextInput("keep me")
        compose.onNodeWithText("Send").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("keep me").assertIsDisplayed()
        compose.onNodeWithText("Agents are offline on this computer. Your message was not sent.").assertIsDisplayed()
        sent.intValue = 1
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("keep me").fetchSemanticsNodes().isEmpty())
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

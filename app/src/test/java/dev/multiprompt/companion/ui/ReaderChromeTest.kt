package dev.multiprompt.companion.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.multiprompt.companion.dictation.DictationState
import dev.multiprompt.companion.dictation.DictationStatus
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.reader.ChatHistory
import dev.multiprompt.companion.reader.ChatHistoryCache
import dev.multiprompt.companion.reader.ChatHistoryConnection
import dev.multiprompt.companion.reader.ChatHistoryProtocol
import dev.multiprompt.companion.reader.ChatHistoryState
import dev.multiprompt.companion.reader.ReaderState
import dev.multiprompt.companion.reader.ReaderStatus
import dev.multiprompt.companion.reader.SessionReaderConnection
import dev.multiprompt.companion.ssh.TmuxText
import java.io.File
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import kotlinx.coroutines.channels.Channel
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderChromeTest {
    private val scheduler = TestCoroutineScheduler()
    @get:Rule val compose = createComposeRule(effectContext = UnconfinedTestDispatcher(scheduler))
    private lateinit var f: SessionActivityFixture
    private lateinit var connection: SessionReaderConnection
    private lateinit var reader: MutableStateFlow<ReaderState>
    private lateinit var history: MutableStateFlow<ChatHistoryState>
    private lateinit var chat: ChatHistoryConnection
    private val messageCount = 20
    private val modelLabel = "Opus 5.5 · medium"

    @Before fun setup() {
        f = SessionActivityFixture()
        val cache = ChatHistoryCache(f.app.cacheDir.resolve("reader-chrome-history"))
        connection = SessionReaderConnection(f.app.sshRepository, f.host, f.session.name,
            agent = AgentKind.CLAUDE, historyScript = byteArrayOf(), historyCache = cache)
        reader = f.field(connection, "_state")
        reader.value = ReaderState(status = ReaderStatus.Live, waitingForInput = true,
            runtimeDetails = TmuxText.RuntimeDetails("Opus 5.5", "medium"))
        val identity = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, f.session.name, f.session.workingDirectory)
        chat = ChatHistoryConnection(f.app.sshRepository, f.host, identity, byteArrayOf(), cache)
        ReflectionHelpers.setField(connection, "historyIdentity", identity)
        ReflectionHelpers.setField(connection, "historyConnection", chat)
        history = f.field(chat, "_state")
        history.value = ChatHistoryState(loaded = true, history = ChatHistory(sid = "chrome", rows =
            List(messageCount) { index ->
                ChatHistoryProtocol.Row(if (index % 2 == 0) "user" else "assistant",
                    "Message $index" + if (index == messageCount - 1) "\n\n1. First option\n2. Second option" else "",
                    "$index", "", index.toLong(), index + 1L)
            }))
    }

    @After fun cleanup() { connection.close(); f.close() }

    private fun render() {
        compose.setContent {
            ReaderScreen(
                connection = connection, dictation = f.app.deepgramDictation,
                screencast = f.app.screencastUploader,
                signedIn = true, onSignIn = {}, onSignOut = {},
                session = f.session.copy(paneCommand = "claude"),
                unread = false, archived = false, initialFontScale = 1f,
                onBack = {}, onMarkRead = {}, onArchiveToggle = {}, onRemind = {},
                onOpenTerminal = {}, onFontScaleChanged = {},
                onRename = { null }, onDissolve = {}, onEnd = {}, onSessionInteraction = {},
                onSessionInput = {}, technicalMode = false, onTechnicalModeChanged = {},
                sunlightMode = false, onSetAppTheme = {}, onResetFontScale = { 1f },
                sessionActionError = null, onClearSessionActionError = {}, onForget = {},
                onShowEtaAccuracy = {}, onReleaseNotes = {},
            )
        }
    }

    @Test fun chatScrollsUnderFloatingControlsWithSessionHeadline() {
        render()
        compose.onNodeWithText("Conversation").assertDoesNotExist()
        listOf("Scroll agent history up", "Open agent history", "Back to live").forEach {
            compose.onNodeWithContentDescription(it).assertDoesNotExist()
        }
        compose.onNodeWithText("$modelLabel · waiting for input", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(f.session.displayName, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Disconnected · retrying").assertDoesNotExist()
        screenshot("chat-live")
        compose.onNodeWithTag("chat-history").performScrollToIndex(messageCount - 1)
        val bar = compose.onNodeWithTag("reader-floating-controls").getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("chat-history").getUnclippedBoundsInRoot()
        assertTrue("Conversation must extend under controls: $viewport $bar", viewport.top < bar.bottom)
        val composer = compose.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
        assertTrue("Conversation must scroll behind composer: $viewport $composer", viewport.bottom >= composer.bottom)
        val message = compose.onNodeWithText("Message 0", useUnmergedTree = true)
            .assertIsDisplayed().onParent().getUnclippedBoundsInRoot()
        val gap = (message.top - bar.bottom).value
        assertTrue("First message must start within 24dp below the bar, gap=${gap}dp", gap in 0f..24f)
        screenshot()
        compose.onNodeWithContentDescription("Session actions").performClick()
        compose.onNodeWithText("Change ›").performScrollTo().performClick()
        compose.onNodeWithText("Current: $modelLabel").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Current: $modelLabel · waiting for input").assertDoesNotExist()
    }

    @Test fun dictatingKeepsTheMessageBoxHeight() {
        render()
        compose.onNodeWithContentDescription("Start dictation").assertIsDisplayed()
        compose.onNodeWithTag("dictation-stop").assertDoesNotExist()
        val before = compose.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
        screenshot("dictation-before")
        val dictationState: MutableStateFlow<DictationState> = f.field(f.app.deepgramDictation, "_state")
        val dictationLevel: MutableStateFlow<Float> = f.field(f.app.deepgramDictation, "_level")
        compose.runOnIdle {
            dictationState.value = DictationState(status = DictationStatus.LISTENING, configured = true, sessionKey = "${f.host.id}::${f.session.name}")
            dictationLevel.value = 0.7f
        }
        compose.onNodeWithText("Message ${f.session.displayName}").assertDoesNotExist()
        compose.onNodeWithContentDescription("Start dictation").assertDoesNotExist()
        compose.onNodeWithTag("dictation-stop").assertIsDisplayed()
        compose.onNodeWithTag("dictation-send").assertIsDisplayed()
        compose.onNodeWithTag("dictation-timer").assertIsDisplayed()
        compose.onNodeWithText(f.session.displayName, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Messaging", substring = true).assertDoesNotExist()
        val stop = compose.onNodeWithTag("dictation-stop").getUnclippedBoundsInRoot()
        val meter = compose.onNodeWithTag("dictation-meter").getUnclippedBoundsInRoot()
        val send = compose.onNodeWithTag("dictation-send").getUnclippedBoundsInRoot()
        assertTrue("stop, meter, send read left to right on one row: $stop $meter $send",
            stop.right <= meter.left && meter.right <= send.left && stop.top == meter.top && meter.top == send.top)
        screenshot("dictation-during")
        listOf(stop, meter, send).forEach { control ->
            assertEquals("dictation control matches message box height", before.bottom - before.top, control.bottom - control.top)
            assertEquals("bottom bar must not jump", before.top, control.top)
            assertEquals("bottom bar stays anchored", before.bottom, control.bottom)
        }
        compose.onNodeWithTag("dictation-stop").performClick()
        compose.runOnIdle {
            assertTrue("stop button stops dictation", f.app.deepgramDictation.state.value.status == DictationStatus.IDLE)
        }
        compose.onNodeWithTag("dictation-stop").assertDoesNotExist()
        compose.onNodeWithContentDescription("Start dictation").assertIsDisplayed()
        val after = compose.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
        assertEquals("message box returns at the same bounds", before, after)
        screenshot("dictation-after")
        println("DICTATION_GEOMETRY before=$before during=$stop meter=$meter send=$send after=$after")
        compose.runOnIdle { dictationState.value = DictationState(status = DictationStatus.LISTENING, configured = true, sessionKey = "${f.host.id}::${f.session.name}") }
        compose.onNodeWithTag("dictation-send").performClick()
        compose.runOnIdle {
            assertTrue("send button stops dictation", f.app.deepgramDictation.state.value.status == DictationStatus.IDLE)
        }
    }

    @Test fun replyCardSitsInsideTheMessageBox() {
        render()
        val before = compose.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
        compose.onNodeWithTag("chat-history").performScrollToIndex(messageCount - 1)
        compose.onNodeWithText("Message 0", useUnmergedTree = true).assertIsDisplayed().performTouchInput { longClick() }
        compose.onNodeWithText("Reply").performClick()
        compose.onNodeWithText("Replying").assertIsDisplayed()
        compose.onNodeWithText("Reply to", substring = true).assertDoesNotExist()
        val field = compose.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
        val card = compose.onNodeWithTag("reply-card", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val send = compose.onNodeWithContentDescription("Send prompt", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val attach = compose.onNodeWithContentDescription("Attach").getUnclippedBoundsInRoot()
        screenshot("reply-card")
        println("REPLY_CARD_GEOMETRY field=$field card=$card send=$send attach=$attach")
        assertEquals("card inset top", 6f, (card.top - field.top).value, 0.5f)
        assertEquals("card inset start", 6f, (card.left - field.left).value, 0.5f)
        assertEquals("card inset end", 6f, (field.right - card.right).value, 0.5f)
        assertTrue("typing line sits below the card: $card $send", send.top >= card.bottom)
        assertEquals("box keeps its bottom edge", before.bottom.value, field.bottom.value, 0.5f)
        assertEquals("plus button stays on the bottom line", field.bottom.value, attach.bottom.value, 0.5f)
        compose.onNodeWithContentDescription("Cancel reply", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("reply-card", useUnmergedTree = true).assertDoesNotExist()
        assertEquals("message box returns to one line", before, compose.onNode(hasSetTextAction()).getUnclippedBoundsInRoot())
    }

    @Test fun historyMenuIsOnlyAvailableForAlternateScreenSessions() {
        render()
        compose.onNodeWithContentDescription("Session actions").performClick()
        compose.onNodeWithText("Session history").assertDoesNotExist()
        compose.runOnIdle { reader.value = reader.value.copy(alternateOn = true) }
        compose.onNodeWithText("Session history").performScrollTo().assertIsDisplayed()
        listOf("Scroll agent history up", "Open agent history", "Back to live").forEach {
            compose.onNodeWithContentDescription(it).assertDoesNotExist()
        }
        compose.onNodeWithText("Session history").performClick()
        compose.runOnIdle { scheduler.advanceTimeBy(1_201); scheduler.runCurrent() }
        compose.onNodeWithText("Capturing…").assertIsDisplayed()
        compose.onNodeWithText("Back to live").assertIsDisplayed().performClick()
        compose.onNodeWithText("Capturing…").assertDoesNotExist()
        compose.runOnIdle { assertTrue(!f.field<Boolean>(connection, "historyMode")) }
    }

    @Test fun menuStatusJoinsOnlyAvailableModelEtaAndWaitingParts() {
        render()
        compose.onNodeWithContentDescription("Session actions").performClick()
        compose.runOnIdle {
            reader.value = reader.value.copy(eta = TmuxText.SessionEta(System.currentTimeMillis() + 200_000, 240_000, ""))
        }
        compose.onNodeWithText("$modelLabel · ETA 3m · waiting for input", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { reader.value = reader.value.copy(eta = null, waitingForInput = false) }
        compose.onNodeWithText(modelLabel, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { reader.value = reader.value.copy(runtimeDetails = TmuxText.RuntimeDetails()) }
        compose.onNodeWithText("Model / effort", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { reader.value = reader.value.copy(waitingForInput = true) }
        compose.onNodeWithText("waiting for input", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun sendWhileDisconnectedWaitsAndGoesOutOnceOnReconnect() {
        render()
        compose.runOnIdle { history.value = history.value.copy(retrying = true) }
        compose.onNode(hasSetTextAction()).performTextInput("offline hello")
        compose.onNodeWithContentDescription("Send prompt").performClick()
        compose.runOnIdle { assertEquals("Will send when back online", ShadowToast.getTextOfLatestToast()) }
        compose.runOnIdle {
            ReflectionHelpers.setField(chat, "selected", true)
            ReflectionHelpers.setField(chat, "freshSid", "chrome")
            history.value = history.value.copy(retrying = false)
        }
        compose.runOnIdle { scheduler.advanceTimeBy(3_000); scheduler.runCurrent() }
        compose.runOnIdle {
            val commands = f.field<Channel<Any>>(chat, "commands")
            val sent = generateSequence { commands.tryReceive().getOrNull() }.map { it.toString() }.toList()
            assertEquals(1, sent.count { "offline hello" in it })
        }
    }

    @Test fun retryingShowsNoDisconnectWarning() {
        render()
        compose.runOnIdle { history.value = history.value.copy(retrying = true) }
        compose.onNodeWithText("Disconnected · retrying").assertDoesNotExist()
        compose.onNodeWithText("Reconnect").assertDoesNotExist()
        compose.onNodeWithText("Conversation").assertDoesNotExist()
        compose.runOnIdle { history.value = history.value.copy(retrying = false) }
        compose.onNodeWithText("Disconnected · retrying").assertDoesNotExist()
    }

    private fun screenshot(name: String = "chat-view") {
        val root = compose.onRoot()
        val view = (root.fetchSemanticsNode().root as ViewRootForTest).view.rootView
        val attachInfo = ReflectionHelpers.getField<Any>(view, "mAttachInfo")
        val hardwareAccelerated = view.isHardwareAccelerated
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
            ReflectionHelpers.setField(attachInfo, "mHardwareAccelerated", hardwareAccelerated)
        }
        val output = File("build/reports/igsh-844/$name.png")
        checkNotNull(output.parentFile).mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue(output.isFile && output.length() > 0)
    }
}

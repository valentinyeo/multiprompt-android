package dev.multiprompt.companion.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextEquals
import org.junit.Assert.assertEquals
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.reader.ChatHistoryCache
import dev.multiprompt.companion.reader.ChatHistoryConnection
import dev.multiprompt.companion.reader.ChatHistoryProtocol
import dev.multiprompt.companion.reader.ChatHistoryState
import dev.multiprompt.companion.reader.ReaderState
import dev.multiprompt.companion.reader.ReaderStatus
import dev.multiprompt.companion.reader.SessionReaderConnection
import java.io.File
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
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
class ReaderAttachOutlineTest {
    private val scheduler = TestCoroutineScheduler()
    @get:Rule val compose = createComposeRule(effectContext = UnconfinedTestDispatcher(scheduler))
    private lateinit var f: SessionActivityFixture
    private lateinit var connection: SessionReaderConnection

    @Before fun setup() {
        f = SessionActivityFixture()
        val cache = ChatHistoryCache(f.app.cacheDir.resolve("attach-outline-history"))
        connection = SessionReaderConnection(f.app.sshRepository, f.host, f.session.name,
            agent = AgentKind.CLAUDE, historyScript = byteArrayOf(), historyCache = cache)
        val identity = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, f.session.name, f.session.workingDirectory)
        val chat = ChatHistoryConnection(f.app.sshRepository, f.host, identity, byteArrayOf(), cache)
        f.field<MutableStateFlow<ChatHistoryState>>(chat, "_state").value = ChatHistoryState(loaded = true)
        f.field<MutableStateFlow<ReaderState>>(connection, "_state").value = ReaderState(status = ReaderStatus.Live)
        ReflectionHelpers.setField(connection, "historyIdentity", identity)
        ReflectionHelpers.setField(connection, "historyConnection", chat)
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

    private fun capture(node: SemanticsNodeInteraction, name: String): Bitmap {
        val view = (node.fetchSemanticsNode().root as ViewRootForTest).view.rootView
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
            node.captureToImage().asAndroidBitmap()
        } finally {
            redraw.cancel()
            ReflectionHelpers.setField(attachInfo, "mHardwareAccelerated", hardwareAccelerated)
        }
        val output = File("build/reports/igsh-844/igsh-880-$name.png")
        checkNotNull(output.parentFile).mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return bitmap
    }

    private fun isOutline(pixel: Int): Boolean {
        val expected = ReaderOutline.toArgb()
        return listOf(16, 8, 0).all { shift -> Math.abs(((pixel shr shift) and 0xFF) - ((expected shr shift) and 0xFF)) <= 24 }
    }

    private fun hasOutlineAlongRow(bitmap: Bitmap, y: Int, width: Int): Boolean =
        (0 until minOf(width, bitmap.width)).any { isOutline(bitmap.getPixel(it, y)) }

    private fun assertRuntimeCommand(expected: String) {
        val requests = f.field<Channel<Any>>(connection, "requests")
        val request = checkNotNull(requests.tryReceive().getOrNull())
        assertEquals("RuntimeCommand", request.javaClass.simpleName)
        assertEquals(expected, f.field<String>(request, "text"))
        assertTrue(requests.tryReceive().isFailure)
        assertTrue(connection.state.value.sentPrompts.isEmpty())
    }

    @Test fun plusButtonIsDrawnWithAnOutline() {
        render()
        val plus = compose.onNodeWithContentDescription("Attach").assertIsDisplayed()
        val bitmap = capture(plus, "plus-button")
        assertTrue(hasOutlineAlongRow(bitmap, bitmap.height / 2, 4))
    }

    @Test fun plusModelAndEffortUseNativeCommandsWithoutDroppingTheDraft() {
        render()
        compose.onNode(hasSetTextAction()).performTextInput("keep my draft")
        compose.onNodeWithContentDescription("Attach").performClick()
        compose.onNodeWithText("Model").performClick()
        compose.onNodeWithText("Use Claude Sonnet").performClick()
        compose.onNode(hasSetTextAction()).assertTextEquals("keep my draft")
        compose.runOnIdle {
            assertRuntimeCommand("/model sonnet")
        }
        compose.onNodeWithContentDescription("Attach").performClick()
        compose.onNodeWithText("Effort").performClick()
        compose.onNodeWithText("Set effort: high").performClick()
        compose.onNode(hasSetTextAction()).assertTextEquals("keep my draft")
        compose.runOnIdle { assertRuntimeCommand("/effort high") }
    }

    @Test fun attachMenuIsACardWithAnOutlineAndLineIcons() {
        render()
        compose.onNodeWithContentDescription("Attach").performClick()
        compose.onNodeWithText("Add image").assertIsDisplayed()
        compose.onNodeWithText("Skill").assertIsDisplayed()
        val menu = compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog()))
        val bitmap = capture(menu, "attach-menu")
        assertTrue(hasOutlineAlongRow(bitmap, bitmap.height / 2, 8))
        compose.onNodeWithText("Add image").performClick()
    }
}

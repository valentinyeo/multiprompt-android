package dev.multiprompt.companion.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import dev.multiprompt.companion.AppSection
import dev.multiprompt.companion.AppUiState
import dev.multiprompt.companion.BuildConfig
import dev.multiprompt.companion.MainViewModel
import dev.multiprompt.companion.MultipromptApplication
import dev.multiprompt.companion.SessionBucket
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import dev.multiprompt.companion.reader.SessionReaderConnection
import dev.multiprompt.companion.terminal.TerminalConnection
import dev.multiprompt.companion.terminal.TerminalStatus
import java.io.File
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalCoroutinesApi::class, androidx.compose.ui.InternalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VersionLabelTest {
    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val compose = createComposeRule(effectContext = UnconfinedTestDispatcher(scheduler))

    private val sunlight = mutableStateOf(false)
    private var clicks = 0
    private val inboxState = mutableStateOf(AppUiState())
    private val models = ViewModelStore()
    private val session = TmuxSession("host", "Chat View with a long session title", 1, 0, 20)

    @After
    fun closeModels() {
        compose.runOnIdle { models.clear() }
    }

    private fun viewModel(): MainViewModel {
        val application = MultipromptApplication()
        ReflectionHelpers.callInstanceMethod<Unit>(
            application, "attach",
            ReflectionHelpers.ClassParameter.from(Context::class.java, RuntimeEnvironment.getApplication()),
        )
        application.getSharedPreferences("updates", Context.MODE_PRIVATE).edit()
            .putLong("last_check_ms", System.currentTimeMillis()).commit()
        return MainViewModel(application).also { models.put("main", it) }
    }

    private fun render(content: @Composable () -> Unit) {
        compose.setContent {
            MultipromptTheme(sunlight = sunlight.value) {
                Surface(Modifier.fillMaxSize()) { Column { content() } }
            }
        }
    }

    @Composable
    private fun inbox() {
        SessionsScreen(
            state = inboxState.value,
            onOpen = {},
            onOpenTerminal = {},
            onArchive = {},
            onArchiveUntil = { _, _ -> },
            onRestore = {},
            onDissolve = {},
            onEnd = {},
            onRestoreDissolved = {},
            onForgetDissolved = {},
            onMarkUnread = {},
            onToggleArchived = {},
            onSelectSessionBucket = {},
            onSelectWorkspace = {},
            onSwitchWorkspace = {},
            onMoveWorkspaceSplit = { _, _ -> },
            onResetWorkspaceSplitOrder = {},
            onCreateWorkspace = { null },
            onMoveSession = { _, _ -> },
            onRenameSession = { _, _ -> null },
            onSetFavorite = { _, _ -> },
            onRefresh = {},
            onSelectSection = {},
            onReleaseNotes = { clicks++ },
            onForgetSession = {},
            onStartNewSession = {},
            onSetNewestSessionsAtBottom = {},
            onSetAllSplitOnRight = {},
            appTheme = AppTheme.SYSTEM,
            onSetAppTheme = {},
            onClearSessionActionError = {},
            readerDefaultFontScale = 1f,
            onSetReaderDefaultFontScale = {},
            crashReport = null,
            onClearCrashReport = {},
            onMapAccountHost = { _, _ -> },
            signedIn = true,
            onSignIn = {},
            onSignOut = {},
        )
    }

    private fun checkLayout(title: String, action: String? = null): SemanticsNodeInteraction {
        val version = compose.onNodeWithTag("app-version")
        version
            .assertIsDisplayed()
            .assertTextEquals(BuildConfig.VERSION_NAME)
            .assertContentDescriptionEquals("Version ${BuildConfig.VERSION_NAME}, open release notes")
        val titleNode = compose.onNodeWithText(title, useUnmergedTree = true).assertIsDisplayed()
        val labelBounds = version.getUnclippedBoundsInRoot()
        val titleBounds = titleNode.getUnclippedBoundsInRoot()
        assertTrue("The title must keep usable width", (titleBounds.right - titleBounds.left).value >= 40f)
        assertTrue("Version label must remain compact", (labelBounds.right - labelBounds.left).value <= 72f)
        assertTrue("Version must not overlap the title: $titleBounds vs $labelBounds", titleBounds.right <= labelBounds.left)
        val layout = version.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult]
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        assertTrue(layout.action!!.invoke(results))
        assertEquals(1, results.single().lineCount)
        if (action != null) {
            val actionBounds = compose.onNodeWithContentDescription(action).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("Version must remain left of the action button", labelBounds.right <= actionBounds.left)
        }
        return version
    }

    private fun screenshot(name: String) {
        val root = compose.onRoot()
        val view = (root.fetchSemanticsNode().root as ViewRootForTest).view.rootView
        val attachInfo = ReflectionHelpers.getField<Any>(view, "mAttachInfo")
        val hardwareAccelerated = view.isHardwareAccelerated
        ReflectionHelpers.setField(attachInfo, "mHardwareAccelerated", false)
        val redraw = CoroutineScope(UnconfinedTestDispatcher(scheduler)).launch {
            delay(16)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            view.draw(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
            view.viewTreeObserver.dispatchOnDraw()
            shadowOf(Looper.getMainLooper()).idle()
        }
        val bitmap = try {
            root.captureToImage().asAndroidBitmap()
        } finally {
            redraw.cancel()
            ReflectionHelpers.setField(attachInfo, "mHardwareAccelerated", hardwareAccelerated)
        }
        val output = File("build/outputs/version-label/$name.png")
        checkNotNull(output.parentFile).mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue(output.isFile && output.length() > 0)
    }

    private fun verifyThemes(name: String, title: String, action: String? = null) {
        listOf(false, true).forEachIndexed { index, light ->
            compose.runOnIdle { sunlight.value = light }
            val version = checkLayout(title, action)
            screenshot(if (light) "$name-sunlight" else name)
            version.performClick()
            compose.runOnIdle { assertEquals(index + 1, clicks) }
        }
    }

    @Test
    fun inboxVersionLivesInMenuAndOpensReleaseNotes() {
        render { inbox() }
        listOf(false, true).forEachIndexed { index, light ->
            compose.runOnIdle { sunlight.value = light }
            compose.onNodeWithTag("app-version").assertDoesNotExist()
            compose.onNodeWithContentDescription("Session list actions").performClick()
            compose.onNodeWithTag("app-version").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(index + 1, clicks) }
        }
    }

    @Test
    fun refreshingInboxBucketsKeepTheirTitleAndFullMenuButton() {
        render { inbox() }
        val initialMenu = compose.onNodeWithContentDescription("Session list actions").getUnclippedBoundsInRoot()
        val menuWidth = initialMenu.right - initialMenu.left
        listOf(SessionBucket.OPEN to "Inbox", SessionBucket.WAITING to "Waiting", SessionBucket.ARCHIVE to "Archive").forEach { (bucket, title) ->
            compose.runOnIdle { inboxState.value = AppUiState(sessionBucket = bucket, refreshing = true) }
            compose.onNodeWithText(title).assertIsDisplayed()
            compose.onNodeWithTag("app-version").assertDoesNotExist()
            val menu = compose.onNodeWithContentDescription("Session list actions").getUnclippedBoundsInRoot()
            val root = compose.onRoot().getUnclippedBoundsInRoot()
            assertEquals("$title menu must retain its full width", menuWidth, menu.right - menu.left)
            assertTrue("Menu must remain within the bar", menu.right <= root.right)
        }
    }

    @Test
    fun readerVersionDoesNotDisplaceTitleOrMenuAndOpensReleaseNotes() {
        render {
            ReaderTopBar(
                session = session,
                modelLabel = "Claude Opus · high",
                statusLabel = null,
                onModelCommand = {},
                onOpenTerminal = {},
                onReleaseNotes = { clicks++ },
                onMenuClick = {},
                menuContent = {},
            )
        }
        verifyThemes("reader", session.displayName, "Session actions")
    }

    @Test
    fun material3ReaderVersionUsesTinyMonospaceOutlineAndKeepsReleaseNotesAction() {
        compose.setContent {
            MultipromptTheme(sunlight = sunlight.value, material3 = true) {
                ReaderTopBar(
                    session = session,
                    modelLabel = "Claude Opus · high",
                    onModelCommand = {},
                    onOpenTerminal = {},
                    onReleaseNotes = { clicks++ },
                    onMenuClick = {},
                    menuContent = {},
                )
            }
        }
        listOf(false, true).forEachIndexed { index, light ->
            compose.runOnIdle { sunlight.value = light }
            val version = checkLayout(session.displayName, "Session actions")
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            version.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            val style = results.single().layoutInput.style
            assertEquals(10f, style.fontSize.value)
            assertEquals(androidx.compose.ui.text.font.FontFamily.Monospace, style.fontFamily)
            assertEquals(if (light) Material3LightColors.outline else Material3DarkColors.outline, style.color)
            version.performClick()
            compose.runOnIdle { assertEquals(index + 1, clicks) }
        }
    }

    @Test
    fun terminalVersionIsFirstActionAndOpensReleaseNotes() {
        render {
            TerminalTopBar(
                title = "Shell View with a long session title",
                subtitle = "session · connected",
                onBack = {},
                onReleaseNotes = { clicks++ },
                onOpenHistory = {},
                onLiveView = {},
                onShowKeyboard = {},
            )
        }
        verifyThemes("terminal", "Shell View with a long session title", "Open shell history")
    }

    @Test
    fun settingsVersionClosesPageBeforeOpeningReleaseNotes() {
        render { inbox() }
        listOf(false, true).forEachIndexed { index, light ->
            compose.runOnIdle { sunlight.value = light }
            compose.onNodeWithContentDescription("Session list actions").performClick()
            compose.onNodeWithText("Settings").performClick()
            val version = checkLayout("Session list settings")
            screenshot(if (light) "settings-sunlight" else "settings")
            version.performClick()
            compose.onNodeWithText("Session list settings").assertDoesNotExist()
            compose.runOnIdle { assertEquals(index + 1, clicks) }
        }
    }

    @Test
    fun headerVersionOpensReleaseNotesWithoutOverlappingTitleOrMenu() {
        render {
            Header(false, {}, AppSection.UPDATE, {}, onReleaseNotes = { clicks++ })
        }
        verifyThemes("header", "Update", "Open navigation")
    }

    @Test
    fun versionContrastIsAtLeastThreeInDarkAndSunlightThemes() {
        listOf(MultipromptColors, SunlightColors).forEach { scheme ->
            val foreground = scheme.onSurfaceVariant.luminance()
            val background = scheme.surface.luminance()
            val contrast = (maxOf(foreground, background) + 0.05f) / (minOf(foreground, background) + 0.05f)
            assertTrue("Version contrast $contrast must be >= 3:1", contrast >= 3f)
        }
    }

    @Test
    fun openReleaseNotesClosesTerminalWithoutReopeningReaderAndClearsOverlays() {
        compose.runOnIdle {
            val model = viewModel()
            val app = model.getApplication<MultipromptApplication>()
            val host = HostProfile("host", "VPS", "example.com", username = "user", keySecretId = "key")
            val terminal = TerminalConnection(app.sshRepository, host, session.name)
            val reader = SessionReaderConnection(
                app.sshRepository, host, session.name,
                historyScript = byteArrayOf(),
                historyCache = dev.multiprompt.companion.reader.ChatHistoryCache(app.cacheDir.resolve("version-label-history")),
            )
            val state = ReflectionHelpers.getField<MutableStateFlow<AppUiState>>(model, "_state")
            state.value = AppUiState(
                section = AppSection.SESSIONS,
                terminal = terminal,
                terminalSession = session,
                terminalFromReader = true,
                reader = reader,
                readerSession = session,
                readerSwipeOrder = listOf("host:session"),
                editorVisible = true,
            )
            try {
                model.openReleaseNotes()
                val result = model.state.value
                assertEquals(AppSection.UPDATE, result.section)
                assertEquals(TerminalStatus.Closed, terminal.status.value)
                assertNull(result.terminal)
                assertNull(result.terminalSession)
                assertFalse(result.terminalFromReader)
                assertNull(result.reader)
                assertNull(result.readerSession)
                assertTrue(result.readerSwipeOrder.isEmpty())
                assertFalse(result.editorVisible)
                model.openReleaseNotes()
                assertEquals(AppSection.UPDATE, model.state.value.section)
            } finally {
                terminal.close()
                reader.close()
            }
        }
    }
}

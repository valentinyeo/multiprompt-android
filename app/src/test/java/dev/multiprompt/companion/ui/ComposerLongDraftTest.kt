package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.reader.ChatHistoryCache
import dev.multiprompt.companion.reader.ChatHistoryConnection
import dev.multiprompt.companion.reader.ChatHistoryProtocol
import dev.multiprompt.companion.reader.ChatHistoryState
import dev.multiprompt.companion.reader.ChatPollGate
import dev.multiprompt.companion.reader.ReaderState
import dev.multiprompt.companion.reader.ReaderStatus
import dev.multiprompt.companion.reader.SessionReaderConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerLongDraftTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var f: SessionActivityFixture
    private lateinit var connection: SessionReaderConnection

    private val longDraft = "Checking when it resets. We need instructions for this, " +
        "this never used to happen. We had a very good setup for this in place, " +
        "but since using the new stack this has broken down."

    @Before fun setup() {
        f = SessionActivityFixture()
        val cache = ChatHistoryCache(f.app.cacheDir.resolve("long-draft-history"))
        connection = SessionReaderConnection(
            f.app.sshRepository, f.host, f.session.name,
            agent = AgentKind.CLAUDE, historyScript = byteArrayOf(), historyCache = cache,
        )
        val identity = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, f.session.name, f.session.workingDirectory)
        val chat = ChatHistoryConnection(f.app.sshRepository, f.host, identity, byteArrayOf(), cache, pollGate = ChatPollGate())
        f.field<MutableStateFlow<ChatHistoryState>>(chat, "_state").value = ChatHistoryState(loaded = true)
        f.field<MutableStateFlow<ReaderState>>(connection, "_state").value = ReaderState(status = ReaderStatus.Live)
        ReflectionHelpers.setField(connection, "historyIdentity", identity)
        ReflectionHelpers.setField(connection, "historyConnection", chat)
    }

    @After fun cleanup() {
        connection.close()
        f.close()
    }

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
                sunlightMode = true, onSetAppTheme = {}, onResetFontScale = { 1f },
                sessionActionError = null, onClearSessionActionError = {}, onForget = {},
                onShowEtaAccuracy = {}, onReleaseNotes = {},
            )
        }
    }

    private fun node(interaction: SemanticsNodeInteraction): SemanticsNode = interaction.fetchSemanticsNode()

    private fun descendants(root: SemanticsNode): List<SemanticsNode> =
        listOf(root) + root.children.flatMap { descendants(it) }

    private fun texts(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()

    @Test fun aLongDraftKeepsEveryComposerControlOnOneRowAndShowsNoHint() {
        render()
        compose.onNode(hasSetTextAction()).performTextInput(longDraft)
        compose.waitForIdle()

        val bar = node(compose.onNodeWithTag("floating-bottom-bar"))
        val field = node(compose.onNode(hasSetTextAction()))
        val attach = node(compose.onNodeWithContentDescription("Attach"))
        val root = node(compose.onRoot())
        compose.onRoot().printToString(maxDepth = 20).also { println("COMPOSER_TREE_BEGIN\n$it\nCOMPOSER_TREE_END") }

        val barBounds = bar.boundsInRoot
        val fieldBounds = field.boundsInRoot
        val attachBounds = attach.boundsInRoot
        val rootBounds = root.boundsInRoot
        println("COMPOSER_BOUNDS bar=$barBounds field=$fieldBounds attach=$attachBounds root=$rootBounds")

        assertTrue(
            "the + button sits inside the composer row: attach=$attachBounds row=$barBounds",
            attachBounds.top >= barBounds.top && attachBounds.bottom <= barBounds.bottom &&
                attachBounds.left >= barBounds.left && attachBounds.right <= barBounds.right,
        )
        assertTrue(
            "the text field sits inside the composer row: field=$fieldBounds row=$barBounds",
            fieldBounds.top >= barBounds.top && fieldBounds.bottom <= barBounds.bottom &&
                fieldBounds.left >= barBounds.left && fieldBounds.right <= barBounds.right,
        )
        assertTrue(
            "the + button is bottom aligned with the text field: attach=$attachBounds field=$fieldBounds",
            kotlin.math.abs(attachBounds.bottom - fieldBounds.bottom) <= 4f,
        )
        assertTrue(
            "the composer row bottom sits at the screen bottom: row=$barBounds root=$rootBounds",
            kotlin.math.abs(barBounds.bottom - rootBounds.bottom) <= 1f,
        )
        assertTrue(
            "the draft grew the field instead of the row: field=$fieldBounds",
            fieldBounds.height > (ReaderControlSize.value * 2).toFloat(),
        )
        val draftTexts = descendants(bar).flatMap { texts(it) }
        assertEquals("the composer row shows only the draft, no hint", listOf(longDraft), draftTexts)
    }
}

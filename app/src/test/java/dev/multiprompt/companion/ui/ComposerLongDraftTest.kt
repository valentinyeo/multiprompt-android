package dev.multiprompt.companion.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.reader.ChatHistoryCache
import dev.multiprompt.companion.reader.ChatHistoryConnection
import dev.multiprompt.companion.reader.ChatHistoryProtocol
import dev.multiprompt.companion.reader.ChatHistoryState
import dev.multiprompt.companion.reader.ChatPollGate
import dev.multiprompt.companion.reader.ReaderState
import dev.multiprompt.companion.reader.ReaderStatus
import dev.multiprompt.companion.reader.SessionReaderConnection
import dev.multiprompt.companion.skills.NativeSkill
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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

    private val skill = "/review-native"
    private val longDraft = "Checking when it resets. We need instructions for this, " +
        "this never used to happen. We had a very good setup for this in place, " +
        "but since using the new stack this has broken down. $skill"

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
        f.field<MutableStateFlow<ReaderState>>(connection, "_state").value = ReaderState(
            status = ReaderStatus.Live,
            nativeSkills = listOf(NativeSkill("review-native", "Review the current native change")),
        )
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

    private fun textLayout(node: SemanticsNode): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.first()
    }

    private fun near(pixel: Int, expected: Int, tolerance: Int = 24): Boolean =
        listOf(16, 8, 0).all { shift -> Math.abs(((pixel shr shift) and 0xFF) - ((expected shr shift) and 0xFF)) <= tolerance }

    private fun save(node: SemanticsNodeInteraction, name: String): Bitmap {
        val bitmap = node.captureToImage().asAndroidBitmap()
        val output = File("build/reports/igsh-844/igsh-2957-$name.png")
        checkNotNull(output.parentFile).mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return bitmap
    }

    @Test fun aLongDraftKeepsEveryComposerControlOnOneRowAndShowsNoHint() {
        render()
        compose.onNode(hasSetTextAction()).performTextInput(longDraft)
        compose.waitForIdle()

        val bar = node(compose.onNodeWithTag("floating-bottom-bar"))
        val field = node(compose.onNode(hasSetTextAction()))
        val attach = node(compose.onNodeWithContentDescription("Attach"))
        val mic = node(compose.onNodeWithContentDescription("Start dictation"))
        val send = node(compose.onNodeWithContentDescription("Send prompt"))
        val root = node(compose.onRoot())

        val barBounds = bar.boundsInRoot
        val fieldBounds = field.boundsInRoot
        val attachBounds = attach.boundsInRoot
        val micBounds = mic.boundsInRoot
        val sendBounds = send.boundsInRoot
        val rootBounds = root.boundsInRoot
        println(
            "COMPOSER_BOUNDS bar=$barBounds field=$fieldBounds attach=$attachBounds " +
                "mic=$micBounds send=$sendBounds root=$rootBounds",
        )

        val layout = textLayout(field)
        val tokenStart = longDraft.indexOf(skill)
        val first = layout.getBoundingBox(tokenStart)
        val last = layout.getBoundingBox(longDraft.length - 1)
        println("COMPOSER_TOKEN layout_start=$first layout_end=$last lines=${layout.lineCount}")

        val bitmap = save(compose.onNode(hasSetTextAction()), "composer-field")
        val surface = Kit.Surface.toArgb()
        for (y in 0 until bitmap.height) {
            var pill = 0
            var pillMin = bitmap.width
            var pillMax = 0
            for (x in 0 until bitmap.width) {
                if (near(bitmap.getPixel(x, y), surface, 8)) {
                    pill++
                    pillMin = minOf(pillMin, x)
                    pillMax = maxOf(pillMax, x)
                }
            }
            if (pill > 20) println("COMPOSER_ROW y=$y pill=$pill pillX=$pillMin..$pillMax")
        }

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
            "dictation and send stay bottom aligned with the grown text field: mic=$micBounds send=$sendBounds field=$fieldBounds",
            kotlin.math.abs(micBounds.bottom - fieldBounds.bottom) <= 4f &&
                kotlin.math.abs(sendBounds.bottom - fieldBounds.bottom) <= 4f,
        )
        assertTrue(
            "the composer row reaches the bottom of the screen: row=$barBounds root=$rootBounds",
            barBounds.bottom >= rootBounds.bottom - (2 * ReaderControlSpacing.value).toFloat(),
        )
        assertTrue(
            "the draft grew the field instead of the row: field=$fieldBounds",
            fieldBounds.height > (ReaderControlSize.value * 2).value,
        )
        val draftTexts = descendants(bar).flatMap { texts(it) }
        assertEquals("the composer row shows only the draft, no hint", listOf(longDraft), draftTexts)
    }
}

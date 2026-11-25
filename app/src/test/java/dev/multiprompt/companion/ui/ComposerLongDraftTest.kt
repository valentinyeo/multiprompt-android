package dev.multiprompt.companion.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ViewRootForTest
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
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.text.TextRange
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
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerLongDraftTest {
    private val scheduler = TestCoroutineScheduler()
    @get:Rule val compose = createComposeRule(effectContext = UnconfinedTestDispatcher(scheduler))
    private lateinit var f: SessionActivityFixture
    private lateinit var connection: SessionReaderConnection
    private val showingReader = mutableStateOf(true)

    private val skill = "/review-native"
    private val longDraft = "Checking when it resets. We need instructions for this, " +
        "this never used to happen. We had a very good setup for this in place, " +
        "but since using the new stack this has broken down. $skill keeps the draft " +
        "long enough to scroll inside the message box."

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
        TypingProbe.compositions = null
        connection.close()
        f.close()
    }

    private fun render() {
        compose.setContent {
            if (showingReader.value) ReaderScreen(
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

    private fun settleComposer() {
        repeat(8) {
            compose.waitForIdle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
        }
    }

    private fun skillPillBandPixels(bitmap: Bitmap, textRight: Int): Pair<Int, Int> {
        val outline = kitPalette(true).outline.toArgb()
        val xMax = textRight.coerceIn(1, bitmap.width)
        var pillRows = 0
        var pillPixels = 0
        for (y in 0 until bitmap.height) {
            var run = 0
            var best = 0
            var bestStart = 0
            for (x in 0 until xMax) {
                if (near(bitmap.getPixel(x, y), outline, 20)) {
                    run++
                    if (run > best) {
                        best = run
                        bestStart = x - run + 1
                    }
                } else {
                    run = 0
                }
            }
            if (best >= 24) {
                pillRows++
                pillPixels += best
                println("COMPOSER_ROW y=$y pill=$best pillX=$bestStart..${bestStart + best - 1}")
            }
        }
        return pillRows to pillPixels
    }

    private fun save(node: SemanticsNodeInteraction, name: String): Bitmap {
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
        val output = File("build/reports/igsh-844/igsh-2957-$name.png")
        checkNotNull(output.parentFile).mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return bitmap
    }

    @Test fun aLongDraftKeepsEveryComposerControlOnOneRowAndShowsNoHint() {
        render()
        compose.onNode(hasSetTextAction()).performTextInput(longDraft)
        settleComposer()

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
        assertTrue(
            "a long draft scrolls inside the composer field: lines=${layout.lineCount} " +
                "maxLines=$ReaderComposerMaxLines exceeded=${layout.multiParagraph.didExceedMaxLines}",
            layout.scrollsInsideComposer(),
        )
        val first = layout.getBoundingBox(tokenStart)
        val last = layout.getBoundingBox(longDraft.length - 1)
        println("COMPOSER_TOKEN layout_start=$first layout_end=$last lines=${layout.lineCount}")

        val bitmap = save(compose.onNode(hasSetTextAction()), "composer-field")
        val textRight = (micBounds.left - fieldBounds.left).toInt() - 4
        val (pillRows, pillPixels) = skillPillBandPixels(bitmap, textRight)
        println("COMPOSER_PILL rows=$pillRows pixels=$pillPixels textRight=$textRight scrolls=${layout.scrollsInsideComposer()}")

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
            "a scrolled draft draws no skill pill over the text: rows=$pillRows pixels=$pillPixels",
            pillPixels == 0,
        )
        assertTrue(
            "the composer row reaches the bottom of the screen: row=$barBounds root=$rootBounds",
            barBounds.bottom >= rootBounds.bottom - (2 * ReaderControlSpacing.value).toFloat(),
        )
        assertTrue(
            "the draft grew the field instead of the row: field=$fieldBounds",
            fieldBounds.height > (ReaderControlSize * 2).value,
        )
        val barTexts = descendants(bar).flatMap { texts(it) }
        val editable = descendants(bar).mapNotNull { it.config.getOrNull(SemanticsProperties.EditableText)?.text }
        println("COMPOSER_TEXTS bar=$barTexts editable=$editable")
        assertTrue("the composer row shows no hint beside the draft: $barTexts", barTexts.isEmpty())
        assertTrue("the composer field holds the whole draft: $editable", editable.any { it == longDraft })
    }

    @Test fun leavingAfterAMiddleEditRestoresTheWholeDraft() {
        render()
        val field = compose.onNode(hasSetTextAction())
        field.performTextInput("abcdefghij")
        field.performTextInputSelection(TextRange(5))
        field.performTextInput("MID")
        compose.runOnIdle { showingReader.value = false }
        compose.waitForIdle()
        compose.runOnIdle { showingReader.value = true }
        field.assertTextEquals("abcdeMIDfghij")
    }

    @Test fun typingAndMiddleEditsDoNotRecomposeTheReaderOrMessageList() {
        val counts = mutableMapOf<String, Int>()
        TypingProbe.compositions = { name -> counts[name] = (counts[name] ?: 0) + 1 }
        render()
        compose.waitForIdle()
        assertTrue("the probe must see the real reader", (counts["ReaderScreen"] ?: 0) > 0)
        assertTrue("the probe must see the real message list", (counts["ChatHistoryList"] ?: 0) > 0)
        counts.clear()

        val field = compose.onNode(hasSetTextAction())
        "abcdefghij".forEach { field.performTextInput(it.toString()) }
        field.performTextInputSelection(TextRange(5))
        "MID".forEach { field.performTextInput(it.toString()) }
        field.assertTextEquals("abcdeMIDfghij")
        compose.waitForIdle()

        assertEquals("typing must stay inside the composer", 0, counts["ReaderScreen"] ?: 0)
        assertEquals("typing must not invalidate chat projection", 0, counts["ChatHistoryView"] ?: 0)
        assertEquals("typing must not recompose messages", 0, counts["ChatHistoryList"] ?: 0)
        assertTrue("the composer must actually update", (counts["Composer"] ?: 0) >= 13)
    }

    @Test fun aDraftThatFitsStillDrawsTheSkillPill() {
        val fits = "keep $skill in view"
        render()
        compose.onNode(hasSetTextAction()).performTextInput(fits)
        compose.waitForIdle()

        val field = node(compose.onNode(hasSetTextAction()))
        val layout = textLayout(field)
        assertTrue(
            "the draft must fit to keep its pill: lines=${layout.lineCount}",
            !layout.scrollsInsideComposer(),
        )

        val mic = node(compose.onNodeWithContentDescription("Start dictation"))
        val bitmap = save(compose.onNode(hasSetTextAction()), "composer-pill")
        val textRight = (mic.boundsInRoot.left - field.boundsInRoot.left).toInt() - 4
        val (pillRows, pillPixels) = skillPillBandPixels(bitmap, textRight)
        println("COMPOSER_FITTING_PILL rows=$pillRows pixels=$pillPixels")
        assertTrue("a draft that fits keeps its skill pill: rows=$pillRows pixels=$pillPixels", pillRows >= 2 && pillPixels >= 100)
    }
}

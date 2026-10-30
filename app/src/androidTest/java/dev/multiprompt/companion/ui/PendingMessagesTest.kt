package dev.multiprompt.companion.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import org.json.JSONObject
import org.junit.Assert.assertTrue
import java.io.File
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dev.multiprompt.companion.MultipromptApplication
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.reader.ChatHistory
import dev.multiprompt.companion.reader.ChatHistoryActorTest
import dev.multiprompt.companion.reader.ChatHistoryConnection
import dev.multiprompt.companion.reader.ChatHistoryProtocol
import dev.multiprompt.companion.reader.ChatHistoryState
import dev.multiprompt.companion.reader.MessageJournal
import dev.multiprompt.companion.reader.MessageProtocol
import dev.multiprompt.companion.reader.MessageReplica
import dev.multiprompt.companion.reader.SessionReaderConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test

class PendingMessagesTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var connection: SessionReaderConnection
    private lateinit var history: MutableStateFlow<ChatHistoryState>
    private val identity = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "pending-proof", "/pending")
    private val id = "1234567890123456"
    private fun row(state: MessageProtocol.State) = MessageProtocol.Row(id, "claude", "proof", "Synthetic prompt", 1,
        "attempt", state, null, null, 1, 1.0)

    @Suppress("UNCHECKED_CAST")
    private fun harness() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MultipromptApplication
        val host = HostProfile("pending-proof", "Proof", "", port = 0, username = "proof")
        val stream = ChatHistoryConnection(app.sshRepository, host, identity, app.chatHistoryScript, app.chatHistoryCache)
        connection = SessionReaderConnection(app.sshRepository, host, identity.tmux, identity.agent, app.chatHistoryScript, app.chatHistoryCache)
        connection.javaClass.getDeclaredField("historyIdentity").apply { isAccessible = true }.set(connection, identity)
        connection.javaClass.getDeclaredField("historyConnection").apply { isAccessible = true }.set(connection, stream)
        history = stream.javaClass.getDeclaredField("_state").apply { isAccessible = true }.get(stream) as MutableStateFlow<ChatHistoryState>
        history.value = ChatHistoryState(ChatHistory(sid = "proof", inode = 1, offset = 100), loaded = true,
            replica = MessageReplica(1, 1, mapOf(id to row(MessageProtocol.State.QUEUED))))
        compose.setContent {
            MultipromptTheme(readerStyle = true) {
                val reader by connection.state.collectAsState()
                ChatHistoryView(connection, reader, identity.agent, identity.cwd, 1f, false,
                    ReplySwipeGate(), { _, _ -> }, {}, rememberLazyListState(), 0, "Claude", null, {})
            }
        }
    }

    @Test fun queuedAndAcceptedRemainHostCopies() {
        harness()
        compose.onNodeWithText("Queued").assertIsDisplayed()
        compose.runOnIdle {
            history.value = history.value.copy(replica = MessageReplica(1, 2, mapOf(id to row(MessageProtocol.State.ACCEPTED))))
        }
        compose.onNodeWithText("Accepted").assertIsDisplayed()
        compose.onNodeWithText("Synthetic prompt").assertIsDisplayed()
        compose.onNodeWithText("Send again").assertDoesNotExist()
        compose.onNodeWithText("Remove").assertDoesNotExist()
    }

    @Test fun cancelledRetryShowsRecoveryWithoutBody() {
        harness()
        compose.runOnIdle {
            val entry = MessageJournal.Entry("retry123456789012", "account", MessageProtocol.Scope("claude", "proof", "pending-proof"),
                "retry", id, null, 1, "attempt", true)
            history.value = history.value.copy(replica = MessageReplica(1, 2, mapOf(id to row(MessageProtocol.State.CANCELLED))), unresolved = listOf(entry))
        }
        compose.onNodeWithText("Synthetic prompt").assertDoesNotExist()
        compose.onNodeWithText("Send again").performClick()
        compose.onNodeWithText("Your agent may already have this message. Sending again may duplicate it. Send again?").assertIsDisplayed()
    }

    @Test fun cancelledRetryRecoveryUsesVisibleControlAndProductionOwner() {
        val f = ChatHistoryActorTest().Fixture("claude")
        f.row = f.row("failed")
        f.dropRetryAfterAdmission = true
        val first = f.connection()
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MultipromptApplication
        var owner: ChatHistoryConnection? = null
        try {
            runBlocking {
                first.select(true); first.start()
                val ready = f.ready(first)
                val failed = requireNotNull(ready.replica?.messages?.get(id))
                assertTrue(withTimeout(10_000) { first.retryFailed(failed, true) } is dev.multiprompt.companion.reader.MessageActionResult.Unknown)
            }
            val retryId = requireNotNull(f.admittedRetryId)
            first.close()
            runBlocking {
                f.exchange(MessageProtocol.request(700, f.wire, "message_reset", JSONObject().put("reset_id", "otherreset123456789")))
            }
            val current = f.connection()
            owner = current
            runBlocking {
                current.select(true); current.start(); f.ready(current)
                assertTrue(withTimeout(10_000) { current.resetConversation().await() } == dev.multiprompt.companion.reader.MessageActionResult.BlockedRecovery)
            }
            connection = SessionReaderConnection(app.sshRepository, f.host, f.identity.tmux, f.identity.agent, app.chatHistoryScript, app.chatHistoryCache)
            connection.javaClass.getDeclaredField("historyIdentity").apply { isAccessible = true }.set(connection, f.identity)
            connection.javaClass.getDeclaredField("historyConnection").apply { isAccessible = true }.set(connection, current)
            compose.setContent {
                MultipromptTheme(readerStyle = true) {
                    val reader by connection.state.collectAsState()
                    ChatHistoryView(connection, reader, f.identity.agent, f.identity.cwd, 1f, false,
                        ReplySwipeGate(), { _, _ -> }, {}, rememberLazyListState(), 0, "Claude", null, {})
                }
            }
            compose.onNodeWithText("hello").assertDoesNotExist()
            compose.onNodeWithText("Send again").performClick()
            compose.onNodeWithText("Your agent may already have this message. Sending again may duplicate it. Send again?").assertIsDisplayed()
            compose.onAllNodesWithText("Send again").onLast().performClick()
            compose.waitUntil(10_000) { f.journal.entries().isEmpty() }
            assertTrue(f.lastRetryId == retryId && f.retryCalls == 2 && f.retryAdmissions == 1)
            compose.onNodeWithText("hello").assertDoesNotExist()
            runBlocking {
                assertTrue(withTimeout(10_000) { current.resetConversation().await() } == dev.multiprompt.companion.reader.MessageActionResult.Applied)
            }
        } finally {
            first.close()
            owner?.close()
        }
    }

    @Test fun claudeUnknownRemoveUsesVisibleProductionOwner() = unknownRemoveUsesVisibleProductionOwner("claude")
    @Test fun codexUnknownRemoveUsesVisibleProductionOwner() = unknownRemoveUsesVisibleProductionOwner("codex")

    private fun unknownRemoveUsesVisibleProductionOwner(provider: String) {
        val f = ChatHistoryActorTest().Fixture(provider)
        f.dropSubmit = true
        val owner = f.connection()
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MultipromptApplication
        try {
            val original = runBlocking {
                owner.select(true); owner.start(); f.ready(owner)
                val id = (owner.submit("Unknown prompt") as dev.multiprompt.companion.reader.PromptAdmission.Recorded).messageId
                withTimeout(10_000) { owner.state.first { it.retrying } }
                f.dropSubmit = false
                withTimeout(15_000) { owner.state.first { !it.retrying && it.unresolved.any { entry -> entry.id == id } } }
                id
            }
            f.dismissPause = CompletableDeferred()
            connection = SessionReaderConnection(app.sshRepository, f.host, f.identity.tmux, f.identity.agent, app.chatHistoryScript, app.chatHistoryCache)
            connection.javaClass.getDeclaredField("historyIdentity").apply { isAccessible = true }.set(connection, f.identity)
            connection.javaClass.getDeclaredField("historyConnection").apply { isAccessible = true }.set(connection, owner)
            compose.setContent {
                MultipromptTheme(readerStyle = true) {
                    val reader by connection.state.collectAsState()
                    ChatHistoryView(connection, reader, f.identity.agent, f.identity.cwd, 1f, false,
                        ReplySwipeGate(), { _, _ -> }, {}, rememberLazyListState(), 0, provider, null, {})
                }
            }
            compose.onNodeWithText("Unknown prompt").assertIsDisplayed()
            compose.onNodeWithText("Remove").performClick()
            runBlocking { withTimeout(10_000) { f.dismissStarted.await() } }
            compose.waitUntil(10_000) { owner.state.value.unresolved.any { it.operation == "dismiss" && it.messageId == original } }
            compose.onNodeWithText("Unknown prompt").assertIsDisplayed()
            compose.onNodeWithText("Send again").assertDoesNotExist()
            assertTrue(f.journal.entries().any { it.operation == "submit" && it.id == original })
            assertTrue(f.lastDismiss!!.getString("text") == "Unknown prompt")
            f.dismissPause!!.complete(Unit)
            compose.waitUntil(10_000) {
                owner.state.value.replica?.messages?.get(original)?.state == MessageProtocol.State.CANCELLED && owner.state.value.unresolved.isEmpty()
            }
            compose.onNodeWithText("Unknown prompt").assertDoesNotExist()
            compose.onNodeWithText("Remove").assertDoesNotExist()
            assertTrue(f.submitCalls == 1 && f.dismissCalls == 1)
            runBlocking { assertTrue(withTimeout(10_000) { owner.resetConversation().await() } == dev.multiprompt.companion.reader.MessageActionResult.Applied) }
        } finally { f.dismissPause?.complete(Unit); owner.close() }
    }

    @Test fun claudeHandedOffRemoveKeepsVisibleWork() = handedOffRemoveKeepsVisibleWork("claude")
    @Test fun codexHandedOffRemoveKeepsVisibleWork() = handedOffRemoveKeepsVisibleWork("codex")

    private fun handedOffRemoveKeepsVisibleWork(provider: String) {
        val f = ChatHistoryActorTest().Fixture(provider)
        val submit = f.journal.create(f.account, f.wire, "submit", text = "Accepted prompt", epoch = 1)
        f.journal.markIssued(submit.id)
        f.row = f.row("accepted").put("id", submit.id).put("text", submit.text)
        f.hideSnapshotRow = true
        f.dismissPause = CompletableDeferred()
        val owner = f.connection()
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MultipromptApplication
        try {
            runBlocking { owner.select(true); owner.start(); f.ready(owner) }
            connection = SessionReaderConnection(app.sshRepository, f.host, f.identity.tmux, f.identity.agent, app.chatHistoryScript, app.chatHistoryCache)
            connection.javaClass.getDeclaredField("historyIdentity").apply { isAccessible = true }.set(connection, f.identity)
            connection.javaClass.getDeclaredField("historyConnection").apply { isAccessible = true }.set(connection, owner)
            compose.setContent {
                MultipromptTheme(readerStyle = true) {
                    val reader by connection.state.collectAsState()
                    ChatHistoryView(connection, reader, f.identity.agent, f.identity.cwd, 1f, false,
                        ReplySwipeGate(), { _, _ -> }, {}, rememberLazyListState(), 0, provider, null, {})
                }
            }
            compose.onNodeWithText("Remove").performClick()
            runBlocking { withTimeout(10_000) { f.dismissStarted.await() } }
            compose.waitUntil(10_000) { owner.state.value.unresolved.any { it.operation == "dismiss" } }
            compose.onNodeWithText("Accepted prompt").assertIsDisplayed()
            compose.onNodeWithText("Send again").assertDoesNotExist()
            f.hideSnapshotRow = false
            f.dismissPause!!.complete(Unit)
            compose.waitUntil(10_000) { owner.state.value.replica?.messages?.get(submit.id)?.state == MessageProtocol.State.ACCEPTED && owner.state.value.unresolved.isEmpty() }
            compose.onNodeWithText("Accepted prompt").assertIsDisplayed()
            compose.onNodeWithText("Accepted").assertIsDisplayed()
            compose.onNodeWithText("Send again").assertDoesNotExist()
            compose.onNodeWithText("Remove").assertDoesNotExist()
            runBlocking { assertTrue(withTimeout(10_000) { owner.resetConversation().await() } == dev.multiprompt.companion.reader.MessageActionResult.Applied) }
        } finally { f.dismissPause?.complete(Unit); owner.close() }
    }

    @Test fun claudeAliasRemoveUsesVisibleOwner() = aliasRemoveUsesVisibleOwner("claude")
    @Test fun codexAliasRemoveUsesVisibleOwner() = aliasRemoveUsesVisibleOwner("codex")

    private fun aliasRemoveUsesVisibleOwner(provider: String) {
        val f = ChatHistoryActorTest().Fixture(provider)
        val submit = f.journal.create(f.account, f.wire.copy(tmux = "old-alias"), "submit", text = "Alias prompt", epoch = 1)
        f.journal.markIssued(submit.id)
        val dismiss = f.journal.create(f.account, f.wire, "dismiss", submit.id, submit.text, 1)
        f.journal.markIssued(dismiss.id)
        f.dismissPause = CompletableDeferred()
        val owner = f.connection()
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MultipromptApplication
        try {
            runBlocking { owner.select(true); owner.start(); f.ready(owner) }
            connection = SessionReaderConnection(app.sshRepository, f.host, f.identity.tmux, f.identity.agent, app.chatHistoryScript, app.chatHistoryCache)
            connection.javaClass.getDeclaredField("historyIdentity").apply { isAccessible = true }.set(connection, f.identity)
            connection.javaClass.getDeclaredField("historyConnection").apply { isAccessible = true }.set(connection, owner)
            compose.setContent {
                MultipromptTheme(readerStyle = true) {
                    val reader by connection.state.collectAsState()
                    ChatHistoryView(connection, reader, f.identity.agent, f.identity.cwd, 1f, false,
                        ReplySwipeGate(), { _, _ -> }, {}, rememberLazyListState(), 0, provider, null, {})
                }
            }
            compose.onNodeWithText("Alias prompt").assertIsDisplayed()
            compose.onNodeWithText("Send again").assertDoesNotExist()
            compose.onNodeWithText("Remove").performClick()
            runBlocking { withTimeout(10_000) { f.dismissStarted.await() } }
            assertTrue(f.lastDismiss!!.getString("tmux") == "chat")
            assertTrue(f.lastDismiss!!.getString("message_id") == submit.id)
            assertTrue(f.lastDismiss!!.getString("text") == "Alias prompt")
            assertTrue(f.journal.entries().count { it.operation == "dismiss" } == 1)
            f.dismissPause!!.complete(Unit)
            compose.waitUntil(10_000) { owner.state.value.unresolved.isEmpty() && owner.state.value.replica?.messages?.get(submit.id)?.state == MessageProtocol.State.CANCELLED }
            compose.onNodeWithText("Alias prompt").assertDoesNotExist()
            compose.onNodeWithText("Remove").assertDoesNotExist()
            assertTrue(f.submitCalls == 0 && f.retryCalls == 0)
            runBlocking { assertTrue(withTimeout(10_000) { owner.resetConversation().await() } == dev.multiprompt.companion.reader.MessageActionResult.Applied) }
        } finally { f.dismissPause?.complete(Unit); owner.close() }
    }

    @Test fun claudeAliasQueuedRemoveUsesVisibleOwner() = aliasQueuedRemoveUsesVisibleOwner("claude")
    @Test fun codexAliasQueuedRemoveUsesVisibleOwner() = aliasQueuedRemoveUsesVisibleOwner("codex")

    private fun aliasQueuedRemoveUsesVisibleOwner(provider: String) {
        val f = ChatHistoryActorTest().Fixture(provider)
        val submit = f.journal.create(f.account, f.wire.copy(tmux = "old-alias"), "submit", text = "Queued alias", epoch = 1)
        f.journal.markIssued(submit.id)
        f.row = f.row("queued").put("id", submit.id).put("text", submit.text)
        val owner = f.connection()
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MultipromptApplication
        try {
            runBlocking { owner.select(true); owner.start(); f.ready(owner) }
            assertTrue(f.journal.entries().isEmpty())
            connection = SessionReaderConnection(app.sshRepository, f.host, f.identity.tmux, f.identity.agent, app.chatHistoryScript, app.chatHistoryCache)
            connection.javaClass.getDeclaredField("historyIdentity").apply { isAccessible = true }.set(connection, f.identity)
            connection.javaClass.getDeclaredField("historyConnection").apply { isAccessible = true }.set(connection, owner)
            compose.setContent {
                MultipromptTheme(readerStyle = true) {
                    val reader by connection.state.collectAsState()
                    ChatHistoryView(connection, reader, f.identity.agent, f.identity.cwd, 1f, false,
                        ReplySwipeGate(), { _, _ -> }, {}, rememberLazyListState(), 0, provider, null, {})
                }
            }
            compose.onNodeWithText("Queued alias").assertIsDisplayed()
            compose.onNodeWithText("Remove").performClick()
            compose.waitUntil(10_000) { owner.state.value.replica?.messages?.get(submit.id)?.state == MessageProtocol.State.CANCELLED && owner.state.value.unresolved.isEmpty() }
            compose.onNodeWithText("Queued alias").assertDoesNotExist()
            assertTrue(f.lastDismiss!!.getString("message_id") == submit.id && f.lastDismiss!!.getString("tmux") == "chat")
            assertTrue(f.submitCalls == 0 && f.retryCalls == 0)
            runBlocking { assertTrue(withTimeout(10_000) { owner.resetConversation().await() } == dev.multiprompt.companion.reader.MessageActionResult.Applied) }
        } finally { owner.close() }
    }

    @Test fun claudeOldKnownRemoveUsesVisibleOwner() = oldKnownRemoveUsesVisibleOwner("claude")
    @Test fun codexOldKnownRemoveUsesVisibleOwner() = oldKnownRemoveUsesVisibleOwner("codex")

    private fun oldKnownRemoveUsesVisibleOwner(provider: String) {
        val f = ChatHistoryActorTest().Fixture(provider)
        val dismiss = f.journal.create(f.account, f.wire.copy(tmux = "old-alias"), "dismiss", id, epoch = 0, attempt = "old")
        f.journal.markIssued(dismiss.id)
        f.row = f.row("accepted", epoch = 0)
        val owner = f.connection()
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MultipromptApplication
        try {
            runBlocking { owner.select(true); owner.start(); f.ready(owner) }
            connection = SessionReaderConnection(app.sshRepository, f.host, f.identity.tmux, f.identity.agent, app.chatHistoryScript, app.chatHistoryCache)
            connection.javaClass.getDeclaredField("historyIdentity").apply { isAccessible = true }.set(connection, f.identity)
            connection.javaClass.getDeclaredField("historyConnection").apply { isAccessible = true }.set(connection, owner)
            compose.setContent {
                MultipromptTheme(readerStyle = true) {
                    val reader by connection.state.collectAsState()
                    ChatHistoryView(connection, reader, f.identity.agent, f.identity.cwd, 1f, false,
                        ReplySwipeGate(), { _, _ -> }, {}, rememberLazyListState(), 0, provider, null, {})
                }
            }
            compose.onNodeWithText("hello").assertIsDisplayed()
            compose.onNodeWithText("Remove").performClick()
            compose.waitUntil(10_000) { owner.state.value.unresolved.isEmpty() && f.dismissCalls == 1 }
            compose.onNodeWithText("hello").assertIsDisplayed()
            assertTrue(f.row!!.getString("state") == "accepted" && !f.lastDismiss!!.has("text"))
            assertTrue(f.submitCalls == 0 && f.retryCalls == 0)
            runBlocking { assertTrue(withTimeout(10_000) { owner.resetConversation().await() } == dev.multiprompt.companion.reader.MessageActionResult.Applied) }
        } finally { owner.close() }
    }

    @Test fun claudeVisibleRemoveRejectsSnapshotConflict() = visibleRemoveRejectsSnapshotConflict("claude")
    @Test fun codexVisibleRemoveRejectsSnapshotConflict() = visibleRemoveRejectsSnapshotConflict("codex")

    private fun visibleRemoveRejectsSnapshotConflict(provider: String) {
        val f = ChatHistoryActorTest().Fixture(provider)
        val submit = f.journal.create(f.account, f.wire.copy(tmux = "old-alias"), "submit", text = "Original prompt", epoch = 1)
        f.journal.markIssued(submit.id)
        val owner = f.connection()
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MultipromptApplication
        try {
            runBlocking { owner.select(true); owner.start(); f.ready(owner) }
            f.row = f.row("queued").put("id", submit.id).put("text", "Conflicting prompt")
            f.paged = true
            f.targetOnLaterPage = true
            f.pauseSecondSnapshot = CompletableDeferred()
            owner.resync()
            runBlocking { withTimeout(10_000) { f.secondSnapshotStarted.await() } }
            f.pauseSnapshot = CompletableDeferred()
            connection = SessionReaderConnection(app.sshRepository, f.host, f.identity.tmux, f.identity.agent, app.chatHistoryScript, app.chatHistoryCache)
            connection.javaClass.getDeclaredField("historyIdentity").apply { isAccessible = true }.set(connection, f.identity)
            connection.javaClass.getDeclaredField("historyConnection").apply { isAccessible = true }.set(connection, owner)
            compose.setContent {
                MultipromptTheme(readerStyle = true) {
                    val reader by connection.state.collectAsState()
                    ChatHistoryView(connection, reader, f.identity.agent, f.identity.cwd, 1f, false,
                        ReplySwipeGate(), { _, _ -> }, {}, rememberLazyListState(), 0, provider, null, {})
                }
            }
            compose.onNodeWithText("Original prompt").assertIsDisplayed()
            compose.onNodeWithText("Remove").performClick()
            compose.runOnIdle { }
            f.pauseSecondSnapshot!!.complete(Unit)
            runBlocking { withTimeout(10_000) { f.thirdSnapshotStarted.await() } }
            compose.onNodeWithText("Original prompt").assertIsDisplayed()
            compose.onNodeWithText("Conflicting prompt").assertDoesNotExist()
            assertTrue(f.row!!.getString("state") == "queued")
            assertTrue(f.dismissCalls == 0 && f.submitCalls == 0 && f.retryCalls == 0)
            assertTrue(f.journal.entries().single().id == submit.id)
        } finally { f.pauseSecondSnapshot?.complete(Unit); f.pauseSnapshot?.complete(Unit); owner.close() }
    }

    private fun contrast(bitmap: Bitmap): Double {
        val values = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(values, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val light = values.map { Color.red(it) * 0.2126 + Color.green(it) * 0.7152 + Color.blue(it) * 0.0722 }
        return light.max() - light.min()
    }

    private fun pixels() = compose.onNodeWithText("Synthetic prompt").captureToImage().asAndroidBitmap()

    private fun save(name: String, bitmap: Bitmap) {
        val output = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "pending-messages")
        output.mkdirs()
        File(output, "$name-text.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        File(output, "$name.png").outputStream().use {
            assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun screenshot(name: String) {
        val output = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "pending-messages")
        output.mkdirs()
        File(output, "$name.png").outputStream().use {
            assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test fun hostAcceptanceRestoresBrightnessBeforeTranscript() {
        harness()
        compose.runOnIdle {
            history.value = history.value.copy(replica = MessageReplica(1, 1, mapOf(id to row(MessageProtocol.State.ACCEPTED))))
        }
        val normal = pixels()
        val normalContrast = contrast(normal)
        save("normal", normal)
        compose.runOnIdle {
            history.value = history.value.copy(replica = MessageReplica(1, 1, mapOf(id to row(MessageProtocol.State.QUEUED))))
        }
        val pending = pixels()
        val pendingContrast = contrast(pending)
        save("pending", pending)
        screenshot("before-pending")
        compose.runOnIdle {
            val other = row(MessageProtocol.State.ACCEPTED).copy(id = "other-message-123456", text = "Other work")
            history.value = history.value.copy(replica = MessageReplica(1, 2, mapOf(id to row(MessageProtocol.State.QUEUED), other.id to other)))
        }
        assertTrue(contrast(pixels()) == pendingContrast)
        val start = SystemClock.elapsedRealtime()
        compose.runOnIdle {
            history.value = history.value.copy(replica = MessageReplica(1, 3, mapOf(id to row(MessageProtocol.State.ACCEPTED))))
        }
        val accepted = pixels()
        val elapsed = SystemClock.elapsedRealtime() - start
        val acceptedContrast = contrast(accepted)
        assertTrue("Accepted text must match normal brightness", kotlin.math.abs(acceptedContrast - normalContrast) < 2)
        assertTrue("Accepted text must be brighter", pendingContrast < normalContrast * 0.75 && pendingContrast > normalContrast * 0.25)
        assertTrue("Acceptance must paint within one second", elapsed <= 1000)
        assertTrue(history.value.history.messages.none { it.role == "user" })
        save("accepted", accepted)
        screenshot("after-accepted")
        val output = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "pending-messages")
        File(output, "result.json").writeText(JSONObject().put("acceptedAfterMs", elapsed).put("pendingContrast", pendingContrast)
            .put("normalContrast", normalContrast).put("acceptedContrast", acceptedContrast).put("status", "passed").toString())
    }

    @Test fun hostAcceptanceWithTranscriptRestoresBrightness() {
        harness()
        compose.runOnIdle {
            history.value = history.value.copy(replica = MessageReplica(1, 1, mapOf(id to row(MessageProtocol.State.ACCEPTED))))
        }
        val normal = contrast(pixels())
        compose.runOnIdle {
            history.value = history.value.copy(replica = MessageReplica(1, 2, mapOf(id to row(MessageProtocol.State.QUEUED))))
        }
        val start = SystemClock.elapsedRealtime()
        compose.runOnIdle {
            val source = history.value.history.copy(rows = listOf(ChatHistoryProtocol.Row("user", "Transcript arrived", "native", "", 1, 2)))
            history.value = history.value.copy(history = source, replica = MessageReplica(1, 3, mapOf(id to row(MessageProtocol.State.ACCEPTED))))
        }
        val bitmap = pixels()
        val elapsed = SystemClock.elapsedRealtime() - start
        assertTrue(kotlin.math.abs(normal - contrast(bitmap)) < 2)
        assertTrue(elapsed <= 1000)
        save("transcript", bitmap)
        screenshot("after-transcript")
        val output = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "pending-messages")
        File(output, "transcript-result.json").writeText(JSONObject().put("acceptedAfterMs", elapsed).put("status", "passed").toString())
    }

    @After fun close() { if (::connection.isInitialized) connection.close() }
}

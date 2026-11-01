package dev.multiprompt.companion.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.reader.ChatReactions
import dev.multiprompt.companion.reader.ReaderStatus
import dev.multiprompt.companion.ssh.TmuxText
import dev.multiprompt.companion.reader.PendingQuestion
import dev.multiprompt.companion.reader.ChatHistoryMessage
import dev.multiprompt.companion.reader.ReaderState
import dev.multiprompt.companion.reader.SessionReaderConnection
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ReplyJump(val message: ChatHistoryMessage, val reply: ReplyQuote.Reply)

@Composable
internal fun ChatHistoryView(
    connection: SessionReaderConnection,
    reader: ReaderState,
    agent: AgentKind,
    workingDirectory: String,
    fontScale: Float,
    sunlight: Boolean,
    replyGate: ReplySwipeGate,
    onReply: (String, Boolean) -> Unit,
    onSessionUsed: () -> Unit,
    listState: LazyListState,
    followRequest: Int,
    sessionName: String,
    unreadMessageKey: String?,
    onMarkUnread: (String) -> Unit,
    onReaction: ((ChatHistoryMessage, ChatReactions.Emoji) -> Unit)? = null,
    onSpeakableReplies: (List<dev.multiprompt.companion.reader.ReadAloud.Spoken>) -> Unit = {},
    onChatReady: (Boolean) -> Unit = {},
    focusKey: String? = null,
    bottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val history = remember(connection, agent, workingDirectory) { connection.chatHistory(agent, workingDirectory) }
    val state by history.state.collectAsState()
    val managed = agent == AgentKind.CLAUDE || agent == AgentKind.CODEX || agent == AgentKind.PI
    val messages = if (managed) state.history.managedMessages(state.replica, state.unresolved) else state.history.displayMessages(reader.sentRecord)
    val speakable = remember(messages) { dev.multiprompt.companion.reader.ReadAloud.replies(messages) }
    LaunchedEffect(speakable) { onSpeakableReplies(speakable) }
    LaunchedEffect(state.loaded, state.history.sid) { onChatReady(state.loaded && state.history.sid.isNotEmpty()) }
    val actionScope = rememberCoroutineScope()
    val context = LocalContext.current
    var retryRow by remember { mutableStateOf<Pair<dev.multiprompt.companion.reader.MessageProtocol.Row, Long>?>(null) }
    var hiddenRetry by remember { mutableStateOf<Pair<String, Long>?>(null) }
    suspend fun showResult(result: dev.multiprompt.companion.reader.MessageActionResult) {
        val text = when (result) {
            dev.multiprompt.companion.reader.MessageActionResult.Applied -> "Request confirmed"
            dev.multiprompt.companion.reader.MessageActionResult.Unknown -> "Confirmation unknown"
            dev.multiprompt.companion.reader.MessageActionResult.BlockedRecovery -> "Resolve unknown messages first"
            is dev.multiprompt.companion.reader.MessageActionResult.Rejected -> result.reason
        }
        withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    retryRow?.let { (row, token) ->
        KitSheet(onDismiss = { retryRow = null }) {
            Text("Send again?")
            Text("Your agent may already have this message. Sending again may duplicate it. Send again?")
            KitPrimaryButton("Send again", onClick = { retryRow = null; actionScope.launch { showResult(history.retryFailed(row, true, token)) } })
            KitSecondaryButton("Cancel", onClick = { retryRow = null })
        }
    }
    hiddenRetry?.let { (id, token) ->
        KitSheet(onDismiss = { hiddenRetry = null }) {
            Text("Send again?")
            Text("Your agent may already have this message. Sending again may duplicate it. Send again?")
            KitPrimaryButton("Send again", onClick = { hiddenRetry = null; actionScope.launch { showResult(history.replayUnknown(id, token)) } })
            KitSecondaryButton("Cancel", onClick = { hiddenRetry = null })
        }
    }
    SideEffect {
        history.awaitingMessages(messages.any { it.pending })
        if (!managed) history.deliveryIds(reader.sentRecord.map { it.messageId }.filter { it.isNotEmpty() })
    }
    DisposableEffect(history) { onDispose { history.awaitingMessages(false) } }
    val questions = state.history.pendingQuestions
    Column(modifier.fillMaxSize()) {
        state.messageError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
        if (managed && state.unresolved.any { it.operation == "reset" }) {
            KitSecondaryButton("Check reset", onClick = { val token = history.actionToken(); actionScope.launch { showResult(history.recoverReset(token)) } })
        }
        state.unresolved.filter { entry -> managed && entry.operation == "retry" &&
            state.replica?.messages?.get(entry.messageId)?.state == dev.multiprompt.companion.reader.MessageProtocol.State.CANCELLED
        }.forEach { entry ->
            KitSecondaryButton("Send again", onClick = { hiddenRetry = entry.id to history.actionToken() })
        }
        state.unresolved.filter { entry -> managed && entry.operation == "dismiss" && entry.text != null &&
            state.unresolved.none { it.operation == "submit" && it.sameChat(entry.account, entry.scope) && it.messageId == entry.messageId && it.text == entry.text } &&
            (state.replica?.messages?.get(entry.messageId) == null ||
                state.replica?.messages?.get(entry.messageId)?.state == dev.multiprompt.companion.reader.MessageProtocol.State.CANCELLED)
        }.forEach { entry ->
            KitSecondaryButton("Remove", onClick = { val token = history.actionToken(); actionScope.launch { showResult(history.removeUnknown(entry.id, token)) } })
        }
        if (state.retrying) {
            Text(
                "Disconnected · retrying",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(8.dp),
            )
        }
        reader.modelPickerOptions.forEach { option ->
            OutlinedButton(
                onClick = { if (connection.selectModelPickerOption(option.index)) onSessionUsed() },
                enabled = !reader.sending,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(option.label + if (option.current) " · current" else "") }
        }
        ChatHistoryList(
            messages = messages,
            focusKey = focusKey,
            loaded = state.loaded,
            hasOlder = !state.loaded || state.history.start > 0 || state.history.more,
            agent = agent,
            fontScale = fontScale,
            sunlight = sunlight,
            replyGate = replyGate,
            onReply = onReply,
            questions = questions,
            questionError = reader.actionError,
            onQuestionChoice = if (!reader.sending && reader.status == ReaderStatus.Live) { question, choices, other ->
                connection.answerQuestion(question, choices, other).also { if (it) onSessionUsed() }
            } else null,
            questionScreen = reader.screenOutput,
            sessionName = sessionName,
            unreadMessageKey = unreadMessageKey,
            onMarkUnread = onMarkUnread,
            onReaction = onReaction,
            onCancelPending = if (!managed && !reader.sending && reader.status == ReaderStatus.Live) { target ->
                connection.cancelQueued(target.requestId, state.history.held)
            } else null,
            onSharedAction = if (managed) { message, action ->
                val token = history.actionToken()
                when (action) {
                    "status" -> actionScope.launch { showResult(history.checkStatus(requireNotNull(message.unknownId), token)) }
                    "replay" -> actionScope.launch { showResult(history.replayUnknown(requireNotNull(message.unknownId), token)) }
                    "retry" -> retryRow = message.sharedRow?.let { it to token }
                    "dismiss" -> actionScope.launch { showResult(history.dismiss(requireNotNull(message.sharedRow), token)) }
                    "removeUnknown" -> actionScope.launch { showResult(history.removeUnknown(requireNotNull(message.unknownId), token)) }
                }
            } else null,
            sharedEpoch = state.replica?.epoch,
            unresolved = state.unresolved,
            collapsePendingReactions = reader.actionError == null,
            working = reader.isWorking(agent),
            onNearTop = history::nearTop,
            listState = listState,
            followRequest = followRequest,
            modifier = Modifier.weight(1f),
            bottomPadding = bottomPadding,
        )

    }
}

@Composable
internal fun ChatHistoryList(
    messages: List<ChatHistoryMessage>,
    modifier: Modifier = Modifier,
    focusKey: String? = null,
    loaded: Boolean = true,
    agent: AgentKind = AgentKind.CLAUDE,
    fontScale: Float = 1f,
    sunlight: Boolean = false,
    replyGate: ReplySwipeGate = remember { ReplySwipeGate() },
    onReply: (String, Boolean) -> Unit = { _, _ -> },
    questions: List<PendingQuestion> = emptyList(),
    questionScreen: String = "",
    questionError: String? = null,
    onQuestionChoice: ((PendingQuestion, Set<Int>, String) -> Boolean)? = null,
    onNearTop: (Boolean) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    followRequest: Int = 0,
    sessionName: String = "",
    unreadMessageKey: String? = null,
    onMarkUnread: (String) -> Unit = {},
    hasOlder: Boolean = false,
    onReaction: ((ChatHistoryMessage, ChatReactions.Emoji) -> Unit)? = null,
    onCancelPending: ((ChatHistoryMessage) -> Unit)? = null,
    onSharedAction: ((ChatHistoryMessage, String) -> Unit)? = null,
    sharedEpoch: Long? = null,
    unresolved: List<dev.multiprompt.companion.reader.MessageJournal.Entry> = emptyList(),
    collapsePendingReactions: Boolean = true,
    working: Boolean = false,
    bottomPadding: Dp = 0.dp,
) {
    val presentation = remember(messages, agent, collapsePendingReactions) {
        if (agent == AgentKind.CLAUDE) ChatReactions.present(messages, collapsePendingReactions)
        else ChatReactions.Presentation(messages, emptyMap())
    }
    val nearTop by rememberUpdatedState(onNearTop)
    var pendingJump by remember { mutableStateOf<ReplyJump?>(null) }
    var highlightedKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusKey) { if (focusKey != null) highlightedKey = focusKey }
    val context = LocalContext.current
    LaunchedEffect(pendingJump, presentation.messages, hasOlder, loaded, questions, working) {
        val request = pendingJump ?: return@LaunchedEffect
        val visible = presentation.messages
        val keys = visible.map { it.key }
        val texts = visible.map { it.text }
        val before = visible.indexOfFirst { it.key == request.message.key }.takeIf { it >= 0 }
            ?: visible.indexOfLast { it.role == request.message.role && it.text == request.message.text }
        val source = ReplyQuote.sourceIndex(texts, before, request.reply.quoted)
        if (source != null) {
            highlightedKey = keys[source]
            listState.scrollToItem(keys.lastIndex - source + questions.size + (if (working) 1 else 0))
            pendingJump = null
        } else if (before < 0 || (!hasOlder && loaded)) {
            pendingJump = null
            android.widget.Toast.makeText(context, "Original message not found", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(highlightedKey) {
        if (highlightedKey != null) {
            delay(2_000)
            highlightedKey = null
        }
    }
    val atBottom = !listState.canScrollBackward && pendingJump == null
    val newest = messages.lastOrNull()
    LaunchedEffect(newest?.key, newest?.text, newest?.pending, questions) {
        if (atBottom) listState.scrollToItem(0)
    }
    LaunchedEffect(followRequest) {
        if (followRequest > 0) listState.scrollToItem(0)
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            pendingJump != null || (info.totalItemsCount > 0 && (info.visibleItemsInfo.maxOfOrNull { it.index } ?: 0) >= info.totalItemsCount - 8)
        }.distinctUntilChanged().collect { nearTop(it) }
    }
    DisposableEffect(Unit) { onDispose { nearTop(false) } }
    val fontSize = (14f * fontScale).sp
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("chat-history"),
        state = listState,
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(if (LocalReaderStyle.current) ReaderMessageSpacing else 10.dp),
        contentPadding = if (LocalReaderStyle.current) androidx.compose.foundation.layout.PaddingValues(top = ReaderFloatingInset + WindowInsets.statusBars.asPaddingValues().calculateTopPadding(), bottom = ReaderMessageSpacing + bottomPadding) else androidx.compose.foundation.layout.PaddingValues(bottom = bottomPadding),
    ) {
        if (working) item(key = "working") { WorkingDots() }
        questions.asReversed().forEach { question ->
            item(key = "question:${question.callId}:${question.index}") {
                QuestionChoices(question, enabled = onQuestionChoice != null && question.isVisible(questionScreen), error = questionError) { choices, other ->
                    onQuestionChoice?.invoke(question, choices, other) == true
                }
            }
        }
        itemsIndexed(presentation.messages.asReversed(), key = { _, message -> message.key }, contentType = { _, message -> message.role }) { index, message ->
            Column {
                if (message.key == unreadMessageKey) {
                    Text("New", color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                }
                val previous = presentation.messages.getOrNull(presentation.messages.lastIndex - index - 1)
                if (LocalReaderStyle.current && previous != null && previous.role != message.role) Spacer(Modifier.height(10.dp))
                HistoryBubble(message.text, message.role, message.pending, fontScale, sunlight, replyGate, onReply,
                    messageId = message.requestId, sessionName = sessionName, onMarkUnread = { onMarkUnread(message.key) },
                    key = message.key, highlighted = highlightedKey == message.key,
                    onJump = { pendingJump = ReplyJump(message, it) },
                    reactions = presentation.badges[message.key].orEmpty(),
                    onReaction = if (agent == AgentKind.CLAUDE && message.role == "assistant" && onReaction != null) {
                        { emoji -> onReaction(message, emoji) }
                    } else null,
                    delivery = message.delivery,
                    onCancel = if (message.role == "user" && message.delivery == "Queued" && message.requestId.isNotEmpty() && onCancelPending != null) {
                        { onCancelPending(message) }
                    } else null)
                if (onSharedAction != null) {
                    if (message.unknownId != null) {
                        val original = unresolved.firstOrNull { it.id == message.unknownId && it.operation == "submit" }
                        val cancelling = original != null && unresolved.any { it.operation == "dismiss" && it.sameChat(original.account, original.scope) && it.messageId == message.requestId && it.text == message.text }
                        KitSecondaryButton("Check status", onClick = { onSharedAction(message, "status") })
                        if (!cancelling) KitSecondaryButton("Send again", onClick = { onSharedAction(message, "replay") })
                        KitSecondaryButton("Remove", onClick = { onSharedAction(message, "removeUnknown") })
                    }
                    val row = message.sharedRow
                    val uncertain = unresolved.firstOrNull { it.messageId == row?.id && it.operation in setOf("retry", "dismiss") &&
                        (it.text == null || it.text == row?.text) }
                    if (uncertain != null) {
                        val recovery = message.copy(unknownId = uncertain.id)
                        KitSecondaryButton(if (uncertain.operation == "retry") "Send again" else "Remove", onClick = {
                            onSharedAction(recovery, if (uncertain.operation == "dismiss" && uncertain.text != null) "removeUnknown" else "replay")
                        })
                    } else if (row != null && row.epoch == sharedEpoch) {
                        if (row.state == dev.multiprompt.companion.reader.MessageProtocol.State.FAILED)
                            KitSecondaryButton("Send again", onClick = { onSharedAction(message, "retry") })
                        if (row.state in setOf(dev.multiprompt.companion.reader.MessageProtocol.State.QUEUED, dev.multiprompt.companion.reader.MessageProtocol.State.FAILED))
                            KitSecondaryButton("Remove", onClick = { onSharedAction(message, "dismiss") })
                    }
                }
            }
        }
        if (presentation.messages.isEmpty()) {
            item(key = "empty") {
                Text(if (loaded) "No messages yet" else "Loading conversation…", fontSize = fontSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

@Composable
private fun HistoryBubble(
    text: String,
    role: String,
    pending: Boolean,
    fontScale: Float,
    sunlight: Boolean,
    gate: ReplySwipeGate,
    onReply: (String, Boolean) -> Unit,
    messageId: String = "",
    sessionName: String = "",
    onMarkUnread: () -> Unit = {},
    key: String,
    highlighted: Boolean,
    onJump: (ReplyQuote.Reply) -> Unit,
    reactions: Set<ChatReactions.Emoji> = emptySet(),
    onReaction: ((ChatReactions.Emoji) -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    delivery: String? = null,
) {
    val fontSize = chatFontSize((14f * fontScale).sp, fontScale)
    val isUser = role == "user"
    val note = remember(text, isUser, pending, key) { if (isUser && !pending && listOf("@local:", "@shared:", "@unknown:").none(key::startsWith)) SessionNote.parse(text) else null }
    val reply = remember(text, isUser, note) { if (isUser && note == null) ReplyQuote.parse(text) else null }
    val start = remember { BringIntoViewRequester() }
    LaunchedEffect(highlighted) {
        if (highlighted) {
            withFrameNanos { }
            start.bringIntoView(Rect(0f, 0f, 1f, 1f))
        }
    }
    Column(Modifier.testTag("chat-message-$key").bringIntoViewRequester(start).then(
        if (highlighted) Modifier.semantics { stateDescription = "Original message" } else Modifier,
    )) {
        if (note != null) {
            SessionNoteChip(note, sessionName, sunlight, fontScale, key)
            return@Column
        }
        if (reply != null) ReplyReference(reply, sunlight, fontScale, Modifier.alpha(if (pending) 0.5f else 1f)) { onJump(reply) }
        ReplyableBubble(isUser = isUser, sunlight = sunlight, text = text,
            modifier = Modifier.alpha(if (pending) 0.5f else 1f),
            onReply = { onReply(it, isUser) }, gate = gate,
            messageId = messageId, sessionName = sessionName, onMarkUnread = onMarkUnread,
            highlighted = highlighted, reactions = reactions, onReaction = onReaction, onCancel = onCancel) {
            if (pending || delivery != null) {
                Text(delivery ?: "Queued", fontFamily = chatFontFamily(), fontSize = if (LocalReaderStyle.current) ReaderMetaTextSize * fontScale else (fontSize.value - 3f).coerceAtLeast(8f).sp,
                    color = chatHintColor(sunlight))
            }
            if (isUser) {
                ChatLinkText(reply?.body ?: text) { line ->
                    Text(terminalLinks(line, sunlight), fontFamily = chatFontFamily(), fontSize = fontSize,
                        lineHeight = chatLineHeight((fontSize.value * 1.5f).sp, fontScale), color = chatTextColor(sunlight))
                }
            } else {
                MarkdownText(text = text, fontSize = fontSize, color = chatTextColor(sunlight), linkColor = chatLinkColor(terminalBlue(sunlight)), imagePreviews = true)
            }
        }
    }
}

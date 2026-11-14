package dev.multiprompt.companion.ui

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.reader.ChatMention
import dev.multiprompt.companion.reader.ChatReactions
import dev.multiprompt.companion.reader.ReaderStatus
import dev.multiprompt.companion.ssh.TmuxText
import dev.multiprompt.companion.reader.PendingQuestion
import dev.multiprompt.companion.reader.ChatHistoryMessage
import dev.multiprompt.companion.reader.ReaderState
import dev.multiprompt.companion.reader.SessionReaderConnection
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.multiprompt.companion.reader.ChatHistory
import dev.multiprompt.companion.reader.MessageActionResult
import dev.multiprompt.companion.reader.MessageProtocol
import dev.multiprompt.companion.reader.PromptAdmission
import dev.multiprompt.companion.reader.QueuedEdit

private data class ReplyJump(val message: ChatHistoryMessage, val reply: ReplyQuote.Reply)

private val FAILURE_LABELS = setOf("Failed", "Confirmation unknown", "Local · unconfirmed", ChatHistory.NOT_SENT)

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
    onNewBelow: (Int) -> Unit = {},
    focusKey: String? = null,
    bottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
    unreadSinceEpochSeconds: Long? = null,
    onEditPending: ((ChatHistoryMessage) -> Unit)? = null,
) {
    val history = remember(connection, agent, workingDirectory) { connection.chatHistory(agent, workingDirectory) }
    val state by history.state.collectAsState()
    val managed = connection.sharedMessages
    val waiting = state.replica?.messages?.values?.any { it.state == MessageProtocol.State.QUEUED || it.state == MessageProtocol.State.SENT } == true
    val now by produceState(System.currentTimeMillis() / 1000.0, waiting) {
        while (waiting) {
            value = System.currentTimeMillis() / 1000.0
            delay(15_000)
        }
    }
    val seen = remember(history) { HashMap<String, Double>() }
    state.replica?.messages?.values?.forEach { if (it.state == MessageProtocol.State.QUEUED || it.state == MessageProtocol.State.SENT) seen.getOrPut(it.id) { System.currentTimeMillis() / 1000.0 } }
    val messages = if (managed) state.history.managedMessages(state.replica, state.unresolved, now, state.retrying, seen) else state.history.displayMessages(reader.sentRecord)
    val speakable = remember(messages) { dev.multiprompt.companion.reader.ReadAloud.replies(messages) }
    LaunchedEffect(speakable) { onSpeakableReplies(speakable) }
    LaunchedEffect(state.loaded, state.history.sid, state.retrying) { onChatReady(state.loaded && state.history.sid.isNotEmpty() && !state.retrying) }
    val pendingReset = if (managed) state.unresolved.firstOrNull { it.operation == "reset" }?.id else null
    LaunchedEffect(history, pendingReset) {
        while (pendingReset != null && history.recoverReset(history.actionToken()) != dev.multiprompt.companion.reader.MessageActionResult.Applied) delay(5_000)
    }
    SideEffect {
        history.awaitingMessages(messages.any { it.pending })
        if (!managed) history.deliveryIds(reader.sentRecord.map { it.messageId }.filter { it.isNotEmpty() })
    }
    DisposableEffect(history) { onDispose { history.awaitingMessages(false) } }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val toast: (String) -> Unit = { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show() }
    val hostClosed by history.hostClosed.collectAsState()
    val hostError by history.hostError.collectAsState()
    val hostRequest = state.replica?.host?.request
    val hostCards = hostRequest?.takeIf { it.id !in hostClosed }?.cards()?.filter { "${it.callId}#${it.index}" !in hostClosed }.orEmpty()
    val questions = if (hostRequest != null) hostCards else state.history.pendingQuestions
    Column(modifier.fillMaxSize()) {
        state.messageError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
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
            questionError = if (hostRequest != null) hostError else reader.actionError,
            onQuestionChoice = if (hostRequest != null) { question, choices, other ->
                history.answerHost(question, choices, other).also { if (it) onSessionUsed() }
            } else if (!reader.sending && reader.status == ReaderStatus.Live) { question, choices, other ->
                connection.answerQuestion(question, choices, other).also { if (it) onSessionUsed() }
            } else null,
            questionScreen = reader.screenOutput,
            sessionName = sessionName,
            unreadMessageKey = unreadMessageKey,
            onMarkUnread = onMarkUnread,
            onReaction = onReaction,
            onEditPending = if (managed) onEditPending else null,
            onCancelPending = if (managed) { target ->
                val row = state.replica?.messages?.get(target.requestId)
                if (row != null) scope.launch {
                    if (history.dismiss(row) != MessageActionResult.Applied) toast("Could not cancel yet. Try again")
                }
            } else if (!reader.sending && reader.status == ReaderStatus.Live) { target ->
                connection.cancelQueued(target.requestId, state.history.held)
            } else null,
            onSendAgain = if (managed) { target, confirmed ->
                val row = state.replica?.messages?.get(target.requestId)
                when {
                    target.unknownId != null -> { scope.launch { if (history.replayUnknown(target.unknownId) != MessageActionResult.Applied) toast("Could not send again. Try again") }; true }
                    row == null -> true
                    row.state != MessageProtocol.State.FAILED && !confirmed -> false
                    else -> { scope.launch { if (history.sendAgain(row) is PromptAdmission.Rejected) toast("Could not send again. Try again") else onSessionUsed() }; true }
                }
            } else null,
            onRemove = if (managed) { target ->
                val row = state.replica?.messages?.get(target.requestId)
                scope.launch {
                    val result = when {
                        target.unknownId != null -> history.removeUnknown(target.unknownId)
                        row != null -> history.dismiss(row)
                        else -> MessageActionResult.Applied
                    }
                    if (result != MessageActionResult.Applied) toast("Could not remove yet. Try again")
                }
            } else null,
            collapsePendingReactions = reader.actionError == null,
            working = reader.isWorking(agent) || state.replica?.host?.running == true,
            onNearTop = history::nearTop,
            listState = listState,
            followRequest = followRequest,
            onNewBelow = onNewBelow,
            modifier = Modifier.weight(1f),
            bottomPadding = bottomPadding,
            unreadSinceEpochSeconds = unreadSinceEpochSeconds,
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
    onNewBelow: (Int) -> Unit = {},
    sessionName: String = "",
    unreadMessageKey: String? = null,
    onMarkUnread: (String) -> Unit = {},
    hasOlder: Boolean = false,
    onReaction: ((ChatHistoryMessage, ChatReactions.Emoji) -> Unit)? = null,
    onCancelPending: ((ChatHistoryMessage) -> Unit)? = null,
    onSendAgain: ((ChatHistoryMessage, Boolean) -> Boolean)? = null,
    onRemove: ((ChatHistoryMessage) -> Unit)? = null,
    collapsePendingReactions: Boolean = true,
    working: Boolean = false,
    bottomPadding: Dp = 0.dp,
    unreadSinceEpochSeconds: Long? = null,
    onEditPending: ((ChatHistoryMessage) -> Unit)? = null,
) {
    val presentation = remember(messages, agent, collapsePendingReactions) {
        if (agent == AgentKind.CLAUDE) ChatReactions.present(messages, collapsePendingReactions)
        else ChatReactions.Presentation(messages, emptyMap())
    }
    val showDots = working || awaitingReply(presentation.messages, questions.size)
    val firstUnreadKey = remember(presentation.messages, unreadMessageKey, unreadSinceEpochSeconds) {
        ChatOpenAnchor.unreadKey(presentation.messages, unreadMessageKey, unreadSinceEpochSeconds)
    }
    var openHold by remember(listState) { mutableStateOf(unreadMessageKey != null || unreadSinceEpochSeconds != null) }
    val nearTop by rememberUpdatedState(onNearTop)
    var pendingJump by remember { mutableStateOf<ReplyJump?>(null) }
    var highlightedKey by remember { mutableStateOf<String?>(null) }
    var confirmResend by remember { mutableStateOf<ChatHistoryMessage?>(null) }
    confirmResend?.let { target ->
        KitSheet(onDismiss = { confirmResend = null }) {
            Text("Your agent may already have this message. Sending again may duplicate it. Send again?", color = Kit.Text)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                KitSecondaryButton("Cancel", { confirmResend = null })
                KitPrimaryButton("Send again", {
                    confirmResend = null
                    onSendAgain?.invoke(target, true)
                })
            }
        }
    }
    LaunchedEffect(focusKey) { if (focusKey != null) highlightedKey = focusKey }
    val context = LocalContext.current
    LaunchedEffect(pendingJump, presentation.messages, hasOlder, loaded, questions, showDots) {
        val request = pendingJump ?: return@LaunchedEffect
        val visible = presentation.messages
        val keys = visible.map { it.key }
        val texts = visible.map { it.text }
        val before = visible.indexOfFirst { it.key == request.message.key }.takeIf { it >= 0 }
            ?: visible.indexOfLast { it.role == request.message.role && it.text == request.message.text }
        val source = ReplyQuote.sourceIndex(texts, before, request.reply.quoted)
        if (source != null) {
            highlightedKey = keys[source]
            listState.scrollToItem(keys.lastIndex - source + questions.size + (if (showDots) 1 else 0))
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
    val anchorPosition = ChatOpenAnchor.listIndex(presentation.messages, firstUnreadKey, questions.size, showDots)
    val holdingForUnread = openHold && anchorPosition != null
    val newest = messages.lastOrNull()
    LaunchedEffect(newest?.key, newest?.text, newest?.pending, questions) {
        if (atBottom && !holdingForUnread) listState.scrollToItem(0)
    }
    LaunchedEffect(followRequest) {
        if (followRequest > 0) {
            openHold = false
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) openHold = false }
    }
    LaunchedEffect(openHold, anchorPosition, pendingJump != null) {
        if (!openHold || pendingJump != null) return@LaunchedEffect
        val position = anchorPosition ?: return@LaunchedEffect
        listState.scrollToItem(position)
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == position }
        if (item != null) {
            val viewport = info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding
            listState.scrollToItem(position, ChatOpenAnchor.scrollOffset(item.size, viewport))
        }
        openHold = false
    }
    var newBelow by remember { mutableStateOf(0) }
    var previousMessages by remember { mutableStateOf<List<ChatHistoryMessage>?>(null) }
    val scrolledUp = listState.canScrollBackward
    LaunchedEffect(messages) {
        val before = previousMessages
        previousMessages = messages
        if (scrolledUp && before != null) newBelow += newRepliesBetween(before, messages)
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollBackward }.distinctUntilChanged().collect { if (!it) newBelow = 0 }
    }
    LaunchedEffect(newBelow) { onNewBelow(newBelow) }
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
        if (showDots) item(key = "working") { WorkingDots() }
        questions.asReversed().forEach { question ->
            item(key = "question:${question.callId}:${question.index}") {
                QuestionChoices(question, enabled = onQuestionChoice != null && (question.host != null || question.isVisible(questionScreen)), error = questionError) { choices, other ->
                    onQuestionChoice?.invoke(question, choices, other) == true
                }
            }
        }
        itemsIndexed(presentation.messages.asReversed(), key = { _, message -> message.key }, contentType = { _, message -> message.role }) { index, message ->
            Column {
                if (message.key == firstUnreadKey) {
                    Text("New", color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                }
                val previous = presentation.messages.getOrNull(presentation.messages.lastIndex - index - 1)
                if (LocalReaderStyle.current && previous != null && previous.role != message.role) Spacer(Modifier.height(10.dp))
                HistoryBubble(if (message.role == "user") ChatMention.visible(message.text) else message.text, message.role, message.pending, fontScale, sunlight, replyGate, onReply,
                    messageId = message.requestId, sessionName = sessionName, onMarkUnread = { openHold = false; onMarkUnread(message.key) },
                    key = message.key, highlighted = highlightedKey == message.key,
                    onJump = { pendingJump = ReplyJump(message, it) },
                    reactions = presentation.badges[message.key].orEmpty(),
                    onReaction = if (agent == AgentKind.CLAUDE && message.role == "assistant" && onReaction != null) {
                        { emoji -> onReaction(message, emoji) }
                    } else null,
                    delivery = message.delivery?.takeIf { it in FAILURE_LABELS },
                    onCancel = if (message.role == "user" && (message.delivery == "Queued" || message.cancellable) && message.requestId.isNotEmpty() && onCancelPending != null) {
                        { onCancelPending(message) }
                    } else null)
                if (onEditPending != null && QueuedEdit.shows(message)) {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp).testTag("edit-waiting-${message.key}"), horizontalArrangement = Arrangement.End) {
                        KitSecondaryButton("Edit", { onEditPending(message) })
                    }
                }
                if (message.role == "user" && message.delivery == ChatHistory.NOT_SENT && (onSendAgain != null || onRemove != null)) {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp).testTag("not-sent-${message.key}"), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        if (onRemove != null) KitSecondaryButton("Remove", { onRemove(message) }, destructive = true)
                        if (onSendAgain != null) KitSecondaryButton("Send again", { if (!onSendAgain(message, false)) confirmResend = message })
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
            modifier = Modifier.alpha(if (pending && isUser) 0.5f else 1f),
            onReply = { onReply(it, isUser) }, gate = gate,
            messageId = messageId, sessionName = sessionName, onMarkUnread = onMarkUnread,
            highlighted = highlighted, reactions = reactions, onReaction = onReaction, onCancel = onCancel) {
            if (delivery != null) {
                Text(delivery, fontFamily = chatFontFamily(), fontSize = if (LocalReaderStyle.current) ReaderMetaTextSize * fontScale else (fontSize.value - 3f).coerceAtLeast(8f).sp,
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

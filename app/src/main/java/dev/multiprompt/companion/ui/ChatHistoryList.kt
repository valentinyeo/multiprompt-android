package dev.multiprompt.companion.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.reader.ReaderStatus
import dev.multiprompt.companion.ssh.ClaudeQuestion
import dev.multiprompt.companion.reader.ChatHistoryMessage
import dev.multiprompt.companion.reader.ReaderState
import dev.multiprompt.companion.reader.SessionReaderConnection
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay

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
    modifier: Modifier = Modifier,
) {
    val history = remember(connection, agent, workingDirectory) { connection.chatHistory(agent, workingDirectory) }
    val state by history.state.collectAsState()
    val messages = state.history.displayMessages(reader.screenOutput, agent, reader.sentRecord, System.currentTimeMillis())
    SideEffect { history.awaitingMessages(messages.any { it.pending }) }
    DisposableEffect(history) { onDispose { history.awaitingMessages(false) } }
    val question = remember(reader.screenOutput, agent) {
        if (agent == AgentKind.CLAUDE) ClaudeQuestion.parse(reader.screenOutput) else null
    }
    Column(modifier.fillMaxSize()) {
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
            loaded = state.loaded,
            hasOlder = !state.loaded || state.history.start > 0 || state.history.more,
            agent = agent,
            fontScale = fontScale,
            sunlight = sunlight,
            replyGate = replyGate,
            onReply = onReply,
            question = question,
            questionError = reader.actionError,
            onQuestionChoice = if (!reader.sending && reader.status == ReaderStatus.Live) { option ->
                question != null && connection.answerQuestion(question, option).also { if (it) onSessionUsed() }
            } else null,
            onChoice = if (question == null && !reader.sending && reader.status == ReaderStatus.Live) { answer ->
                connection.sendPrompt(answer).also { if (it) onSessionUsed() }
            } else null,
            sessionName = sessionName,
            unreadMessageKey = unreadMessageKey,
            onMarkUnread = onMarkUnread,
            onNearTop = history::nearTop,
            listState = listState,
            followRequest = followRequest,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun ChatHistoryList(
    messages: List<ChatHistoryMessage>,
    modifier: Modifier = Modifier,
    loaded: Boolean = true,
    agent: AgentKind = AgentKind.CLAUDE,
    fontScale: Float = 1f,
    sunlight: Boolean = false,
    replyGate: ReplySwipeGate = remember { ReplySwipeGate() },
    onReply: (String, Boolean) -> Unit = { _, _ -> },
    onChoice: ((String) -> Boolean)? = null,
    question: ClaudeQuestion? = null,
    questionError: String? = null,
    onQuestionChoice: ((Int) -> Boolean)? = null,
    onNearTop: (Boolean) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    followRequest: Int = 0,
    sessionName: String = "",
    unreadMessageKey: String? = null,
    onMarkUnread: (String) -> Unit = {},
    hasOlder: Boolean = false,
) {
    val nearTop by rememberUpdatedState(onNearTop)
    var pendingJump by remember { mutableStateOf<ReplyJump?>(null) }
    var highlightedKey by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    LaunchedEffect(pendingJump, messages, hasOlder, loaded, question) {
        val request = pendingJump ?: return@LaunchedEffect
        val keys = messages.map { it.key }
        val texts = messages.map { it.text }
        val before = messages.indexOfFirst { it.key == request.message.key }.takeIf { it >= 0 }
            ?: messages.indexOfLast { it.role == request.message.role && it.text == request.message.text }
        val source = ReplyQuote.sourceIndex(texts, before, request.reply.quoted)
        if (source != null) {
            highlightedKey = keys[source]
            listState.scrollToItem(keys.lastIndex - source + if (question != null) 1 else 0)
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
    val atBottom = !listState.canScrollBackward
    val newest = messages.lastOrNull()
    val selectedAnswers = remember(messages) {
        messages.zipWithNext().filter { (reply, answer) -> reply.role == "assistant" && answer.role == "user" && !answer.pending }
            .associate { (reply, answer) -> reply.key to answer.text }
    }
    LaunchedEffect(newest?.key, newest?.text, newest?.pending, question) {
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
        contentPadding = if (LocalReaderStyle.current) androidx.compose.foundation.layout.PaddingValues(top = ReaderFloatingInset + WindowInsets.statusBars.asPaddingValues().calculateTopPadding(), bottom = ReaderMessageSpacing) else androidx.compose.foundation.layout.PaddingValues(),
    ) {
        if (question != null) {
            item(key = "active-question") {
                QuestionChoices(question, enabled = onQuestionChoice != null, error = questionError) { onQuestionChoice?.invoke(it) == true }
            }
        }
        items(messages.asReversed(), key = { it.key }, contentType = { it.role }) { message ->
            Column {
                if (message.key == unreadMessageKey) {
                    Text("New", color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                }
                HistoryBubble(message.text, message.role, message.pending, fontScale, sunlight, replyGate, onReply,
                    messageId = message.requestId, sessionName = sessionName, onMarkUnread = { onMarkUnread(message.key) },
                    key = message.key, highlighted = highlightedKey == message.key,
                    onJump = { pendingJump = ReplyJump(message, it) },
                    onChoice = onChoice.takeIf { message.key == newest?.key && message.role == "assistant" },
                    selectedAnswer = selectedAnswers[message.key])
            }
        }
        if (messages.isEmpty()) {
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
    onChoice: ((String) -> Boolean)? = null,
    selectedAnswer: String? = null,
) {
    val fontSize = chatFontSize((14f * fontScale).sp, fontScale)
    val isUser = role == "user"
    val reply = remember(text, isUser) { if (isUser) ReplyQuote.parse(text) else null }
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
        if (reply != null) ReplyReference(reply, sunlight, fontScale, Modifier.alpha(if (pending) 0.5f else 1f)) { onJump(reply) }
        ReplyableBubble(isUser = isUser, sunlight = sunlight, text = text,
            modifier = Modifier.alpha(if (pending) 0.5f else 1f),
            onReply = { onReply(it, isUser) }, gate = gate,
            messageId = messageId, sessionName = sessionName, onMarkUnread = onMarkUnread,
            highlighted = highlighted) {
            if (pending) {
                Text("Queued", fontFamily = chatFontFamily(), fontSize = if (LocalReaderStyle.current) ReaderMetaTextSize * fontScale else (fontSize.value - 3f).coerceAtLeast(8f).sp,
                    color = chatHintColor(sunlight))
            }
            if (isUser) {
                ChatLinkText(reply?.body ?: text) { line ->
                    Text(terminalLinks(line, sunlight), fontFamily = chatFontFamily(), fontSize = fontSize,
                        lineHeight = chatLineHeight((fontSize.value * 1.5f).sp, fontScale), color = chatTextColor(sunlight))
                }
            } else {
                MarkdownText(text = text, fontSize = fontSize, color = chatTextColor(sunlight), linkColor = chatLinkColor(terminalBlue(sunlight)), imagePreviews = true, onChoice = onChoice, selectedAnswer = selectedAnswer)
            }
        }
    }
}

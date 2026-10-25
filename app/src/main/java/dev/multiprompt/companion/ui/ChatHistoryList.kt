package dev.multiprompt.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.reader.ChatHistoryMessage
import dev.multiprompt.companion.reader.QueuedMessages
import dev.multiprompt.companion.reader.ReaderState
import dev.multiprompt.companion.reader.SessionReaderConnection
import kotlinx.coroutines.flow.distinctUntilChanged

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
    modifier: Modifier = Modifier,
) {
    val history = remember(connection, agent, workingDirectory) { connection.chatHistory(agent, workingDirectory) }
    val state by history.state.collectAsState()
    val transcript = remember(state.history) { state.history.messages.map { it.transcript() } }
    val queued = QueuedMessages.pending(transcript, reader.screenOutput, agent, reader.sentRecord, System.currentTimeMillis())
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
            messages = state.history.messages,
            queuedMessages = queued,
            loaded = state.loaded,
            agent = agent,
            fontScale = fontScale,
            sunlight = sunlight,
            replyGate = replyGate,
            onReply = onReply,
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
    queuedMessages: List<String> = emptyList(),
    loaded: Boolean = true,
    agent: AgentKind = AgentKind.CLAUDE,
    fontScale: Float = 1f,
    sunlight: Boolean = false,
    replyGate: ReplySwipeGate = remember { ReplySwipeGate() },
    onReply: (String, Boolean) -> Unit = { _, _ -> },
    onNearTop: (Boolean) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    followRequest: Int = 0,
) {
    val nearTop by rememberUpdatedState(onNearTop)
    val atBottom = !listState.canScrollBackward
    val newest = messages.lastOrNull()
    LaunchedEffect(newest?.key, newest?.text, queuedMessages) {
        if (atBottom) listState.scrollToItem(0)
    }
    LaunchedEffect(followRequest) {
        if (followRequest > 0) listState.scrollToItem(0)
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.maxOfOrNull { it.index } ?: 0) >= info.totalItemsCount - 8
        }.distinctUntilChanged().collect { nearTop(it) }
    }
    DisposableEffect(Unit) { onDispose { nearTop(false) } }
    val fontSize = (14f * fontScale).sp
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("chat-history"),
        state = listState,
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(if (LocalReaderStyle.current) ReaderMessageSpacing else 10.dp),
        contentPadding = if (LocalReaderStyle.current) androidx.compose.foundation.layout.PaddingValues(top = ReaderFloatingInset, bottom = ReaderMessageSpacing) else androidx.compose.foundation.layout.PaddingValues(),
    ) {
        itemsIndexed(queuedMessages.asReversed(), key = { index, text -> "queued:$index:$text" }) { _, text ->
            HistoryBubble(text, "user", "", "You · queued", fontScale, sunlight, replyGate, onReply)
        }
        items(messages.asReversed(), key = { it.key }, contentType = { it.role }) { message ->
            HistoryBubble(message.text, message.role, message.timestamp,
                if (message.role == "user") "You" else agent.label, fontScale, sunlight, replyGate, onReply)
        }
        if (messages.isEmpty() && queuedMessages.isEmpty()) {
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
    timestamp: String,
    author: String,
    fontScale: Float,
    sunlight: Boolean,
    gate: ReplySwipeGate,
    onReply: (String, Boolean) -> Unit,
) {
    val fontSize = chatFontSize((14f * fontScale).sp, fontScale)
    val isUser = role == "user"
    ReplyableBubble(isUser = isUser, sunlight = sunlight, text = text,
        onReply = { onReply(it, isUser) }, gate = gate) {
        Text(author, fontFamily = chatFontFamily(), fontSize = if (LocalReaderStyle.current) ReaderMetaTextSize * fontScale else (fontSize.value - 3f).coerceAtLeast(8f).sp,
            color = chatHintColor(sunlight))
        if (isUser) {
            Text(terminalLinks(text, sunlight), fontFamily = chatFontFamily(), fontSize = fontSize,
                lineHeight = chatLineHeight((fontSize.value * 1.5f).sp, fontScale), color = chatTextColor(sunlight))
        } else {
            MarkdownText(text = text, fontSize = fontSize, color = chatTextColor(sunlight), linkColor = chatLinkColor(terminalBlue(sunlight)))
        }
        if (timestamp.isNotEmpty()) {
            Text(formatChatTime(timestamp), fontFamily = chatFontFamily(),
                fontSize = if (LocalReaderStyle.current) ReaderMetaTextSize * fontScale else (fontSize.value - 3f).coerceAtLeast(8f).sp, color = chatHintColor(sunlight))
        }
    }
}

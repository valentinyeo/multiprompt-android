package dev.multiprompt.companion.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MarkChatUnread
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import dev.multiprompt.companion.BuildConfig
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import dev.multiprompt.companion.reader.ChatReactions
import kotlin.math.abs

internal class ReplySwipeGate {
    var owner: Any? = null
    fun suppressRight(): Boolean = owner != null
}

internal val ReplySelectable = SemanticsPropertyKey<Boolean>("ReplySelectable")
internal var SemanticsPropertyReceiver.replySelectable by ReplySelectable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReplyableBubble(
    isUser: Boolean,
    sunlight: Boolean,
    text: String,
    onReply: (String) -> Unit,
    gate: ReplySwipeGate,
    modifier: Modifier = Modifier,
    messageId: String = "",
    sessionName: String = "",
    onMarkUnread: () -> Unit = {},
    highlighted: Boolean = false,
    reactions: Set<ChatReactions.Emoji> = emptySet(),
    onReaction: ((ChatReactions.Emoji) -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    content: @Composable (selectable: Boolean) -> Unit,
) {
    var selectable by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var dragX by remember { mutableFloatStateOf(0f) }
    val owner = remember { Any() }
    val currentReply by rememberUpdatedState { onReply(text) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val confirm: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    DisposableEffect(gate, owner) {
        onDispose { if (gate.owner === owner) gate.owner = null }
    }
    val gestures = Modifier.pointerInput(gate, owner) {
        val threshold = 72.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            gate.owner = owner
            var totalX = 0f
            var totalY = 0f
            var claimed = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        if (claimed && !change.isConsumed && totalX > threshold) currentReply()
                        break
                    }
                    if (change.isConsumed || event.changes.count { it.pressed } > 1) break
                    val delta = change.position - change.previousPosition
                    totalX += delta.x
                    totalY += delta.y
                    if (!claimed && abs(totalY) > viewConfiguration.touchSlop && abs(totalY) >= abs(totalX)) break
                    if (!claimed && totalX > viewConfiguration.touchSlop && totalX > abs(totalY) * 2) {
                        claimed = true
                    }
                    if (claimed) {
                        dragX = (totalX * 0.25f).coerceIn(0f, 48.dp.toPx())
                        change.consume()
                    }
                }
            } finally {
                dragX = 0f
                if (gate.owner === owner) gate.owner = null
            }
        }
    }.then(
        if (selectable) Modifier else Modifier.pointerInput(Unit) {
            detectTapGestures(onLongPress = { menuExpanded = true })
        },
    )
    ChatBubble(
        isUser = isUser,
        sunlight = sunlight,
        highlighted = highlighted,
        contentModifier = modifier
            .graphicsLayer { translationX = dragX }
            .semantics { replySelectable = selectable }
            .then(gestures),
    ) {
        Column {
            content(false)
            if (reactions.isNotEmpty()) {
                val shown = ChatReactions.Emoji.entries.filter { it in reactions }
                Row(
                    Modifier.padding(top = 6.dp).testTag("reaction-badges:$messageId")
                        .semantics { contentDescription = "Reactions: " + shown.joinToString(" ") { it.text } }
                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    shown.forEach { Text(it.text, fontSize = 16.sp) }
                }
            }
        }
    }
    if (menuExpanded) {
        ModalBottomSheet(
            onDismissRequest = { menuExpanded = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = (LocalConfiguration.current.screenHeightDp * 0.46f).dp)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                @Composable
                fun group(content: @Composable () -> Unit) {
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))) {
                        content()
                    }
                }
                @Composable
                fun action(label: String, icon: ImageVector, run: () -> Unit) {
                    Row(
                        Modifier.fillMaxWidth().clickable { menuExpanded = false; run() }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (!isUser && onReaction != null) {
                    group {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            ChatReactions.Emoji.entries.forEach { emoji ->
                                TextButton(
                                    onClick = { menuExpanded = false; onReaction(emoji) },
                                    modifier = Modifier.testTag("reaction-${emoji.name.lowercase()}"),
                                ) { Text(emoji.text, fontSize = 28.sp) }
                            }
                        }
                    }
                }
                if (onCancel != null) {
                    group {
                        action("Cancel message", Icons.Default.Close) { onCancel() }
                    }
                }
                group {
                    action("Reply", Icons.Default.Reply) { currentReply(); confirm("Reply added") }
                    action("Mark as unread", Icons.Default.MarkChatUnread) { onMarkUnread(); confirm("Marked as unread") }
                }
                group {
                    action("Copy", Icons.Default.ContentCopy) { clipboard.setText(AnnotatedString(text)); confirm("Copied") }
                    action("Select Text", Icons.Default.SelectAll) { selectable = true; confirm("Select text to copy") }
                    action("Copy Request ID", Icons.Default.Tag) {
                        if (messageId.isNotBlank()) {
                            clipboard.setText(AnnotatedString(messageId)); confirm("Request ID copied")
                        } else {
                            confirm("No transcript ID for this message yet")
                        }
                    }
                }
                group {
                    action("Report", Icons.Default.Flag) {
                        val report = "Session: $sessionName\nMessage ID: ${messageId.ifBlank { "Not available yet" }}\nApp: ${BuildConfig.VERSION_NAME}\n\n$text"
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, report)
                        }, "Report message"))
                        confirm("Choose where to share")
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    if (selectable) {
        AlertDialog(
            onDismissRequest = { selectable = false },
            title = { Text("Select Text") },
            text = { SelectionContainer { Text(text, Modifier.verticalScroll(rememberScrollState())) } },
            confirmButton = { TextButton(onClick = { selectable = false }) { Text("Done") } },
        )
    }
}

@Composable
internal fun ReplyQuoteBar(
    message: String,
    author: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 4.dp)
            .then(if (LocalMaterial3.current) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(40.dp).background(MaterialTheme.colorScheme.primary))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text("Reply to $author", style = if (LocalMaterial3.current) MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp) else MaterialTheme.typography.labelMedium, color = if (LocalMaterial3.current) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
            Text(
                ReplyQuote.preview(message),
                style = if (LocalMaterial3.current) MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) else MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onCancel) {
            Icon(Icons.Default.Close, contentDescription = "Cancel reply")
        }
    }
}

@Composable
internal fun ReplyReference(
    reply: ReplyQuote.Reply,
    sunlight: Boolean,
    fontScale: Float,
    modifier: Modifier = Modifier,
    onJump: () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        Row(
            Modifier.align(Alignment.CenterEnd).widthIn(max = maxWidth * ChatUserMaxWidthFraction)
                .semantics { contentDescription = "Jump to original message" }
                .clickable(onClick = onJump).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Default.Reply, contentDescription = null, tint = chatHintColor(sunlight), modifier = Modifier.size(14.dp))
            Text(
                reply.preview,
                modifier = Modifier.weight(1f, fill = false).testTag("reply-reference-text"),
                color = chatHintColor(sunlight),
                fontSize = (12f * fontScale).sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

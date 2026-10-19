package dev.multiprompt.companion.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs

internal class ReplySwipeGate {
    var owner: Any? = null
    fun suppressRight(): Boolean = owner != null
}

internal val ReplySelectable = SemanticsPropertyKey<Boolean>("ReplySelectable")
internal var SemanticsPropertyReceiver.replySelectable by ReplySelectable

@Composable
internal fun ReplyableBubble(
    isUser: Boolean,
    sunlight: Boolean,
    text: String,
    onReply: (String) -> Unit,
    gate: ReplySwipeGate,
    modifier: Modifier = Modifier,
    content: @Composable (selectable: Boolean) -> Unit,
) {
    var selectable by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var dragX by remember { mutableFloatStateOf(0f) }
    val owner = remember { Any() }
    val currentReply by rememberUpdatedState { onReply(text) }
    val clipboard = LocalClipboardManager.current
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
        contentModifier = modifier
            .graphicsLayer { translationX = dragX }
            .semantics { replySelectable = selectable }
            .then(gestures),
    ) {
        Box {
            if (selectable) {
                SelectionContainer { Column { content(true) } }
            } else {
                Column { content(false) }
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(text = { Text("Reply") }, onClick = {
                    menuExpanded = false
                    currentReply()
                })
                DropdownMenuItem(text = { Text("Copy") }, onClick = {
                    menuExpanded = false
                    clipboard.setText(AnnotatedString(text))
                })
                DropdownMenuItem(text = { Text("Select text") }, onClick = {
                    menuExpanded = false
                    selectable = true
                })
            }
        }
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

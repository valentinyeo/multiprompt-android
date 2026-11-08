package dev.multiprompt.companion.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.reader.ChatHistoryMessage
import dev.multiprompt.companion.reader.ReaderState
import dev.multiprompt.companion.ssh.TmuxText

internal fun ReaderState.isWorking(agent: AgentKind): Boolean = sending || (hookState?.active
    ?: TmuxText.readerBlocks(screenOutput, agent).any {
        it.kind == TmuxText.ReaderBlockKind.PROGRESS && isTransientProgress(it.text)
    })

internal fun awaitingReply(messages: List<ChatHistoryMessage>, openQuestions: Int): Boolean =
    openQuestions == 0 && messages.lastOrNull()?.role == "user"

internal fun workingDotAlpha(dot: Int, phase: Float): Float =
    if (dot == phase.toInt().coerceIn(0, 2)) 0.85f else 0.3f

@Composable
internal fun WorkingDots() {
    val phase by rememberInfiniteTransition(label = "working").animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "tick",
    )
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 12.dp).semantics { contentDescription = "Working" },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(3) { dot ->
            val alpha = workingDotAlpha(dot, phase)
            Box(Modifier.size(5.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha), CircleShape))
        }
    }
}

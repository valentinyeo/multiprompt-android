package dev.multiprompt.companion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LocalChatUser = staticCompositionLocalOf { false }

@Composable
fun chatBubbleColor(isUser: Boolean, sunlight: Boolean): Color = when {
    LocalMaterial3.current -> if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    isUser && sunlight -> ChatBubbleUserLight
    isUser -> ChatBubbleUserDark
    sunlight -> ChatBubbleAgentLight
    else -> ChatBubbleAgentDark
}

@Composable
fun chatTextColor(sunlight: Boolean, isUser: Boolean = LocalChatUser.current): Color = when {
    LocalMaterial3.current -> if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    sunlight -> ChatTextLight
    else -> ChatTextDark
}

@Composable
fun chatHintColor(sunlight: Boolean): Color = when {
    LocalMaterial3.current -> MaterialTheme.colorScheme.onSurfaceVariant
    sunlight -> Color(0xFF4A4D52)
    else -> ChatHintDark
}

@Composable
fun ChatBubble(
    isUser: Boolean,
    sunlight: Boolean,
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val bubbleModifier = if (isUser) {
            Modifier.align(Alignment.CenterEnd).widthIn(max = maxWidth * ChatUserMaxWidthFraction)
        } else {
            Modifier.align(Alignment.CenterStart).fillMaxWidth()
        }
        Box(
            bubbleModifier
                .then(contentModifier)
                .clip(chatShape())
                .background(chatBubbleColor(isUser, sunlight)),
        ) {
            CompositionLocalProvider(LocalChatUser provides isUser) {
                ProvideTextStyle(
                    if (LocalMaterial3.current) MaterialTheme.typography.bodyLarge.copy(color = chatTextColor(sunlight)) else {
                        androidx.compose.material3.LocalTextStyle.current
                    },
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) { content() }
                }
            }
        }
    }
}

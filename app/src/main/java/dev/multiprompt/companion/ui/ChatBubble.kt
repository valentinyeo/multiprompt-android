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
import androidx.compose.ui.unit.sp

private val LocalChatUser = staticCompositionLocalOf { false }

@Composable
fun chatBubbleColor(isUser: Boolean, sunlight: Boolean): Color = when {
    LocalReaderStyle.current -> if (isUser) ReaderUserCard else ReaderCard
    LocalMaterial3.current -> if (isUser) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent
    isUser && sunlight -> ChatBubbleUserLight
    isUser -> ChatBubbleUserDark
    sunlight -> ChatBubbleAgentLight
    else -> ChatBubbleAgentDark
}

@Composable
fun chatTextColor(sunlight: Boolean, isUser: Boolean = LocalChatUser.current): Color = when {
    LocalReaderStyle.current || LocalMaterial3.current -> MaterialTheme.colorScheme.onSurface
    sunlight -> ChatTextLight
    else -> ChatTextDark
}

@Composable
fun chatHintColor(sunlight: Boolean): Color = when {
    LocalReaderStyle.current || LocalMaterial3.current -> MaterialTheme.colorScheme.onSurfaceVariant
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
        } else if (LocalReaderStyle.current) {
            Modifier.align(Alignment.CenterStart).widthIn(max = maxWidth * ChatAgentMaxWidthFraction)
        } else {
            Modifier.align(Alignment.CenterStart).fillMaxWidth()
        }
        Box(
            bubbleModifier
                .then(contentModifier)
                .clip(if (LocalReaderStyle.current) ReaderCardShape else if (LocalMaterial3.current) androidx.compose.foundation.shape.RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = if (isUser) 4.dp else 20.dp, bottomStart = 20.dp) else chatShape())
                .background(chatBubbleColor(isUser, sunlight)),
        ) {
            CompositionLocalProvider(LocalChatUser provides isUser) {
                ProvideTextStyle(
                    if (LocalReaderStyle.current) MaterialTheme.typography.bodyLarge.copy(color = chatTextColor(sunlight), fontSize = ReaderTextSize, lineHeight = ReaderLineHeight) else if (LocalMaterial3.current) MaterialTheme.typography.bodyLarge.copy(color = chatTextColor(sunlight), fontSize = 15.sp, lineHeight = 22.sp) else {
                        androidx.compose.material3.LocalTextStyle.current
                    },
                ) {
                    Column(if (LocalReaderStyle.current) Modifier.padding(ReaderCardPadding) else Modifier.padding(horizontal = if (LocalMaterial3.current) 16.dp else 12.dp, vertical = 10.dp)) { content() }
                }
            }
        }
    }
}

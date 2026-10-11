package dev.multiprompt.companion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Bubble fill for a chat message, from the desktop Chat View colours (see Theme.kt). */
fun chatBubbleColor(isUser: Boolean, sunlight: Boolean): Color = when {
    isUser && sunlight -> ChatBubbleUserLight
    isUser -> ChatBubbleUserDark
    sunlight -> ChatBubbleAgentLight
    else -> ChatBubbleAgentDark
}

fun chatTextColor(sunlight: Boolean): Color = if (sunlight) ChatTextLight else ChatTextDark

/** Colour of the small name and time labels (desktop "hint"). */
fun chatHintColor(sunlight: Boolean): Color = if (sunlight) Color(0xFF4A4D52) else ChatHintDark

/**
 * One bubble per message or reply, like the desktop Chat View: the user's on the right (at most
 * 70 percent of the width, light grey), the agent's on the left across the whole column (dark
 * grey), both with the card's 4 dp corners.
 */
@Composable
fun ChatBubble(
    isUser: Boolean,
    sunlight: Boolean,
    modifier: Modifier = Modifier,
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
                .clip(RoundedCornerShape(ChatBubbleRadius))
                .background(chatBubbleColor(isUser, sunlight)),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) { content() }
        }
    }
}

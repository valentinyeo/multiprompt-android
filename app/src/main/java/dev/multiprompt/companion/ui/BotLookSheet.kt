package dev.multiprompt.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.multiprompt.companion.model.TmuxSession

@Composable
internal fun BotLookSheet(
    session: TmuxSession,
    face: Int?,
    color: Int?,
    onPickFace: (Int) -> Unit,
    onPickColor: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var pickedFace by remember(session.hostId, session.name, face) { mutableStateOf(face) }
    var pickedColor by remember(session.hostId, session.name, color) { mutableStateOf(color) }
    KitSheet(onDismiss = onDismiss) {
        BotLookContent(session, pickedFace, pickedColor, onPickFace = { pickedFace = it; onPickFace(it) }, onPickColor = { pickedColor = it; onPickColor(it) })
    }
}

@Composable
internal fun BotLookContent(
    session: TmuxSession,
    pickedFace: Int?,
    pickedColor: Int?,
    onPickFace: (Int) -> Unit,
    onPickColor: (Int?) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Bot look", style = Kit.Heading, color = Kit.Text)
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Face", style = Kit.Label, color = Kit.TextMuted)
            (0 until 20).chunked(5).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { index ->
                        Box(
                            Modifier
                                .size(48.dp)
                                .clickable { onPickFace(index) }
                                .border(
                                    if (pickedFace == index) 3.dp else 1.dp,
                                    if (pickedFace == index) Kit.Text else Kit.Outline,
                                    CircleShape,
                                ),
                        ) {
                            BotAvatar(
                                session.name,
                                44.dp,
                                Modifier.align(Alignment.Center),
                                workspaceColor = session.workspaceColor,
                                face = index,
                                color = pickedColor,
                            )
                        }
                    }
                }
            }
            Text("Colour", style = Kit.Label, color = Kit.TextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                (0..6).forEach { index ->
                    Box(
                        Modifier
                            .size(28.dp)
                            .background(botColor(index), CircleShape)
                            .border(
                                if (pickedColor == index) 3.dp else 1.dp,
                                if (pickedColor == index) Kit.Text else Kit.Outline,
                                CircleShape,
                            )
                            .clickable { onPickColor(index) },
                    )
                }
                Text(
                    "Auto",
                    modifier = Modifier
                        .clickable { onPickColor(null) }
                        .padding(start = 4.dp),
                    style = Kit.Label,
                    color = if (pickedColor == null) Kit.Text else Kit.TextMuted,
                )
            }
        }
    }
}

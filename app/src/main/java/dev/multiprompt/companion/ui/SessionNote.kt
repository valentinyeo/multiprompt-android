package dev.multiprompt.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object SessionNote {
    data class Note(val sender: String, val ticket: String?, val body: String)

    private val prefix = Regex("""^\s*\[from ([^\]\n]{1,80})\][ \t]*""")
    private val ticketId = Regex("""\b[A-Z][A-Z0-9]{1,7}-\d{1,6}\b""")

    fun parse(message: String): Note? {
        val match = prefix.find(message) ?: return null
        val sender = match.groupValues[1].trim()
        val body = message.substring(match.range.last + 1).trim()
        if (sender.isEmpty() || body.isEmpty()) return null
        return Note(sender, ticketId.find(body)?.value, body)
    }

    fun label(note: Note, recipient: String): String {
        val route = if (recipient.isBlank()) note.sender else "${note.sender} to $recipient"
        return if (note.ticket == null) route else "$route · ${note.ticket}"
    }
}

@Composable
internal fun SessionNoteChip(
    note: SessionNote.Note,
    recipient: String,
    sunlight: Boolean,
    fontScale: Float,
    key: String,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    val label = SessionNote.label(note, recipient)
    Column(modifier.fillMaxWidth().testTag("session-note-chip-$key")) {
        Row(
            Modifier
                .semantics {
                    contentDescription = "Message from $label"
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                }
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp)
                .testTag("session-note-toggle-$key"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = chatHintColor(sunlight),
                modifier = Modifier.size(14.dp),
            )
            Text(
                label,
                color = chatHintColor(sunlight),
                fontSize = (12f * fontScale).sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded) {
            val fontSize = chatFontSize((14f * fontScale).sp, fontScale)
            Column(Modifier.padding(start = 18.dp, bottom = 4.dp).testTag("session-note-body")) {
                ChatLinkText(note.body) { line ->
                    Text(
                        terminalLinks(line, sunlight),
                        fontFamily = chatFontFamily(),
                        fontSize = fontSize,
                        lineHeight = chatLineHeight((fontSize.value * 1.5f).sp, fontScale),
                        color = chatHintColor(sunlight),
                    )
                }
            }
        }
    }
}

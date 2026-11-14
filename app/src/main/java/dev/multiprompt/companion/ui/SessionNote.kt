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

    private const val SPACE = " \t\r\n"
    private const val ENVELOPE = "<cross-session-message"
    private const val ENVELOPE_CLOSE = "</cross-session-message>"
    private const val ANOTHER_SESSION = "another session"
    private const val SENDER_MAX = 63

    fun parse(message: String): Note? {
        val match = prefix.find(message) ?: return parseEnvelope(message)
        val sender = match.groupValues[1].trim()
        val body = message.substring(match.range.last + 1).trim()
        if (sender.isEmpty() || body.isEmpty()) return null
        return Note(sender, ticketId.find(body)?.value, body)
    }

    private fun parseEnvelope(message: String): Note? {
        val text = message.trim { it in SPACE }
        if (!text.startsWith(ENVELOPE) || text.length <= ENVELOPE.length) return null
        val next = text[ENVELOPE.length]
        if (next != '>' && next !in SPACE) return null
        val end = tagEnd(text) ?: return null
        val close = text.indexOf(ENVELOPE_CLOSE, end + 1)
        if (close < 0 || close + ENVELOPE_CLOSE.length != text.length) return null
        var sender = ANOTHER_SESSION
        var attrs = text.substring(ENVELOPE.length, end).trim { it in SPACE }
        while (attrs.isNotEmpty()) {
            val nameEnd = attrs.indexOfFirst { it == '=' || it in SPACE }
            if (nameEnd <= 0) return null
            val name = attrs.substring(0, nameEnd)
            attrs = attrs.substring(nameEnd).trimStart { it in SPACE }
            if (attrs.isEmpty() || attrs[0] != '=') return null
            attrs = attrs.substring(1).trimStart { it in SPACE }
            if (attrs.isEmpty() || (attrs[0] != '"' && attrs[0] != '\'')) return null
            val quote = attrs[0]
            attrs = attrs.substring(1)
            val valueEnd = attrs.indexOf(quote)
            if (valueEnd < 0) return null
            val value = attrs.substring(0, valueEnd)
            if (name == "from-name" && value.trim { it in SPACE }.isNotEmpty()) sender = value
            attrs = attrs.substring(valueEnd + 1)
            if (attrs.isNotEmpty() && attrs[0] !in SPACE) return null
            attrs = attrs.trimStart { it in SPACE }
        }
        val label = decode(sender, false).take(SENDER_MAX).map { if (it < ' ') ' ' else it }.joinToString("").trim()
        val body = decode(text.substring(end + 1, close).trim { it in SPACE }, true).trim { it in SPACE }
        return Note(label.ifEmpty { ANOTHER_SESSION }, ticketId.find(body)?.value, body)
    }

    private fun tagEnd(text: String): Int? {
        var quote = '\u0000'
        for ((index, c) in text.withIndex()) {
            if (quote != '\u0000') {
                if (c == quote) quote = '\u0000'
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                return index
            }
        }
        return null
    }

    private fun decode(text: String, stripTags: Boolean): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (stripTags && c == '<' && i + 1 < text.length && (text[i + 1] in 'a'..'z' || text[i + 1] in 'A'..'Z' || text[i + 1] == '/')) {
                val end = tagEnd(text.substring(i))
                if (end != null) {
                    i += end + 1
                    continue
                }
            }
            if (c == '&') {
                val semicolon = text.substring(i, minOf(text.length, i + 16)).indexOf(';')
                if (semicolon > 0) {
                    val point = entity(text.substring(i + 1, i + semicolon))
                    if (point != null) {
                        out.appendCodePoint(point)
                        i += semicolon + 1
                        continue
                    }
                }
            }
            out.append(c)
            i += 1
        }
        return out.toString()
    }

    private fun entity(name: String): Int? {
        when (name) {
            "amp" -> return '&'.code
            "lt" -> return '<'.code
            "gt" -> return '>'.code
            "quot" -> return '"'.code
            "apos" -> return '\''.code
        }
        if (name.length < 2 || name[0] != '#') return null
        val hex = name.length > 2 && name[1] == 'x'
        val digits = name.substring(if (hex) 2 else 1)
        if (digits.length > 12 || !digits.all { it in '0'..'9' || (hex && (it in 'a'..'f' || it in 'A'..'F')) }) return null
        val value = digits.toLongOrNull(if (hex) 16 else 10) ?: return null
        return if (value in 1L..0x10FFFFL && value !in 0xD800L..0xDFFFL) value.toInt() else null
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

package dev.multiprompt.companion.ssh

import dev.multiprompt.companion.model.TmuxSession

object TmuxParser {
    const val FIELD_SEPARATOR: String = "::MP_FIELD::"
    const val START_MARKER: String = "__MP_TMUX_BEGIN__"
    const val END_MARKER: String = "__MP_TMUX_END__"
    const val ERROR_PREFIX: String = "__MP_TMUX_ERROR__"
    const val PREVIEW_PREFIX: String = "__MP_TMUX_PREVIEW__"

    fun command(): String {
        val format = listOf(
            "#{session_name}",
            "#{session_windows}",
            "#{session_attached}",
            "#{session_activity}",
            "#{window_width}",
            "#{window_height}",
            "#{host}",
            // Last field: agents set the pane title, and that is what ZigShell shows on its
            // tabs. tmux leaves it as the machine hostname when nothing set one, hence #{host}.
            "#{pane_title}",
        ).joinToString(FIELD_SEPARATOR)
        return "printf '${START_MARKER}\\n'; " +
            "if command -v tmux >/dev/null 2>&1; then " +
            "tmux list-sessions -F '#{session_name}' | while IFS= read -r mp_session; do " +
            "tmux display-message -p -t \"\$mp_session:\" -F ${shellQuote(format)}; " +
            "printf '${PREVIEW_PREFIX}'; " +
            "tmux capture-pane -p -J -S -12 -t \"\$mp_session:\" 2>/dev/null | " +
            "tail -c 4096 | od -An -v -tx1 | tr -d ' \\n'; " +
            "printf '\\n'; done; " +
            "else printf '${ERROR_PREFIX}tmux_not_found\\n'; fi; " +
            "printf '${END_MARKER}\\n'"
    }

    fun hasEnvelope(output: String): Boolean =
        output.lineSequence().any { it.trimEnd() == START_MARKER } &&
            output.lineSequence().any { it.trimEnd() == END_MARKER }

    fun error(output: String): String? = output
        .lineSequence()
        .map(String::trimEnd)
        .firstOrNull { it.startsWith(ERROR_PREFIX) }
        ?.removePrefix(ERROR_PREFIX)

    fun parse(hostId: String, output: String): List<TmuxSession> {
        val sessions = mutableListOf<TmuxSession>()
        output.lineSequence().map(String::trimEnd).forEach { line ->
            if (line.startsWith(PREVIEW_PREFIX)) {
                val preview = decodeHex(line.removePrefix(PREVIEW_PREFIX))
                if (sessions.isNotEmpty()) {
                    sessions[sessions.lastIndex] = sessions.last().copy(preview = preview)
                }
                return@forEach
            }
            if (line.isBlank() || line == START_MARKER || line == END_MARKER || line.startsWith(ERROR_PREFIX)) {
                return@forEach
            }
            // Limit 8 so a separator inside the pane title stays part of the title.
            val fields = line.split(FIELD_SEPARATOR, limit = 8)
            if (fields.size < 4 || fields[0].isBlank()) return@forEach
            val serverHost = fields.getOrNull(6).orEmpty()
            val title = fields.getOrNull(7)?.trim().orEmpty()
            sessions += TmuxSession(
                hostId = hostId,
                name = fields[0],
                windows = fields[1].toIntOrNull() ?: return@forEach,
                attachedClients = fields[2].toIntOrNull() ?: return@forEach,
                lastActivityEpochSeconds = fields[3].toLongOrNull() ?: 0,
                columns = fields.getOrNull(4)?.toIntOrNull() ?: 0,
                rows = fields.getOrNull(5)?.toIntOrNull() ?: 0,
                title = if (title == serverHost) "" else title,
            )
        }
        return sessions.sortedWith(
            compareByDescending<TmuxSession> { it.lastActivityEpochSeconds }.thenBy { it.name },
        )
    }

    private fun decodeHex(value: String): String {
        if (value.length % 2 != 0 || value.any { it.digitToIntOrNull(16) == null }) return ""
        val bytes = ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return bytes.toString(Charsets.UTF_8).trim()
    }

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}

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
            "#{session_path}",
            "#{host}",
            "#{pane_current_command}",
            "#{window_name}",
            "#{pane_title}",
        ).joinToString(FIELD_SEPARATOR)
        return "printf '${START_MARKER}\\n'; " +
            "if command -v tmux >/dev/null 2>&1; then " +
            TmuxCommands.sessionRows(format) +
            "printf '${TmuxCommands.ETA_PREFIX}'; " +
            "{ ${TmuxCommands.etaRowsCommand()} ; } | od -An -v -tx1 | tr -d ' \\n'; " +
            "printf '\\n'; " +
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
                val preview = TmuxText.leftAligned(
                    TmuxText.decodeHex(line.removePrefix(PREVIEW_PREFIX)),
                )
                if (sessions.isNotEmpty()) {
                    sessions[sessions.lastIndex] = sessions.last().copy(preview = preview)
                }
                return@forEach
            }
            if (line.isBlank() || line == START_MARKER || line == END_MARKER || line.startsWith(ERROR_PREFIX)) {
                return@forEach
            }
            val fields = line.split(FIELD_SEPARATOR, limit = 11)
            if (fields.size < 4 || fields[0].isBlank()) return@forEach
            val serverHost = fields.getOrNull(7).orEmpty()
            val hasPaneCommand = fields.size >= 10
            val paneCommand = if (hasPaneCommand) fields[8].trim() else ""
            val hasWindowName = fields.size >= 11
            val windowName = fields.getOrNull(if (hasWindowName) 9 else -1)?.trim().orEmpty()
            val title = fields.getOrNull(
                when {
                    hasWindowName -> 10
                    hasPaneCommand -> 9
                    else -> 8
                },
            )?.trim().orEmpty()
            sessions += TmuxSession(
                hostId = hostId,
                name = fields[0],
                windows = fields[1].toIntOrNull() ?: return@forEach,
                attachedClients = fields[2].toIntOrNull() ?: return@forEach,
                lastActivityEpochSeconds = fields[3].toLongOrNull() ?: 0,
                columns = fields.getOrNull(4)?.toIntOrNull() ?: 0,
                rows = fields.getOrNull(5)?.toIntOrNull() ?: 0,
                workingDirectory = fields.getOrNull(6).orEmpty(),
                title = if (title == serverHost) "" else title,
                windowName = windowName,
                paneCommand = paneCommand,
            )
        }
        return sessions.sortedWith(
            compareByDescending<TmuxSession> { it.lastActivityEpochSeconds }.thenBy { it.name },
        )
    }

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    fun parseEtaStates(output: String): Map<String, TmuxText.SessionEtaState> {
        val payload = output.lineSequence()
            .firstOrNull { it.startsWith(TmuxCommands.ETA_PREFIX) }
            ?.removePrefix(TmuxCommands.ETA_PREFIX)
            ?.trim()
            ?: return emptyMap()
        if (payload.isEmpty()) return emptyMap()
        return TmuxText.parseSessionEtaStates(TmuxText.decodeHex(payload))
    }
}

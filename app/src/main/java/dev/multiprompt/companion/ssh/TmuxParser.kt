package dev.multiprompt.companion.ssh

import dev.multiprompt.companion.model.TmuxSession

object TmuxParser {
    const val FIELD_SEPARATOR: String = "::MP_FIELD::"
    const val START_MARKER: String = "__MP_TMUX_BEGIN__"
    const val END_MARKER: String = "__MP_TMUX_END__"
    const val ERROR_PREFIX: String = "__MP_TMUX_ERROR__"
    const val PREVIEW_PREFIX: String = "__MP_TMUX_PREVIEW__"
    private val SESSION_ID = Regex("[A-Za-z0-9_-]{1,80}")

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
            "#{@mp_host_sid}",
            "#{window_activity}",
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
            val fields = line.split(FIELD_SEPARATOR, limit = 13)
            if (fields.size < 4 || fields[0].isBlank()) return@forEach
            val count = fields.size
            val serverHost = fields.getOrNull(7).orEmpty()
            val paneCommand = if (count >= 10) fields[8].trim() else ""
            val windowName = if (count >= 11) fields[9].trim() else ""
            val hostSessionId = if (count >= 12) fields[10].trim().takeIf { SESSION_ID.matches(it) }.orEmpty() else ""
            val title = fields.getOrNull(
                when {
                    count >= 13 -> 12
                    count >= 12 -> 11
                    count >= 11 -> 10
                    count >= 10 -> 9
                    else -> 8
                },
            )?.trim().orEmpty()
            sessions += TmuxSession(
                hostId = hostId,
                name = fields[0],
                windows = fields[1].toIntOrNull() ?: return@forEach,
                attachedClients = fields[2].toIntOrNull() ?: return@forEach,
                lastActivityEpochSeconds = fields[3].toLongOrNull() ?: 0,
                outputActivityEpochSeconds = if (count >= 13) fields[11].toLongOrNull() ?: 0 else 0,
                columns = fields.getOrNull(4)?.toIntOrNull() ?: 0,
                rows = fields.getOrNull(5)?.toIntOrNull() ?: 0,
                workingDirectory = fields.getOrNull(6).orEmpty(),
                title = if (title == serverHost) "" else title,
                windowName = windowName,
                paneCommand = paneCommand,
                hostSessionId = hostSessionId,
            )
        }
        val replies = parseLiveReplies(output)
        return sessions.map { session ->
            replies[session.name]?.takeIf { session.liveChat }?.let {
                session.copy(replyAtEpochSeconds = it.atEpochSeconds, replyWaiting = it.waiting)
            } ?: session
        }.sortedWith(
            compareByDescending<TmuxSession> { it.lastActivityEpochSeconds }.thenBy { it.name },
        )
    }

    fun parseLiveReplies(output: String): Map<String, TmuxText.LiveReply> =
        etaPayload(output)?.let(TmuxText::parseLiveReplies).orEmpty()

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    fun parseEtaStates(output: String): Map<String, TmuxText.SessionEtaState> =
        etaPayload(output)?.let(TmuxText::parseSessionEtaStates).orEmpty()

    private fun etaPayload(output: String): String? = output.lineSequence()
        .firstOrNull { it.startsWith(TmuxCommands.ETA_PREFIX) }
        ?.removePrefix(TmuxCommands.ETA_PREFIX)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let(TmuxText::decodeHex)
}

package dev.multiprompt.companion.ssh

import dev.multiprompt.companion.model.TmuxSession

object TmuxParser {
    const val FIELD_SEPARATOR: Char = '\u001f'

    fun command(): String {
        val format = listOf(
            "#{session_name}",
            "#{session_windows}",
            "#{session_attached}",
            "#{session_activity}",
        ).joinToString(FIELD_SEPARATOR.toString())
        return "tmux list-sessions -F ${shellQuote(format)} 2>/dev/null || true"
    }

    fun parse(hostId: String, output: String): List<TmuxSession> = output
        .lineSequence()
        .map(String::trimEnd)
        .filter(String::isNotBlank)
        .mapNotNull { line ->
            val fields = line.split(FIELD_SEPARATOR)
            if (fields.size != 4 || fields[0].isBlank()) return@mapNotNull null
            TmuxSession(
                hostId = hostId,
                name = fields[0],
                windows = fields[1].toIntOrNull() ?: return@mapNotNull null,
                attachedClients = fields[2].toIntOrNull() ?: return@mapNotNull null,
                lastActivityEpochSeconds = fields[3].toLongOrNull() ?: 0,
            )
        }
        .sortedWith(compareByDescending<TmuxSession> { it.lastActivityEpochSeconds }.thenBy { it.name })
        .toList()

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}


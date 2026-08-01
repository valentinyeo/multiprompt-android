package dev.multiprompt.companion.ssh

import dev.multiprompt.companion.model.TmuxSession

object TmuxParser {
    const val FIELD_SEPARATOR: String = "::MP_FIELD::"
    const val START_MARKER: String = "__MP_TMUX_BEGIN__"
    const val END_MARKER: String = "__MP_TMUX_END__"
    const val ERROR_PREFIX: String = "__MP_TMUX_ERROR__"

    fun command(): String {
        val format = listOf(
            "#{session_name}",
            "#{session_windows}",
            "#{session_attached}",
            "#{session_activity}",
        ).joinToString(FIELD_SEPARATOR)
        return "printf '${START_MARKER}\\n'; " +
            "if command -v tmux >/dev/null 2>&1; then " +
            "tmux list-sessions -F ${shellQuote(format)}; " +
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

    fun parse(hostId: String, output: String): List<TmuxSession> = output
        .lineSequence()
        .map(String::trimEnd)
        .filter { line ->
            line.isNotBlank() &&
                line != START_MARKER &&
                line != END_MARKER &&
                !line.startsWith(ERROR_PREFIX)
        }
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

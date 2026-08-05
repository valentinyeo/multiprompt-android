package dev.multiprompt.companion.model

data class TmuxSession(
    val hostId: String,
    val name: String,
    val windows: Int,
    val attachedClients: Int,
    val lastActivityEpochSeconds: Long,
    /** Width the desktop gave this tmux window. The phone renders it without changing it. */
    val columns: Int = 0,
    val rows: Int = 0,
    val workingDirectory: String = "",
    val title: String = "",
    val preview: String = "",
    val paneCommand: String = "",
) {
    val agent: AgentKind
        get() = AgentKind.detect(name, title, preview, paneCommand)

    /** What ZigShell shows on its tabs: the agent's pane title, falling back to the tmux name. */
    val displayName: String
        get() = title.ifBlank { name }
}

enum class AgentKind(val label: String) {
    CLAUDE("Claude"),
    CODEX("Codex"),
    PI("Pi"),
    KIMI("Kimi"),
    OTHER("Shell");

    companion object {
        fun detect(name: String, title: String = "", preview: String = "", paneCommand: String = ""): AgentKind {
            val command = paneCommand.lowercase().substringAfterLast('/').substringAfterLast('\\')
            when {
                command == "codex" || command.startsWith("codex-") -> return CODEX
                command == "claude" || command.startsWith("claude-") -> return CLAUDE
                command == "pi" || command.startsWith("pi-") -> return PI
                command == "kimi" || command.startsWith("kimi-") -> return KIMI
            }

            val normalized = name.lowercase()
            val namedAgent = when {
                "codex" in normalized || normalized.startsWith("cx-") -> CODEX
                "kimi" in normalized -> KIMI
                "claude" in normalized || normalized.startsWith("cl-") -> CLAUDE
                PI_NAME.containsMatchIn(normalized) -> PI
                else -> OTHER
            }
            if (namedAgent != OTHER) return namedAgent

            val terminalText = "$title\n$preview".lowercase()
            return when {
                "openai codex" in terminalText || "codex>" in terminalText -> CODEX
                "claude code" in terminalText || "shift+tab to cycle" in terminalText ||
                    "bypass permissions" in terminalText -> CLAUDE
                "kimi code" in terminalText || "kimi cli" in terminalText -> KIMI
                "pi coding agent" in terminalText -> PI
                else -> OTHER
            }
        }

        private val PI_NAME = Regex("(^|[-_])pi($|[-_])")
    }
}

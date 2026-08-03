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
) {
    val agent: AgentKind
        get() = AgentKind.fromName(name)

    /** What ZigShell shows on its tabs: the agent's pane title, falling back to the tmux name. */
    val displayName: String
        get() = title.ifBlank { name }
}

enum class AgentKind(val label: String) {
    CLAUDE("Claude"),
    CODEX("Codex"),
    KIMI("Kimi"),
    OTHER("Shell");

    companion object {
        fun fromName(name: String): AgentKind {
            val normalized = name.lowercase()
            return when {
                "codex" in normalized || normalized.startsWith("cx-") -> CODEX
                "kimi" in normalized -> KIMI
                "claude" in normalized || normalized.startsWith("cl-") -> CLAUDE
                else -> OTHER
            }
        }
    }
}
